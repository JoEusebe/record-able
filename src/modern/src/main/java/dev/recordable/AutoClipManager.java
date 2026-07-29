package dev.recordable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Player;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

/**
 * Manages automatic short-clip recording triggered by in-game events.
 *
 * <p>Supported triggers:</p>
 * <ul>
 *     <li>Achievement/advancement unlocked</li>
 *     <li>Player death</li>
 *     <li>Dimension change (Nether, End, Overworld)</li>
 *     <li>Boss kill (Ender Dragon, Wither)</li>
 * </ul>
 *
 * <p>When a trigger fires:</p>
 * <ol>
 *     <li>Checks if already recording (skips if yes)</li>
 *     <li>Starts recording automatically</li>
 *     <li>Schedules stop after {@code autoClipDuration} seconds</li>
 *     <li>Shows toast notification with trigger reason</li>
 * </ol>
 */
public final class AutoClipManager {
    private static final int TICKS_PER_SECOND = 20;

    /** Countdown ticks until auto-clip stops. -1 = not active. */
    private volatile int autoClipStopCountdown = -1;
    /** The reason the auto-clip was started, for display purposes. */
    private volatile String autoClipReason = null;
    /**
     * Whether the auto-clip system itself started the current recording. Only when this is
     * {@code true} may {@link #stopAutoClip} stop the recording. This prevents an auto-clip
     * event (e.g. an achievement) from cutting short a recording the user started manually.
     */
    private volatile boolean autoClipOwnsRecording = false;
    /** Cooldown to prevent rapid re-triggering (5 seconds). */
    private volatile long lastAutoClipTriggerMs = 0;
    private static final long AUTO_CLIP_COOLDOWN_MS = 5_000;

    /** Track current dimension for change detection. */
    private volatile ResourceKey<Level> lastDimension = null;

    /** Track player health for death detection (client-side). */
    private volatile boolean wasPlayerAlive = true;

    /** Window (in ticks) after attacking an entity during which its death counts as a kill. */
    private static final int KILL_TRACK_WINDOW_TICKS = 200; // ~10s
    /** The entity the player most recently attacked, tracked for kill detection. */
    private WeakReference<LivingEntity> killTrackTarget = null;
    /** Whether the tracked target is another player (for PvP detection). */
    private boolean killTrackTargetIsPlayer = false;
    /** Remaining ticks before the tracked target is forgotten. */
    private int killTrackDeadlineTicks = -1;
    /** The last target a kill clip already fired for, to avoid duplicate triggers. */
    private WeakReference<LivingEntity> killLastProcessed = null;

    /**
     * Victim of a confirmed player-owned projectile hit, published by {@code ProjectileEntityMixin}
     * (event-based) and consumed on the next client tick. Stored as a volatile WeakReference so the
     * mixin - which may run on the integrated-server thread - can hand the entity off without
     * mutating the tracking fields (which are only touched from the client tick thread). Replaces
     * the old unreliable per-tick spatial overlap scan for ranged kills (spear/trident/arrow).
     */
    private volatile WeakReference<LivingEntity> pendingProjectileVictim = null;

    /** Track player's last known totem-pop health state for totem-pop detection. */
    private volatile boolean wasPlayerNearDeath = false;
    /** Tracks totem-related effects to detect new pop events. */
    private volatile boolean hadTotemEffectsLastTick = false;

    /**
     * Attribution guard: ticks since the local player last swung their arm (attack action).
     * The melee/spear fallback only attributes a kill to the local player when this is small,
     * preventing entities damaged by OTHER mobs/players from being falsely credited to us.
     */
    private int ticksSincePlayerSwing = 99_999;
    /** How recently the player must have swung for the fallback scan to attribute a hit (ticks). */
    private static final int SWING_GRACE_TICKS = 20; // ~1s; covers charge-release -> impact delay of spears/lances
    /** Maximum reach (blocks) for aim-based melee fallback attribution (covers extended-reach spears). */
    private static final double FALLBACK_REACH = 10.0; // Extended for spears/lances

    public void initialize() {
        RecordableMod.LOGGER.info("AutoClipManager initialized.");
    }

    /**
     * Returns {@code true} if the entity is an actual mob/creature (or player) that should
     * count as a "kill" for auto-clip purposes. Returns {@code false} for non-mob
     * LivingEntity subclasses like armor stands, ender crystals (those aren't LivingEntity
     * anyway), item frames, etc. This prevents destroying decorative entities from falsely
     * triggering kill clips.
     */
    private static boolean isActualMob(LivingEntity entity) {
        if (entity instanceof Player) return true; // PvP kills always count
        return entity instanceof Mob; // Mob is the base for all AI-driven creatures
    }

    /** Safe display name for logging, never throws. */
    private static String nameOf(LivingEntity e) {
        try {
            return e.getName() != null ? e.getName().getString() : String.valueOf(e.getType());
        } catch (Throwable t) {
            return "entity";
        }
    }

