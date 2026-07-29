package dev.recordable;

import net.minecraft.client.MinecraftClient;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKey;
import net.minecraft.world.World;
import java.util.ArrayList;
import java.util.List;

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
    private volatile RegistryKey<World> lastDimension = null;

    /** Track player health for death detection (client-side). */
    private volatile boolean wasPlayerAlive = true;

    /** Track player's last known totem-pop health state for totem-pop detection. */
    private volatile boolean wasPlayerNearDeath = false;
    /** Tracks totem-related effects to detect new pop events. */
    private volatile boolean hadTotemEffectsLastTick = false;

    /** Window (in ticks) after attacking an entity during which its death counts as a kill. */
    private static final int KILL_TRACK_WINDOW_TICKS = 200; // ~10s
    /**
     * The entity the player most recently attacked, fed by AttackEntityMixin
     * (since {@code getLastHurtMob()} is not available client-side in 1.21.x).
     */
    private java.lang.ref.WeakReference<net.minecraft.entity.LivingEntity> killTrackTarget = null;
    /** Whether the tracked target is another player (for PvP detection). */
    private boolean killTrackTargetIsPlayer = false;
    /** Remaining ticks before the tracked target is forgotten. */
    private int killTrackDeadlineTicks = -1;
    /** The last target a kill clip already fired for, to avoid duplicate triggers. */
    private java.lang.ref.WeakReference<net.minecraft.entity.LivingEntity> killLastProcessed = null;

    /**
     * Victim of a confirmed player-owned projectile hit, published by
     * {@code ProjectileEntityMixin} (event-based) and consumed on the next client tick.
     * Stored as a volatile WeakReference so the mixin - which may run on the integrated
     * server thread - can hand the entity off without mutating the tracking fields (which
     * are only touched from the client tick thread). Replaces the old unreliable per-tick
     * spatial overlap scan.
     */
    private volatile java.lang.ref.WeakReference<net.minecraft.entity.LivingEntity> pendingProjectileVictim = null;

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
     * LivingEntity subclasses like armor stands, etc.
     */
    private static boolean isActualMob(net.minecraft.entity.LivingEntity entity) {
        if (entity instanceof net.minecraft.entity.player.PlayerEntity) return true;
        return entity instanceof net.minecraft.entity.mob.MobEntity;
    }

    /** Safe display name for logging, never throws. */
    private static String nameOf(net.minecraft.entity.LivingEntity e) {
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
    private void beginTracking(net.minecraft.entity.LivingEntity living, String how) {
        // Filter out non-mob entities (armor stands, marker entities, etc.)
        if (!isActualMob(living)) {
            RecordableMod.LOGGER.debug("[AutoClip] Ignoring non-mob entity for kill detection ({}): {}", how, nameOf(living));
            return;
        }
        killTrackTarget = new java.lang.ref.WeakReference<>(living);
        killTrackTargetIsPlayer = living instanceof net.minecraft.entity.player.PlayerEntity;
        killTrackDeadlineTicks = KILL_TRACK_WINDOW_TICKS;
        RecordableMod.LOGGER.debug("[AutoClip] Tracking entity for kill detection ({}): {}", how, nameOf(living));
        armKillMontageIfEnabled();
    }

    /** Updates {@link #ticksSincePlayerSwing} once per tick from the player's arm-swing state. */
    private void updatePlayerSwingTracker(MinecraftClient client) {
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
     *     <li>Normal melee: the vanilla arm-swing animation ({@code handSwinging}).</li>
     *     <li>Charging melee weapons (spears/lances): these custom weapons frequently do NOT
     *     fire the vanilla arm-swing. Instead they charge while the attack (or use) key is
     *     held and strike on release. Treating a held attack/use key as an active combat
     *     action lets the spear/lance kill be attributed to the player even without a swing.</li>
     * </ul>
     */
    private static boolean isPlayerCombatActive(MinecraftClient client) {
        if (client.player != null && client.player.handSwinging) return true;
        try {
            if (client.options != null) {
                if (client.options.attackKey != null && client.options.attackKey.isPressed()) return true;
                if (client.options.useKey != null && client.options.useKey.isPressed()) return true;
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
    private net.minecraft.entity.LivingEntity findAimedDamagedEntity(MinecraftClient client,
            net.minecraft.entity.LivingEntity processed) {
        net.minecraft.entity.player.PlayerEntity player = client.player;
        net.minecraft.util.math.Vec3d eye = player.getEyePos();
        net.minecraft.util.math.Vec3d look = player.getRotationVec(1.0f);
        net.minecraft.util.math.Vec3d end = eye.add(look.multiply(FALLBACK_REACH));
        net.minecraft.util.math.Box searchBox =
                player.getBoundingBox().stretch(look.multiply(FALLBACK_REACH)).expand(1.0);

        net.minecraft.entity.LivingEntity best = null;
        double bestDist = Double.MAX_VALUE;
        for (net.minecraft.entity.Entity e : client.world.getOtherEntities(player, searchBox)) {
            if (!(e instanceof net.minecraft.entity.LivingEntity living)) continue;
            if (living == processed) continue;
            if (living.isRemoved()) continue;
            // Must be actively taking damage (hurtTime > 0) or already dying.
            if (living.hurtTime <= 0 && living.getHealth() > 0.0f) continue;
            // Precise aim check: the player's view ray must intersect the entity's hitbox.
            java.util.Optional<net.minecraft.util.math.Vec3d> hit =
                    living.getBoundingBox().expand(0.3).raycast(eye, end);
            if (hit.isEmpty()) continue;
            double d = eye.squaredDistanceTo(hit.get());
            if (d < bestDist) {
                bestDist = d;
                best = living;
            }
        }
        return best;
    }

    /** Convenience accessor for the local player (used only for self-attack guards). */
    private static net.minecraft.entity.LivingEntity localPlayer() {
        MinecraftClient mc = MinecraftClient.getInstance();
        return mc != null ? mc.player : null;
    }

    /**
     * Called by {@code AttackEntityMixin} whenever the local player attacks an entity.
     * Records the target so that a subsequent death of that entity can be detected as
     * a "Kill" / "Player Kill" trigger. This is the client-side workaround for older
     * Minecraft versions that lack {@code PlayerEntity.getLastHurtMob()}.
     */
    public void onPlayerAttackEntity(net.minecraft.entity.Entity target) {
        if (!(target instanceof net.minecraft.entity.LivingEntity living)) return;
        if (living == localPlayer() || living.isRemoved()) return;
        net.minecraft.entity.LivingEntity processed = killLastProcessed != null ? killLastProcessed.get() : null;
        if (living == processed) return; // already fired for this entity
        // This callback ONLY fires for the local player's own melee attacks, so attribution
        // here is guaranteed: the local player is the attacker.
        beginTracking(living, "direct melee attack");
    }

    /**
     * If kill-montage capture is enabled, begin (or refresh) the rolling pre-roll buffer so a
     * subsequent kill can include the second(s) before the finishing blow. No-op otherwise.
     */
    private void armKillMontageIfEnabled() {
        try {
            RecordableConfig config = RecordableConfig.get();
            if (!config.autoClipEnabled) return;
            if (!(config.autoClipOnKill || config.autoClipOnPlayerKill)) return;
            if (!config.autoClipKillMontage) return;
            int[] dims = resolveMontageDimensions();
            if (dims == null) return;
            KillClipBuffer.getInstance().arm(MinecraftClient.getInstance(), dims[0], dims[1], dims[2],
                    config.autoClipKillPreSeconds, config.autoClipKillPostSeconds);
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("Kill-montage arm skipped.", t);
        }
    }

    /** Returns {width, height, fps} for montage capture, or null if the window is unavailable. */
    private static int[] resolveMontageDimensions() {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.getWindow() == null) return null;
            int nativeWidth = client.getWindow().getFramebufferWidth();
            int nativeHeight = client.getWindow().getFramebufferHeight();
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
    private void fireKill(MinecraftClient client, RecordableConfig config, String reason) {
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
     * Called every client tick to check for trigger events and manage auto-clip countdown.
     */
    public void onClientTick(MinecraftClient client) {
        if (client == null || client.player == null || client.world == null) {
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
            consumePendingProjectileVictim();
            scanForRecentlyDamagedEntities(client);
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
     * Detects when an entity the local player recently attacked (recorded via
     * {@code onPlayerAttackEntity}) has died, and fires the appropriate auto-clip
     * ("Player Kill" for PvP, otherwise "Kill: &lt;mob&gt;").
     */
    private void checkKillTrigger(MinecraftClient client, RecordableConfig config) {
        net.minecraft.entity.LivingEntity tracked = killTrackTarget != null ? killTrackTarget.get() : null;
        if (tracked == null) return;

        float health = tracked.getHealth();
        // A real kill is confirmed only when the entity's health has actually dropped to zero
        // (or it is in its death state). This distinguishes a true death from a despawn / chunk
        // unload, which would otherwise make isAlive() return false and cause a false positive.
        boolean died = health <= 0.0f || tracked.isDead();
        boolean removedWhileAlive = tracked.isRemoved() && health > 0.0f;

        if (died) {
            boolean isPlayer = killTrackTargetIsPlayer;
            killLastProcessed = new java.lang.ref.WeakReference<>(tracked); // dedupe future ticks
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
     * Fallback melee/attack detection for weapons (spears, modded items) whose custom attack
     * mechanics bypass Fabric's AttackEntityCallback. Scans for any living entity within
     * attack range that is actively taking damage, and begins tracking it for a kill clip.
     */
    private void scanForRecentlyDamagedEntities(MinecraftClient client) {
        try {
            if (client.player == null || client.world == null) return;
            // Keep an already-tracked, still-alive target instead of re-acquiring.
            net.minecraft.entity.LivingEntity current = killTrackTarget != null ? killTrackTarget.get() : null;
            if (current != null && current.isAlive()) return;

            // ATTRIBUTION GATE: only attribute a melee/spear kill to the local player when the
            // player actually swung their arm recently. Without this, an entity being damaged by
            // ANOTHER mob or player nearby would be falsely credited to us (the main false-positive
            // and mis-attribution source in the old radius-only scan).
            if (ticksSincePlayerSwing > SWING_GRACE_TICKS) return;

            net.minecraft.entity.LivingEntity processed = killLastProcessed != null ? killLastProcessed.get() : null;
            // Require the damaged entity to be the one the player is actually AIMING at (precise
            // look-ray intersection), not merely within radius. Covers extended-reach spears.
            net.minecraft.entity.LivingEntity living = findAimedDamagedEntity(client, processed);
            if (living == null) return;

            beginTracking(living, "melee/spear fallback (recent swing + aim)");
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("Recent damage scan failed.", t);
        }
    }

    /**
     * Event-based projectile-kill detection. Called by {@code ProjectileEntityMixin} at the
     * exact moment a projectile strikes an entity (an injection into
     * {@code ProjectileEntity.onEntityHit}). This is guaranteed single-tick capture: it fires
     * before the trident bounces away (Loyalty), embeds in the ground, or the fast-moving
     * arrow/trident disappears from the client entity list - the failure modes that made the
     * old per-tick spatial overlap scan unreliable for ranged kills.
     *
     * <p>This method may be invoked from the integrated-server thread, so it must NOT touch
     * the kill-tracking fields directly. It only validates attribution (the projectile must be
     * owned by the local player, matched by UUID rather than reference identity) and publishes
     * the victim into {@link #pendingProjectileVictim} for the client tick thread to consume.</p>
     *
     * @param owner  the projectile's owner (from {@code ProjectileEntity.getOwner()})
     * @param victim the entity the projectile hit (from {@code EntityHitResult.getEntity()})
     */
    public void onProjectileHitEntity(net.minecraft.entity.Entity owner, net.minecraft.entity.Entity victim) {
        try {
            if (owner == null) return;
            if (!(victim instanceof net.minecraft.entity.LivingEntity living)) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null || mc.player == null) return;
            // ATTRIBUTION: only projectiles owned by the local player count. Compare by UUID
            // (not reference identity) so it stays correct across client / integrated-server
            // entity instances and after world transitions or entity reloads.
            if (!owner.getUuid().equals(mc.player.getUuid())) return;
            if (living == mc.player || living.isRemoved()) return;
            pendingProjectileVictim = new java.lang.ref.WeakReference<>(living);
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("Projectile-hit event handling failed.", t);
        }
    }

    /**
     * Consumes a victim published by {@link #onProjectileHitEntity} on the client tick thread
     * and begins kill-tracking it, so its imminent death fires the kill clip. Runs the same
     * de-duplication / already-tracking guards as the melee path.
     */
    private void consumePendingProjectileVictim() {
        java.lang.ref.WeakReference<net.minecraft.entity.LivingEntity> ref = pendingProjectileVictim;
        if (ref == null) return;
        pendingProjectileVictim = null; // consume once
        net.minecraft.entity.LivingEntity living = ref.get();
        if (living == null || living.isRemoved()) return;
        // Keep an already-tracked, still-alive target instead of re-acquiring.
        net.minecraft.entity.LivingEntity current = killTrackTarget != null ? killTrackTarget.get() : null;
        if (current != null && current.isAlive()) return;
        net.minecraft.entity.LivingEntity processed = killLastProcessed != null ? killLastProcessed.get() : null;
        if (living == processed) return; // already fired for this entity
        beginTracking(living, "player-owned projectile hit (event)");
    }

    /**
     * Detects when the local player's totem of undying pops (saved from lethal damage).
     */
    private void checkTotemPopTrigger(MinecraftClient client, RecordableConfig config) {
        if (client.player == null) {
            wasPlayerNearDeath = false;
            hadTotemEffectsLastTick = false;
            return;
        }
        float health = client.player.getHealth();
        boolean nearDeath = health <= 1.0f;
        boolean hasTotemEffects = false;
        try {
            hasTotemEffects = client.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.REGENERATION)
                    || client.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.ABSORPTION)
                    || client.player.hasStatusEffect(net.minecraft.entity.effect.StatusEffects.FIRE_RESISTANCE);
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
    public void onTotemPopObserved(MinecraftClient client) {
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
     * Called externally to trigger an auto-clip for a custom event.
     */
    public void onCustomEvent(MinecraftClient client, String eventName) {
        RecordableConfig config;
        try { config = RecordableConfig.get(); } catch (Throwable t) { return; }
        if (!config.autoClipEnabled) return;
        if (!config.autoClipOnCustomEvent) return;
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

    private void checkCustomEventTriggers(MinecraftClient client, RecordableConfig config) {
        if (client.player == null || client.world == null) return;
        if (!config.autoClipOnCustomEvent) return;
        if (config.customEventTriggers.isEmpty()) return;
        for (String trigger : config.customEventTriggers) {
            if (trigger == null) continue;
            String t = trigger.toLowerCase(java.util.Locale.ROOT).trim();
            switch (t) {
                case "low_health" -> {
                    float healthPercent = client.player.getHealth() / client.player.getMaxHealth();
                    boolean isLow = healthPercent <= 0.2f && healthPercent > 0.0f;
                    if (isLow && !wasLowHealth) triggerAutoClip(client, config, "Low Health");
                    wasLowHealth = isLow;
                }
                case "xp_level_up" -> {
                    int currentLevel = client.player.experienceLevel;
                    if (lastXpLevel >= 0 && currentLevel > lastXpLevel)
                        triggerAutoClip(client, config, "Level Up (" + currentLevel + ")");
                    lastXpLevel = currentLevel;
                }
                case "weather_change" -> {
                    boolean raining = client.world.isRaining();
                    boolean thundering = client.world.isThundering();
                    if (raining != wasRaining || thundering != wasThundering) {
                        if (wasRaining || wasThundering) {
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
                            if (client.player.getInventory().getStack(i).isEmpty()) { full = false; break; }
                        }
                    } catch (Throwable ignored) { full = false; }
                    if (full && !wasInventoryFull) triggerAutoClip(client, config, "Inventory Full");
                    wasInventoryFull = full;
                }
                default -> {}
            }
        }
    }

    private void updateTrackedState(MinecraftClient client) {
        if (client.player != null) {
            wasPlayerAlive = client.player.isAlive();
            lastDimension = client.world != null ? client.world.getRegistryKey() : null;
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

    private void maintainHindsightBuffer(MinecraftClient client, RecordableConfig config) {
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

    private void trackHindsightSignals(MinecraftClient client) {
        if (client.player == null || client.world == null || !client.player.isAlive()) {
            return;
        }

        if (client.player.getHealth() <= 6.0f) hindsightLowHealthTicks++;
        if (client.player.getHungerManager().getFoodLevel() <= 6) hindsightLowFoodTicks++;
        if (client.player.isOnFire()) hindsightBurningTicks++;
        if (client.player.isInLava()) hindsightLavaTicks++;
        if (client.player.fallDistance > 7.0f) hindsightHighFallTicks++;

        Block current = client.world.getBlockState(client.player.getBlockPos()).getBlock();
        Block below = client.world.getBlockState(client.player.getBlockPos().down()).getBlock();
        if (current == Blocks.MAGMA_BLOCK || below == Blocks.MAGMA_BLOCK) hindsightMagmaTicks++;
        if (current == Blocks.CACTUS || below == Blocks.CACTUS) hindsightCactusTicks++;

        int pearls = countEnderPearls(client);
        if (hindsightLastPearlCount >= 0 && pearls < hindsightLastPearlCount) {
            hindsightPearlSpends += (hindsightLastPearlCount - pearls);
        }
        hindsightLastPearlCount = pearls;
    }

    private int countEnderPearls(MinecraftClient client) {
        if (client.player == null) return 0;
        int total = 0;
        try {
            for (int i = 0; i < client.player.getInventory().size(); i++) {
                ItemStack stack = client.player.getInventory().getStack(i);
                if (stack.isEmpty()) continue;
                String key = Registries.ITEM.getId(stack.getItem()).toString();
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

    private void triggerHindsightClip(MinecraftClient client, RecordableConfig config) {
        String reason = "Hindsight Mode: " + hindsightSummary();
        if (!trySaveEventClipFromBuffer(client, config, reason)) {
            triggerAutoClip(client, config, reason);
        }
    }

    private void checkDeathTrigger(MinecraftClient client, RecordableConfig config) {
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

    private void checkDimensionChangeTrigger(MinecraftClient client, RecordableConfig config) {
        if (client.world == null) return;

        RegistryKey<World> currentDimension = client.world.getRegistryKey();
        if (lastDimension != null && !lastDimension.equals(currentDimension)) {
            String dimName = getDimensionDisplayName(currentDimension);
            triggerAutoClip(client, config, "Entered " + dimName);
        }
        lastDimension = currentDimension;
    }

    private void checkAchievementTrigger(MinecraftClient client, RecordableConfig config) {
        // Client-side advancement tracking: check if the advancement manager has
        // new entries. This is a best-effort heuristic since the client doesn't
        // have direct access to all server-side advancement data.
        try {
            if (client.player != null && client.player.networkHandler != null) {
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
    public void onBossKilled(MinecraftClient client, String bossName) {
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
    public void onAdvancementEarned(MinecraftClient client, String advancementTitle) {
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

    private void triggerAutoClip(MinecraftClient client, RecordableConfig config, String reason) {
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

    private boolean trySaveEventClipFromBuffer(MinecraftClient client, RecordableConfig config, String reason) {
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

    private void stopAutoClip(MinecraftClient client) {
        MinecraftClient activeClient = client != null ? client : MinecraftClient.getInstance();
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

    private static String getDimensionDisplayName(RegistryKey<World> dimension) {
        if (dimension == null) return "Unknown";
        if (World.OVERWORLD.equals(dimension)) return "Overworld";
        if (World.NETHER.equals(dimension)) return "The Nether";
        if (World.END.equals(dimension)) return "The End";
        return dimension.getValue().getPath();
    }
}