    /**
     * Begins tracking {@code living} as the local player's pending kill target. A subsequent
     * confirmed death of this entity (health &lt;= 0) within {@link #KILL_TRACK_WINDOW_TICKS}
     * fires the kill clip. {@code how} describes the attribution path for debug logging.
     */
    private void beginTracking(LivingEntity living, String how, RecordableConfig config) {
        // Filter out non-mob entities (armor stands, marker entities, etc.)
        if (!isActualMob(living)) {
            RecordableMod.LOGGER.debug("[AutoClip] Ignoring non-mob entity for kill detection ({}): {}", how, nameOf(living));
            return;
        }
        killTrackTarget = new WeakReference<>(living);
        killTrackTargetIsPlayer = living instanceof Player;
        killTrackDeadlineTicks = KILL_TRACK_WINDOW_TICKS;
        RecordableMod.LOGGER.debug("[AutoClip] Tracking entity for kill detection ({}): {}", how, nameOf(living));
        armKillMontageIfEnabled(config);
    }

    /** Updates {@link #ticksSincePlayerSwing} once per tick from the player's arm-swing state. */
    private void updatePlayerSwingTracker(Minecraft client) {
        if (client.player != null && isPlayerCombatActive(client)) {
            ticksSincePlayerSwing = 0;
        } else if (ticksSincePlayerSwing < 99_999) {
            ticksSincePlayerSwing++;
        }
    }

    /**
     * Whether the local player is performing (or just performed) a combat action this tick.
     *
     * <p>Covers two cases:</p>
     * <ul>
     *     <li>Normal melee: the vanilla arm-swing animation ({@code swinging}).</li>
     *     <li>Charging melee weapons (spears/lances): these custom weapons frequently do NOT
     *     fire the vanilla arm-swing. Instead they charge while the attack (or use) key is
     *     held and strike on release. Treating a held attack/use key as an active combat
     *     action lets the spear/lance kill be attributed to the player even without a swing.</li>
     * </ul>
     */
    private static boolean isPlayerCombatActive(Minecraft client) {
        if (client.player != null && client.player.swinging) return true;
        try {
            if (client.options != null) {
                if (client.options.keyAttack != null && client.options.keyAttack.isDown()) return true;
                if (client.options.keyUse != null && client.options.keyUse.isDown()) return true;
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /**
     * Finds the living entity the local player is currently aiming at (within {@link #FALLBACK_REACH})
     * that is actively taking damage or dying. Uses a precise look-ray vs. bounding-box intersection
     * so it works for extended-reach weapons (spears) and ignores entities off to the side that are
     * merely being hurt by something else. Returns {@code null} when no aimed, damaged entity exists.
     */
    private LivingEntity findAimedDamagedEntity(Minecraft client, LivingEntity processed) {
        Player player = client.player;
        net.minecraft.world.phys.Vec3 eye = player.getEyePosition();
        net.minecraft.world.phys.Vec3 look = player.getViewVector(1.0f);
        net.minecraft.world.phys.Vec3 end = eye.add(look.scale(FALLBACK_REACH));
        net.minecraft.world.phys.AABB searchBox =
                player.getBoundingBox().expandTowards(look.scale(FALLBACK_REACH)).inflate(1.0);

        LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (net.minecraft.world.entity.Entity e : client.level.getEntities(player, searchBox, en -> true)) {
            if (!(e instanceof LivingEntity living)) continue;
            if (living == processed) continue;
            if (living.isRemoved()) continue;
            // Must be actively taking damage (hurtTime > 0) or already dying.
            if (living.hurtTime <= 0 && living.getHealth() > 0.0f) continue;
            // Precise aim check: the player's view ray must intersect the entity's hitbox.
            java.util.Optional<net.minecraft.world.phys.Vec3> hit =
                    living.getBoundingBox().inflate(0.3).clip(eye, end);
            if (hit.isEmpty()) continue;
            double d = eye.distanceToSqr(hit.get());
            if (d < bestDist) {
                bestDist = d;
                best = living;
            }
        }
        return best;
    }

    /**
     * Called every client tick to check for trigger events and manage auto-clip countdown.
     */
    public void onClientTick(Minecraft client) {
        if (client == null || client.player == null || client.level == null) {
            lastDimension = null;
            wasPlayerAlive = true;
            wasPlayerNearDeath = false;
            killTrackTarget = null;
            killTrackDeadlineTicks = -1;
            ticksSincePlayerSwing = 99_999;
            lastXpLevel = -1;
            wasLowHealth = false;
            wasInventoryFull = false;
            resetHindsightState();
            return;
        }

        // Track the player's arm-swing every tick so the melee fallback can require a recent
        // local attack before attributing a damaged entity's death to us.
        updatePlayerSwingTracker(client);

        RecordableConfig config;
        try {
            config = RecordableConfig.get();
        } catch (Throwable t) {
            return;
        }

        // Handle auto-clip stop countdown
        if (autoClipStopCountdown >= 0) {
            autoClipStopCountdown--;
            if (autoClipStopCountdown < 0) {
                stopAutoClip(client);
            }
            return; // Don't check for new triggers while an auto-clip is active
        }

        // Master toggle: skip all trigger checks when auto-clipping is disabled.
        if (!config.autoClipEnabled) {
            updateTrackedState(client);
            return;
        }

        if (config.autoClipHindsightEnabled) {
            maintainHindsightBuffer(client, config);
            trackHindsightSignals(client);
        }

        // Check individual triggers
        if (config.autoClipOnDeath || config.autoClipHindsightEnabled) {
            checkDeathTrigger(client, config);
        }

        if (config.autoClipOnDimensionChange) {
            checkDimensionChangeTrigger(client, config);
        }

        if (config.autoClipOnAchievement) {
            checkAchievementTrigger(client, config);
        }

        // Boss kill detection is event-based, checked via checkBossKill called from tick
        if (config.autoClipOnBossKill) {
            // Boss kill detection is harder on client-side. We track it via the
            // boss bar disappearing, which is a reasonable heuristic.
            // For now, boss kill detection is handled by checking if the boss bar
            // was present and now disappeared. This is a best-effort approach.
        }

        if (config.autoClipOnKill || config.autoClipOnPlayerKill) {
            consumePendingProjectileVictim(config);
            scanForRecentlyDamagedEntities(client, config);
            checkKillTrigger(client, config);
        }

        if (config.autoClipOnTotemPop) {
            checkTotemPopTrigger(client, config);
        }

        if (config.autoClipOnCustomEvent) {
            checkCustomEventTriggers(client, config);
        }

        updateTrackedState(client);
    }

    /**
     * Detects when an entity the local player recently attacked has died, and
     * fires the appropriate auto-clip ("Player Kill" for PvP, otherwise "Kill").
     * Uses {@code getLastHurtMob()} which the client sets whenever the player attacks.
     */
    private void checkKillTrigger(Minecraft client, RecordableConfig config) {
        if (client.player == null) return;
        // ATTRIBUTION: getLastHurtMob() is the entity the LOCAL player most recently damaged.
        // Tracking from this value guarantees the kill is credited to us, not to another entity.
        LivingEntity attacked = client.player.getLastHurtMob();
        LivingEntity tracked = killTrackTarget != null ? killTrackTarget.get() : null;

        // Begin tracking a newly attacked entity (skip one already processed / already removed).
        if (attacked != null && attacked != tracked && !attacked.isRemoved()) {
            LivingEntity processed = killLastProcessed != null ? killLastProcessed.get() : null;
            if (attacked != processed) {
                beginTracking(attacked, "direct attack (getLastHurtMob)", config);
                tracked = attacked;
            }
        }

        if (tracked == null) return;

        float health = tracked.getHealth();
        // A real kill is confirmed only when the entity's health has actually dropped to zero
        // (or it is in its death state). This distinguishes a true death from a despawn / chunk
        // unload, which would otherwise make isAlive() return false and cause a false positive.
        boolean died = health <= 0.0f || tracked.isDeadOrDying();
        boolean removedWhileAlive = tracked.isRemoved() && health > 0.0f;

        if (died) {
            boolean isPlayer = killTrackTargetIsPlayer;
            killLastProcessed = new WeakReference<>(tracked); // dedupe future ticks
            killTrackTarget = null;
            killTrackDeadlineTicks = -1;
            String name = nameOf(tracked);
            RecordableMod.LOGGER.info("[AutoClip] Confirmed local-player kill: {} (health={}, pvp={})",
                    name, health, isPlayer);
            if (isPlayer) {
                if (config.autoClipOnPlayerKill) {
                    fireKill(client, config, "Player Kill");
                }
            } else if (config.autoClipOnKill) {
                fireKill(client, config, "Kill: " + name);
            }
        } else if (removedWhileAlive) {
            // Entity left the world (despawn / unload / teleport) without dying -> NOT our kill.
            RecordableMod.LOGGER.debug("[AutoClip] Tracked entity removed while alive; not crediting a kill: {}",
                    nameOf(tracked));
            killTrackTarget = null;
            killTrackDeadlineTicks = -1;
        } else if (--killTrackDeadlineTicks < 0) {
            RecordableMod.LOGGER.debug("[AutoClip] Kill-tracking window expired without a kill: {}", nameOf(tracked));
            killTrackTarget = null; // window expired without a kill
        }
    }

    /**
     * If kill-montage capture is enabled, begin (or refresh) the rolling pre-roll buffer so a
     * subsequent kill can include the second(s) before the finishing blow. No-op otherwise.
     */
    private void armKillMontageIfEnabled(RecordableConfig config) {
        try {
            if (!config.autoClipEnabled) return;
            if (!(config.autoClipOnKill || config.autoClipOnPlayerKill)) return;
            if (!config.autoClipKillMontage) return;
            int[] dims = resolveMontageDimensions();
            if (dims == null) return;
            KillClipBuffer.getInstance().arm(Minecraft.getInstance(), dims[0], dims[1], dims[2],
                    config.autoClipKillPreSeconds, config.autoClipKillPostSeconds);
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("Kill-montage arm skipped.", t);
        }
    }

    /** Returns {width, height, fps} for montage capture, or null if the window is unavailable. */
    private static int[] resolveMontageDimensions() {
        try {
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.getWindow() == null) return null;
            int nativeWidth = client.getWindow().getWidth();
            int nativeHeight = client.getWindow().getHeight();
            if (nativeWidth <= 0 || nativeHeight <= 0) return null;
            RecordableConfig config = RecordableConfig.get();
            RecordableConfig.CaptureDimensions d = config.resolveCaptureDimensions(nativeWidth, nativeHeight);
            return new int[]{ Math.max(2, d.width()), Math.max(2, d.height()), config.autoClipFps };
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * Fires a kill trigger as either a kill-montage clip (pre-roll + kill + post-roll) when
     * {@code autoClipKillMontage} is enabled, or the standard forward auto-clip otherwise.
     */
    private void fireKill(Minecraft client, RecordableConfig config, String reason) {
        if (config.autoClipKillMontage) {
            long now = System.currentTimeMillis();
            if (now - lastAutoClipTriggerMs < AUTO_CLIP_COOLDOWN_MS) {
                RecordableMod.LOGGER.debug("Kill montage skipped (cooldown): {}", reason);
                return;
            }
            lastAutoClipTriggerMs = now;
            int[] dims = resolveMontageDimensions();
            if (dims == null) {
                triggerAutoClip(client, config, reason);
                return;
            }
            String prefix = sanitizeForFilename(reason);
            KillClipBuffer.getInstance().triggerKill(client, reason, prefix, dims[0], dims[1], dims[2],
                    config.autoClipKillPreSeconds, config.autoClipKillPostSeconds);
        } else {
            triggerAutoClip(client, config, reason);
        }
    }

    /**
     * Fallback melee/attack detection for weapons (spears, modded items) whose custom attack
     * mechanics bypass polling {@code getLastHurtMob()}. Scans for any living entity within
     * extended attack range (up to 10 blocks for charging spears/lances) that is actively 
     * taking damage, and begins tracking it for a kill clip.
     */
    private void scanForRecentlyDamagedEntities(Minecraft client, RecordableConfig config) {
        try {
            if (client.player == null || client.level == null) return;
            // Keep an already-tracked, still-alive target instead of re-acquiring.
            LivingEntity current = killTrackTarget != null ? killTrackTarget.get() : null;
            if (current != null && current.isAlive()) return;

            // ATTRIBUTION GATE: only attribute a melee/spear kill to the local player when the
            // player actually swung their arm recently. Without this, an entity being damaged by
            // ANOTHER mob or player nearby would be falsely credited to us (the main false-positive
            // and mis-attribution source in the old radius-only scan).
            if (ticksSincePlayerSwing > SWING_GRACE_TICKS) return;

            LivingEntity processed = killLastProcessed != null ? killLastProcessed.get() : null;
            // Require the damaged entity to be the one the player is actually AIMING at (precise
            // look-ray intersection), not merely within radius. Covers extended-reach spears.
            LivingEntity living = findAimedDamagedEntity(client, processed);
            if (living == null) return;

            beginTracking(living, "melee/spear fallback (recent swing + aim)", config);
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("Recent damage scan failed.", t);
        }
    }

    /**
     * Event-based projectile-kill detection. Called by {@code ProjectileEntityMixin} at the
     * exact moment a projectile strikes an entity (an injection into
     * {@code Projectile.onHitEntity}). This is guaranteed single-tick capture: it fires before
     * the trident bounces away (Loyalty), embeds in the ground, or the fast-moving arrow/trident
     * disappears from the client entity list - the failure modes that made the old per-tick
     * spatial overlap scan unreliable for ranged kills (spear/trident/arrow).
     *
     * <p>This method may be invoked from the integrated-server thread, so it must NOT touch the
     * kill-tracking fields directly. It only validates attribution (the projectile must be owned
     * by the local player, matched by UUID rather than reference identity) and publishes the
     * victim into {@link #pendingProjectileVictim} for the client tick thread to consume.</p>
     *
     * @param owner  the projectile's owner (from {@code Projectile.getOwner()})
     * @param victim the entity the projectile hit (from {@code EntityHitResult.getEntity()})
     */
    public void onProjectileHitEntity(net.minecraft.world.entity.Entity owner, net.minecraft.world.entity.Entity victim) {
        try {
            if (owner == null) return;
            if (!(victim instanceof LivingEntity living)) return;
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.player == null) return;
            // ATTRIBUTION: only projectiles owned by the local player count. Compare by UUID
            // (not reference identity) so it stays correct across client / integrated-server
            // entity instances and after world transitions or entity reloads.
            if (!owner.getUUID().equals(mc.player.getUUID())) return;
            if (living == mc.player || living.isRemoved()) return;
            pendingProjectileVictim = new WeakReference<>(living);
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("Projectile-hit event handling failed.", t);
        }
    }

    /**
     * Consumes a victim published by {@link #onProjectileHitEntity} on the client tick thread
     * and begins kill-tracking it, so its imminent death fires the kill clip. Runs the same
     * de-duplication / already-tracking guards as the melee path.
     */
    private void consumePendingProjectileVictim(RecordableConfig config) {
        WeakReference<LivingEntity> ref = pendingProjectileVictim;
        if (ref == null) return;
        pendingProjectileVictim = null; // consume once
        LivingEntity living = ref.get();
        if (living == null || living.isRemoved()) return;
        // Keep an already-tracked, still-alive target instead of re-acquiring.
        LivingEntity current = killTrackTarget != null ? killTrackTarget.get() : null;
        if (current != null && current.isAlive()) return;
        LivingEntity processed = killLastProcessed != null ? killLastProcessed.get() : null;
        if (living == processed) return; // already fired for this entity
        beginTracking(living, "player-owned projectile hit (event)", config);
    }

    /**
     * Detects when the local player's totem of undying pops (saved from lethal damage).
     * The heuristic watches for a sudden health recovery after being at very low health,
     * combined with the totem use particle/sound effect (the player's health jumps from
     * near-zero back up in a single tick when the totem activates).
     */
    private void checkTotemPopTrigger(Minecraft client, RecordableConfig config) {
        if (client.player == null) {
            wasPlayerNearDeath = false;
            hadTotemEffectsLastTick = false;
            return;
        }
        float health = client.player.getHealth();
        boolean nearDeath = health <= 1.0f;
        boolean hasTotemEffects = false;
        try {
            hasTotemEffects = client.player.hasEffect(net.minecraft.world.effect.MobEffects.REGENERATION)
                    || client.player.hasEffect(net.minecraft.world.effect.MobEffects.ABSORPTION)
                    || client.player.hasEffect(net.minecraft.world.effect.MobEffects.FIRE_RESISTANCE);
        } catch (Throwable ignored) {
        }

        boolean gainedTotemEffects = hasTotemEffects && !hadTotemEffectsLastTick;
        boolean likelyTotemPop = gainedTotemEffects && client.player.isAlive()
                && (wasPlayerNearDeath || nearDeath || health <= 4.0f);

        if (likelyTotemPop) {
            RecordableMod.LOGGER.info("[AutoClip] Totem of Undying pop detected!");
            triggerAutoClip(client, config, "Totem Pop");
        }

        wasPlayerNearDeath = nearDeath;
        hadTotemEffectsLastTick = hasTotemEffects;
    }

    /**
     * Called by packet-level hooks when the client receives the local-player totem-use event.
     * This path is deterministic and complements the heuristic polling fallback.
     */
    public void onTotemPopObserved(Minecraft client) {
        RecordableConfig config;
        try {
            config = RecordableConfig.get();
        } catch (Throwable t) {
            return;
        }
        if (!config.autoClipEnabled) return;
        if (!config.autoClipOnTotemPop) return;
        triggerAutoClip(client, config, "Totem Pop");
    }

    /**
     * Called externally (e.g., from game event listeners or custom hooks) to trigger
     * an auto-clip for a custom event. The event name is used for the clip filename
     * and chat notification.
     */
    public void onCustomEvent(Minecraft client, String eventName) {
        RecordableConfig config;
        try {
            config = RecordableConfig.get();
        } catch (Throwable t) {
            return;
        }
        if (!config.autoClipEnabled) return;
        if (!config.autoClipOnCustomEvent) return;
        // Check if this event type is in the user's enabled custom events list
        if (config.customEventTriggers.isEmpty()) return;
        String lower = eventName.toLowerCase(java.util.Locale.ROOT);
        boolean matched = false;
        for (String trigger : config.customEventTriggers) {
            if (trigger != null && lower.contains(trigger.toLowerCase(java.util.Locale.ROOT))) {
                matched = true;
                break;
            }
        }
        if (!matched) return;
        triggerAutoClip(client, config, "Event: " + eventName);
    }

    /**
     * Checks all enabled custom event triggers each tick. Currently supports:
     * - "low_health" - player health drops below 20%
     * - "full_inventory" - player inventory becomes full
     * - "xp_level_up" - player gains an experience level
     * - "enter_structure" - player enters a structure (village, fortress, etc.)
     * - "weather_change" - weather changes (rain/thunder starts or stops)
     * - "time_of_day" - dawn/dusk transitions
     */
    private int lastXpLevel = -1;
    private boolean wasRaining = false;
    private boolean wasThundering = false;
    private boolean wasLowHealth = false;
    private boolean wasInventoryFull = false;
    private int hindsightLowHealthTicks = 0;
    private int hindsightLowFoodTicks = 0;
    private int hindsightBurningTicks = 0;
    private int hindsightLavaTicks = 0;
    private int hindsightMagmaTicks = 0;
    private int hindsightCactusTicks = 0;
    private int hindsightHighFallTicks = 0;
    private int hindsightPearlSpends = 0;
    private int hindsightLastPearlCount = -1;
    private long hindsightLastBufferArmMs = 0;

    private void checkCustomEventTriggers(Minecraft client, RecordableConfig config) {
        if (client.player == null || client.level == null) return;
        if (!config.autoClipOnCustomEvent) return;
        if (config.customEventTriggers.isEmpty()) return;

        for (String trigger : config.customEventTriggers) {
            if (trigger == null) continue;
            String t = trigger.toLowerCase(java.util.Locale.ROOT).trim();
            switch (t) {
                case "low_health" -> {
                    float healthPercent = client.player.getHealth() / client.player.getMaxHealth();
                    boolean isLow = healthPercent <= 0.2f && healthPercent > 0.0f;
                    if (isLow && !wasLowHealth) {
                        triggerAutoClip(client, config, "Low Health");
                    }
                    wasLowHealth = isLow;
                }
                case "xp_level_up" -> {
                    int currentLevel = client.player.experienceLevel;
                    if (lastXpLevel >= 0 && currentLevel > lastXpLevel) {
                        triggerAutoClip(client, config, "Level Up (" + currentLevel + ")");
                    }
                    lastXpLevel = currentLevel;
                }
                case "weather_change" -> {
                    boolean raining = client.level.isRaining();
                    boolean thundering = client.level.isThundering();
                    if (raining != wasRaining || thundering != wasThundering) {
                        if (wasRaining || wasThundering) { // Only trigger on actual weather changes, not initial state
                            String weather = thundering ? "Thunder" : raining ? "Rain" : "Clear";
                            triggerAutoClip(client, config, "Weather: " + weather);
                        }
                    }
                    wasRaining = raining;
                    wasThundering = thundering;
                }
                case "full_inventory" -> {
                    boolean full = true;
                    try {
                        for (int i = 0; i < 36; i++) {
                            if (client.player.getInventory().getItem(i).isEmpty()) {
                                full = false;
                                break;
                            }
                        }
                    } catch (Throwable ignored) {
                        full = false;
                    }
                    if (full && !wasInventoryFull) {
                        triggerAutoClip(client, config, "Inventory Full");
                    }
                    wasInventoryFull = full;
                }
                default -> {} // Unknown trigger, skip
            }
        }
    }

    private void updateTrackedState(Minecraft client) {
        if (client.player != null) {
            wasPlayerAlive = client.player.isAlive();
            lastDimension = client.level != null ? client.level.dimension() : null;
        }
    }

    private void resetHindsightState() {
        hindsightLowHealthTicks = 0;
        hindsightLowFoodTicks = 0;
        hindsightBurningTicks = 0;
        hindsightLavaTicks = 0;
        hindsightMagmaTicks = 0;
        hindsightCactusTicks = 0;
        hindsightHighFallTicks = 0;
        hindsightPearlSpends = 0;
        hindsightLastPearlCount = -1;
    }

    private void maintainHindsightBuffer(Minecraft client, RecordableConfig config) {
        long now = System.currentTimeMillis();
        if (now - hindsightLastBufferArmMs < 2_000L) {
            return;
        }
        int[] dims = resolveMontageDimensions();
        if (dims == null) {
            return;
        }
        int preSeconds = Math.max(5, Math.min(60, config.autoClipHindsightLookbackSeconds));
        int postSeconds = Math.max(2, Math.min(10, config.autoClipDuration / 3));
        try {
            KillClipBuffer.getInstance().arm(client, dims[0], dims[1], dims[2], preSeconds, postSeconds);
            hindsightLastBufferArmMs = now;
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("Hindsight buffer arm skipped.", t);
        }
    }

    private void trackHindsightSignals(Minecraft client) {
        if (client.player == null || client.level == null || !client.player.isAlive()) {
            return;
        }

        if (client.player.getHealth() <= 6.0f) hindsightLowHealthTicks++;
        if (client.player.getFoodData().getFoodLevel() <= 6) hindsightLowFoodTicks++;
        if (client.player.isOnFire()) hindsightBurningTicks++;
        if (client.player.isInLava()) hindsightLavaTicks++;
        if (client.player.fallDistance > 7.0f) hindsightHighFallTicks++;

        Block current = client.level.getBlockState(client.player.blockPosition()).getBlock();
        Block below = client.level.getBlockState(client.player.blockPosition().below()).getBlock();
        if (current == Blocks.MAGMA_BLOCK || below == Blocks.MAGMA_BLOCK) hindsightMagmaTicks++;
        if (current == Blocks.CACTUS || below == Blocks.CACTUS) hindsightCactusTicks++;

        int pearls = countEnderPearls(client);
        if (hindsightLastPearlCount >= 0 && pearls < hindsightLastPearlCount) {
            hindsightPearlSpends += (hindsightLastPearlCount - pearls);
        }
        hindsightLastPearlCount = pearls;
    }

    private int countEnderPearls(Minecraft client) {
        if (client.player == null) return 0;
        int total = 0;
        try {
            for (int i = 0; i < client.player.getInventory().getContainerSize(); i++) {
                ItemStack stack = client.player.getInventory().getItem(i);
                if (stack.isEmpty()) continue;
                String key = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
                if (key.contains("ender_pearl")) {
                    total += stack.getCount();
                }
            }
        } catch (Throwable ignored) {
        }
        return total;
    }

    private String hindsightSummary() {
        List<String> notes = new ArrayList<>();
        if (hindsightLavaTicks > 8) notes.add("lava contact");
        if (hindsightMagmaTicks > 8) notes.add("magma steps");
        if (hindsightCactusTicks > 8) notes.add("cactus damage");
        if (hindsightHighFallTicks > 3) notes.add("high fall");
        if (hindsightBurningTicks > 12) notes.add("burning damage");
        if (hindsightLowFoodTicks > 20) notes.add("ignored hunger");
        if (hindsightLowHealthTicks > 20) notes.add("stayed low health");
        if (hindsightPearlSpends >= 3) notes.add("overused pearls");
        if (notes.isEmpty()) {
            return "final moments";
        }
        return String.join(", ", notes.subList(0, Math.min(3, notes.size())));
    }

    private void triggerHindsightClip(Minecraft client, RecordableConfig config) {
        String reason = "Hindsight Mode: " + hindsightSummary();
        if (!trySaveEventClipFromBuffer(client, config, reason)) {
            triggerAutoClip(client, config, reason);
        }
    }

    private void checkDeathTrigger(Minecraft client, RecordableConfig config) {
        if (client.player == null) return;

        boolean isAlive = client.player.isAlive();
        if (wasPlayerAlive && !isAlive) {
            if (config.autoClipHindsightEnabled) {
                triggerHindsightClip(client, config);
            } else if (config.autoClipOnDeath) {
                triggerAutoClip(client, config, "Player Death");
            }
            resetHindsightState();
        }
        wasPlayerAlive = isAlive;
    }

    private void checkDimensionChangeTrigger(Minecraft client, RecordableConfig config) {
        if (client.level == null) return;

        ResourceKey<Level> currentDimension = client.level.dimension();
        if (lastDimension != null && !lastDimension.equals(currentDimension)) {
            String dimName = getDimensionDisplayName(currentDimension);
            triggerAutoClip(client, config, "Entered " + dimName);
        }
        lastDimension = currentDimension;
    }

    private void checkAchievementTrigger(Minecraft client, RecordableConfig config) {
        // Client-side advancement tracking: check if the advancement manager has
        // new entries. This is a best-effort heuristic since the client doesn't
        // have direct access to all server-side advancement data.
        try {
            if (client.player != null && client.player.connection != null) {
                // We track by detecting toast notifications for advancements.
                // Unfortunately, Minecraft doesn't expose a clean client-side event for this.
                // We use a simple counter of received advancement packets as heuristic.
                // The mixin approach would be more reliable but adds complexity.
                // For now, we leave this as a placeholder that can be enhanced with a mixin.
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Called externally (e.g., from a mixin) when a boss mob is killed.
     */
    public void onBossKilled(Minecraft client, String bossName) {
        RecordableConfig config;
        try {
            config = RecordableConfig.get();
        } catch (Throwable t) {
            return;
        }

        if (!config.autoClipEnabled) return;
        if (!config.autoClipOnBossKill) return;

        triggerAutoClip(client, config, "Boss Killed: " + bossName);
    }

    /**
     * Called externally (e.g., from a mixin) when an advancement is earned.
     */
    public void onAdvancementEarned(Minecraft client, String advancementTitle) {
        RecordableConfig config;
        try {
            config = RecordableConfig.get();
        } catch (Throwable t) {
            return;
        }

        if (!config.autoClipEnabled) return;
        if (!config.autoClipOnAchievement) return;

        triggerAutoClip(client, config, "Achievement: " + advancementTitle);
    }

    private void triggerAutoClip(Minecraft client, RecordableConfig config, String reason) {
        long now = System.currentTimeMillis();
        if (now - lastAutoClipTriggerMs < AUTO_CLIP_COOLDOWN_MS) {
            RecordableMod.LOGGER.debug("Auto-clip skipped (cooldown): {}", reason);
            return;
        }
        lastAutoClipTriggerMs = now;

        // Check FFmpeg availability
        FFmpegEncoder.FfmpegStatus ffStatus = FFmpegEncoder.detectFfmpeg();
        if (!ffStatus.found()) {
            RecordableMod.LOGGER.warn("Auto-clip skipped (FFmpeg not found): {}", reason);
            return;
        }

        // If a recording is already in progress (started manually by the user, or by the
        // auto-record-on-join feature), do NOT hijack it: leave it running and never schedule
        // a stop. Cutting the user's recording short after an auto-clip event was the bug where
        // earning an achievement stopped an ongoing recording.
        if (RecordingManager.getInstance().isRecording() || RecordingManager.getInstance().isPaused()) {
            boolean savedFromBuffer = trySaveEventClipFromBuffer(client, config, reason);
            RecordableMod.LOGGER.info("Auto-clip event '{}' detected while recording; {}",
                    reason,
                    savedFromBuffer ? "saving buffered event clip" : "leaving active recording untouched");
            if (RecordableMod.shouldNotify(ChatCategory.CLIPS)) {
                if (savedFromBuffer) {
                    ToastQueue.push("§aAuto-clip saved: §f" + reason + " §7(from replay buffer)", 8_000L);
                } else {
                    ToastQueue.push("§aClip-worthy moment: §f" + reason + " §7(already recording)", 8_000L);
                }
            }
            return;
        }

        autoClipReason = reason;
        autoClipStopCountdown = config.autoClipDuration * TICKS_PER_SECOND;
        autoClipOwnsRecording = true;

        RecordableMod.LOGGER.info("Auto-clip triggered: {} (duration: {}s)", reason, config.autoClipDuration);
        if (RecordableMod.shouldNotify(ChatCategory.CLIPS)) {
            ToastQueue.push("§aAuto-clip started: §f" + reason + " §7(" + config.autoClipDuration + "s)", 8_000L);
        }

        // Convert reason to file-safe prefix
        String filePrefix = sanitizeForFilename(reason);
        RecordingManager.getInstance().startRecording(client, filePrefix);
    }

    private boolean trySaveEventClipFromBuffer(Minecraft client, RecordableConfig config, String reason) {
        try {
            int[] dims = resolveMontageDimensions();
            if (dims == null) {
                return false;
            }
            String filePrefix = sanitizeForFilename(reason);
            int preSeconds = Math.max(1, config.autoClipKillPreSeconds);
            int postSeconds = Math.max(1, config.autoClipDuration);
            KillClipBuffer.getInstance().triggerKill(client, reason, filePrefix,
                    dims[0], dims[1], dims[2], preSeconds, postSeconds);
            return true;
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("Failed to save buffered auto-clip for event '{}'", reason, t);
            return false;
        }
    }

    /**
     * Converts a trigger reason into a file-safe prefix.
     * Examples: "Player Death" -> "on-death", "Achievement: Some Title" -> "on-achievement"
     */
    private static String sanitizeForFilename(String reason) {
        if (reason == null || reason.isEmpty()) {
            return "auto-clip";
        }

        String lower = reason.toLowerCase(java.util.Locale.ROOT);

        if (lower.contains("death")) {
            return "on-death";
        } else if (lower.contains("achievement")) {
            return "on-achievement";
        } else if (lower.contains("nether") || lower.contains("end") || lower.contains("overworld") || lower.contains("dimension")) {
            return "on-dimension";
        } else if (lower.contains("boss")) {
            return "on-boss";
        } else if (lower.contains("player") && lower.contains("kill")) {
            return "on-player-kill";
        } else if (lower.contains("kill")) {
            return "on-kill";
        } else if (lower.contains("totem")) {
            return "on-totem-pop";
        } else if (lower.contains("hindsight")) {
            return "on-hindsight-mode";
        } else if (lower.contains("event") || lower.contains("level") || lower.contains("health")
                || lower.contains("weather") || lower.contains("inventory")) {
            return "on-event";
        } else {
            return "auto-clip";
        }
    }

    private void stopAutoClip(Minecraft client) {
        Minecraft activeClient = client != null ? client : Minecraft.getInstance();
        String reason = autoClipReason != null ? autoClipReason : "Auto-clip";
        autoClipReason = null;
        boolean owned = autoClipOwnsRecording;
        autoClipOwnsRecording = false;

        // Only stop the recording if the auto-clip system started it. Never stop a recording the
        // user started manually (or that auto-record-on-join started).
        if (owned && (RecordingManager.getInstance().isRecording() || RecordingManager.getInstance().isPaused())) {
            RecordableMod.LOGGER.info("Auto-clip stopping: {}", reason);
            if (RecordableMod.shouldNotify(ChatCategory.CLIPS)) {
                ToastQueue.push("§eAuto-clip saved: §f" + reason, 8_000L);
            }
            RecordingManager.getInstance().stopRecording(activeClient, RecordingManager.StopReason.AUTO);
        }
    }

    /** Returns true if an auto-clip is currently active (recording with scheduled stop). */
    public boolean isAutoClipActive() {
        return autoClipStopCountdown >= 0;
    }

    /** Returns remaining seconds for the current auto-clip, or -1 if not active. */
    public int getAutoClipRemainingSeconds() {
        int countdown = autoClipStopCountdown;
        return countdown >= 0 ? (countdown / TICKS_PER_SECOND) : -1;
    }

    private static String getDimensionDisplayName(ResourceKey<Level> dimension) {
        if (dimension == null) return "Unknown";
        if (Level.OVERWORLD.equals(dimension)) return "Overworld";
        if (Level.NETHER.equals(dimension)) return "The Nether";
        if (Level.END.equals(dimension)) return "The End";
        return dimension.identifier().getPath();
    }
}
