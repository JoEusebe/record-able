package dev.recordable;

import dev.recordable.screen.FfmpegWelcomeScreen;
import dev.recordable.screen.RecordableSettingsScreen;
import dev.recordable.screen.VideoCollectionScreen;
import dev.recordable.theme.ThemeEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.util.ActionResult;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashSet;
import java.util.Set;

import static dev.recordable.RecordableMod.LOGGER;

/**
 * Client entry point for the Record-able mod - <b>Legacy build (MC 1.20.0-1.20.4)</b>.
 *
 * <p>Uses Yarn mappings and the classic Fabric API surface. The shared logger
 * and MOD_ID are provided by {@link RecordableMod} in the common source set.</p>
 *
 * <h3>KeyBinding registration</h3>
 *
 * <p>This build only ever runs on MC 1.20.0-1.20.4, where the
 * {@code KeyBinding(String, InputUtil.Type, int, String)} constructor (with a
 * {@code String} category) is public and the 1.21.5+ {@code KeyBinding.Category}
 * inner class does not exist. {@code KeyBinding} ({@code class_304}) is therefore
 * referenced directly as a normal type, created with the native String-category
 * constructor, and registered through
 * {@link KeyBindingHelper#registerKeyBinding} so the bindings appear (and are
 * rebindable) in vanilla <i>Options &gt; Controls</i>.</p>
 */
public final class RecordableModInit implements ClientModInitializer {
    private static final Set<Integer> VANILLA_RESERVED_FKEYS = Set.of(
            GLFW.GLFW_KEY_F1, GLFW.GLFW_KEY_F2, GLFW.GLFW_KEY_F3, GLFW.GLFW_KEY_F5
    );
    private static final int[] FKEY_PRIORITY_ORDER = {
            GLFW.GLFW_KEY_F6, GLFW.GLFW_KEY_F7, GLFW.GLFW_KEY_F8,
            GLFW.GLFW_KEY_F9, GLFW.GLFW_KEY_F10, GLFW.GLFW_KEY_F11,
            GLFW.GLFW_KEY_F12, GLFW.GLFW_KEY_F4
    };

    /**
     * The keybind category translation key. On MC 1.20.0-1.20.4 the legacy
     * {@code String}-based {@code KeyBinding} constructor takes this directly.
     */
    private static final String KEY_CATEGORY_STRING = "key.categories.recordable.main";

    // ── Keybinding storage ──────────────────────────────────────────────────
    // Indices: 0=toggle, 1=pause, 2=settings, 3=videoCollection, 4=bookmark
    private static final int KB_TOGGLE   = 0;
    private static final int KB_PAUSE    = 1;
    private static final int KB_SETTINGS = 2;
    private static final int KB_VIDEOS   = 3;
    private static final int KB_BOOKMARK = 4;
    private static final int KB_PTT      = 5;
    private static final int KB_SAVE_REPLAY = 6;
    private static final int KB_TOGGLE_CENSOR = 7;
    private static final int KB_OPEN_CENSOR_EDITOR = 8;
    private static final int KB_CANCEL = 9;
    private static final int KB_RENAME = 10;
    private static final int KB_RECORDING_SETTINGS = 11;
    private static final int KB_PENDING_RENDERS = 12;
    private static final KeyBinding[] keyBindings = new KeyBinding[13];

    private static AutoRecordManager autoRecordManager;
    private static AutoClipManager autoClipManager;
    
    /** Tracks whether we have shown the FFmpeg welcome screen this session. */
    private static boolean ffmpegWelcomeShown = false;
    /** Whether we have already run the one-time crash-recovery check this launch. */
    private static boolean recoveryChecked = false;
    /** Press-edge tracker for the rename key while a menu screen is open (see poll method). */
    private static boolean renameKeyDownLastTick = false;
    /** Press-edge tracker for the settings key while a menu screen is open (see poll method). */
    private static boolean settingsKeyDownLastTick = false;

    @Override
    public void onInitializeClient() {
        // Install platform-specific messaging bridge
        RecordableMod.setMessageSender((client, message, actionBar) ->
                sendClientMessage((MinecraftClient) client, message, actionBar));

        // Replay/Flashback compatibility bridge: drive recording during replay playback.
        ReplayCompatBridge.setRecordingController(new ReplayCompatBridge.RecordingController() {
            @Override
            public boolean isRecording() {
                return RecordingManager.getInstance().isRecording();
            }

            @Override
            public void startRecording(Object client) {
                RecordingManager.getInstance().startRecording((MinecraftClient) client, "replay");
            }

            @Override
            public void stopRecording(Object client) {
                RecordingManager.getInstance().stopRecording((MinecraftClient) client);
            }
        });

        RecordableConfig config = RecordableConfig.load();
        if (config.migrateOldConfig()) {
            config.save();
        }

        try {
            FfmpegBundleManager.runDiagnostics();
        } catch (Exception e) {
            LOGGER.warn("[RecordableMod] FFmpeg diagnostics failed: {}", e.getMessage());
        }

        ThemeEngine.get().loadFromConfig();
        RecordingManager.getInstance().initialize();

        autoRecordManager = new AutoRecordManager();
        autoRecordManager.initialize();

        Set<Integer> claimedKeys = collectClaimedKeyBindings();

        keyBindings[KB_TOGGLE] = registerKeyBinding(createKeyBinding(
                "key.recordable.toggle_recording",
                InputUtil.Type.KEYSYM,
                resolveHotkeyDefault(config.hotkeyToggleRecording,
                        RecordableConfig.DEFAULT_HOTKEY_TOGGLE_RECORDING, claimedKeys)
        ));
        keyBindings[KB_PAUSE] = registerKeyBinding(createKeyBinding(
                "key.recordable.pause_resume",
                InputUtil.Type.KEYSYM,
                resolveHotkeyDefault(config.hotkeyPauseResume,
                        RecordableConfig.DEFAULT_HOTKEY_PAUSE_RESUME, claimedKeys)
        ));
        keyBindings[KB_SETTINGS] = registerKeyBinding(createKeyBinding(
                "key.recordable.open_settings",
                InputUtil.Type.KEYSYM,
                resolveHotkeyDefault(config.hotkeyOpenSettings,
                        RecordableConfig.DEFAULT_HOTKEY_OPEN_SETTINGS, claimedKeys)
        ));
        keyBindings[KB_VIDEOS] = registerKeyBinding(createKeyBinding(
                "key.recordable.open_video_collection",
                InputUtil.Type.KEYSYM,
                resolveHotkeyDefault(config.hotkeyOpenVideoCollection,
                        RecordableConfig.DEFAULT_HOTKEY_OPEN_VIDEO_COLLECTION, claimedKeys)
        ));
        keyBindings[KB_BOOKMARK] = registerKeyBinding(createKeyBinding(
                "key.recordable.add_bookmark",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN
        ));
        keyBindings[KB_PTT] = registerKeyBinding(createKeyBinding(
                "key.recordable.push_to_talk",
                InputUtil.Type.KEYSYM,
                resolveHotkeyDefault(config.hotkeyPushToTalk,
                        RecordableConfig.DEFAULT_HOTKEY_PUSH_TO_TALK, claimedKeys)
        ));
        keyBindings[KB_SAVE_REPLAY] = registerKeyBinding(createKeyBinding(
                "key.recordable.save_replay_buffer",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN
        ));
        keyBindings[KB_TOGGLE_CENSOR] = registerKeyBinding(createKeyBinding(
                "key.recordable.toggle_censor_overlay",
                InputUtil.Type.KEYSYM,
                config.hotkeyToggleCensorOverlay
        ));
        keyBindings[KB_OPEN_CENSOR_EDITOR] = registerKeyBinding(createKeyBinding(
                "key.recordable.open_censor_editor",
                InputUtil.Type.KEYSYM,
                config.hotkeyOpenCensorEditor
        ));
        keyBindings[KB_CANCEL] = registerKeyBinding(createKeyBinding(
                "key.recordable.cancel_recording",
                InputUtil.Type.KEYSYM,
                config.hotkeyCancelRecording
        ));
        keyBindings[KB_RENAME] = registerKeyBinding(createKeyBinding(
                "key.recordable.name_recording",
                InputUtil.Type.KEYSYM,
                config.hotkeyNameRecording
        ));
        keyBindings[KB_RECORDING_SETTINGS] = registerKeyBinding(createKeyBinding(
                "key.recordable.open_recording_settings",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O
        ));
        keyBindings[KB_PENDING_RENDERS] = registerKeyBinding(createKeyBinding(
                "key.recordable.open_pending_renders",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_P
        ));
        // Let RecordingManager show the bound "Name recording" key in its toast.
        RecordingManager.setRenameKeyDisplaySupplier(() -> getBoundKeyDisplay(Hotkey.NAME_RECORDING));

        autoClipManager = new AutoClipManager();
        autoClipManager.initialize();
        RecordableMod.setAutoClipManager(autoClipManager);

        // Kill-trigger workaround: getLastHurtMob() is not available client-side on
        // 1.20.x, so we use Fabric's AttackEntityCallback (fired by Fabric's own
        // client mixin into ClientPlayerInteractionManager#attackEntity) to record
        // the entity the local player attacks. AutoClipManager then watches for its
        // death to fire the On Kill / On Player Kill triggers.
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            try {
                MinecraftClient client = MinecraftClient.getInstance();
                if (autoClipManager != null && client != null && client.player != null
                        && player == client.player) {
                    autoClipManager.onPlayerAttackEntity(entity);
                }
            } catch (Throwable t) {
                LOGGER.debug("Failed to process attack for auto-clip kill tracking.", t);
            }
            return ActionResult.PASS;
        });

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Show FFmpeg welcome screen on first run if FFmpeg is missing
            if (!ffmpegWelcomeShown && client != null && client.currentScreen == null) {
                RecordableConfig cfg = RecordableConfig.get();
                if (cfg != null && !cfg.ffmpegFirstRunShown) {
                    FFmpegEncoder.FfmpegStatus status = FFmpegEncoder.detectFfmpeg();
                    if (!status.found()) {
                        ffmpegWelcomeShown = true;
                        client.setScreen(new FfmpegWelcomeScreen(null));
                        return;
                    } else {
                        // FFmpeg is found, mark as shown so we don't check again
                        cfg.ffmpegFirstRunShown = true;
                        cfg.save();
                        ffmpegWelcomeShown = true;
                    }
                }
            }
            
            // One-time crash-recovery prompt: if a previous session ended without finalizing a
            // recording (crash / hard kill), offer to recover it once we reach the title screen.
            if (!recoveryChecked && client != null
                    && client.currentScreen instanceof net.minecraft.client.gui.screen.TitleScreen) {
                recoveryChecked = true;
                RecoveryManager.checkForPending();
                if (RecoveryManager.hasPending()) {
                    client.setScreen(new RecoveryPromptScreen(client.currentScreen,
                            RecoveryManager.getPendingName()));
                    return;
                }
            }

            if (consumeKeyPresses(KB_TOGGLE, 1) > 0) {
                if (RecordingManager.isInGameState(client)) {
                    if (!RecordingManager.getInstance().isRecording()) {
                        ModCompatibilityChecker.warnPlayerIfConflicts(client);
                        RecordableConfig diskConfig = RecordableConfig.get();
                        DiskSpaceGuardian.DiskCheckResult diskCheck = DiskSpaceGuardian.check(
                                diskConfig.getOutputDirectory(), diskConfig);
                        boolean allowStart = true;
                        if (diskCheck.status() == DiskSpaceGuardian.DiskStatus.BLOCKED) {
                            if (!diskConfig.disableDiskSpaceUsageBlock) {
                                RecordableMod.sendClientMessage(ChatCategory.WARNINGS, client, diskCheck.message(), false);
                                allowStart = false;
                            }
                        } else {
                            if (diskCheck.status() == DiskSpaceGuardian.DiskStatus.WARNING) {
                                RecordableMod.sendClientMessage(ChatCategory.WARNINGS, client, diskCheck.message(), false);
                            }
                        }
                        if (allowStart) {
                            RecordingManager.getInstance().toggleRecording(client);
                        }
                    } else {
                        RecordingManager.getInstance().toggleRecording(client);
                    }
                } else if (!RecordingManager.getInstance().isActiveOrStopping()) {
                    // Pressed the toggle key from a menu / title screen with no world loaded.
                    // Recording can only start inside a world, so tell the player why nothing happened.
                    ToastQueue.push("You can only start recording inside a world.");
                }
            }
            if (consumeKeyPresses(KB_PAUSE, 1) > 0) {
                if (RecordingManager.getInstance().isRecording() || RecordingManager.getInstance().isPaused()) {
                    RecordingManager.getInstance().togglePause(client);
                }
            }
            if (consumeKeyPresses(KB_SETTINGS, 1) > 0) {
                if (client != null && !(client.currentScreen instanceof RecordableSettingsScreen)) {
                    client.setScreen(new RecordableSettingsScreen(client.currentScreen));
                }
            }
            if (consumeKeyPresses(KB_VIDEOS, 1) > 0) {
                if (client != null && !(client.currentScreen instanceof VideoCollectionScreen)) {
                    client.setScreen(new VideoCollectionScreen(client.currentScreen));
                }
            }
            if (consumeKeyPresses(KB_BOOKMARK, 1) > 0) {
                if (RecordableConfig.get().bookmarksEnabled && RecordingManager.getInstance().isRecording()) {
                    String desc = RecordingManager.getInstance().addBookmark();
                    if (desc != null) {
                        long ts = RecordingManager.getInstance().getEffectiveRecordingMillis();
                        RecordableMod.sendClientMessage(ChatCategory.BOOKMARKS, client, "§a🔖 " + desc + " §7at " + RecordingManager.formatDuration(ts), true);
                    }
                }
            }
            if (consumeKeyPresses(KB_SAVE_REPLAY, 1) > 0) {
                RecordableConfig cfg = RecordableConfig.get();
                if (!cfg.replayBufferEnabled) {
                    RecordableMod.sendClientMessage(ChatCategory.REPLAY_BUFFER, client, "\u00a7e\u26a0 Replay Buffer is disabled. Enable it in Record-able settings.", true);
                } else if (!ReplayBuffer.getInstance().isActive()) {
                    RecordableMod.sendClientMessage(ChatCategory.REPLAY_BUFFER, client, "\u00a7c\u26a0 Replay Buffer failed to start (likely GL context issue). Try restarting Minecraft.", true);
                } else {
                    ReplayBuffer.getInstance().saveBuffer(client);
                }
            }
            if (consumeKeyPresses(KB_TOGGLE_CENSOR, 1) > 0) {
                toggleCensorOverlay(client);
            }
            if (consumeKeyPresses(KB_CANCEL, 1) > 0) {
                RecordingManager mgr = RecordingManager.getInstance();
                if (mgr.isRecording() || mgr.isPaused()) {
                    mgr.cancelRecording(client);
                }
            }
            if (consumeKeyPresses(KB_RENAME, 1) > 0) {
                java.nio.file.Path renameTarget = RecordingManager.getInstance().getPendingRenameTarget();
                if (renameTarget != null) {
                    RenameRecordingScreen.openFor(client, renameTarget);
                }
            }
            // The keybinding above only fires in-game. After leaving a world/server the
            // player is on the title/multiplayer screen, so poll the bound key directly.
            pollPendingRenameKeyWhileMenuOpen(client);
            pollOpenSettingsKeyWhileMenuOpen(client);
            if (consumeKeyPresses(KB_OPEN_CENSOR_EDITOR, 1) > 0) {
                openCensorEditor(client);
            }
            if (consumeKeyPresses(KB_RECORDING_SETTINGS, 1) > 0) {
                if (client != null && !(client.currentScreen instanceof RecordingSettingsScreen)) {
                    client.setScreen(new RecordingSettingsScreen(client.currentScreen));
                }
            }
            if (consumeKeyPresses(KB_PENDING_RENDERS, 1) > 0) {
                if (client != null && !(client.currentScreen instanceof PendingRendersScreen)) {
                    client.setScreen(new PendingRendersScreen(client.currentScreen));
                }
            }
            // Feed the Push-to-Talk key's HELD state every tick (edge-insensitive).
            KeyBinding pttKb = keyBindings[KB_PTT];
            MicrophoneState.setPushToTalkHeld(pttKb != null && pttKb.isPressed());

            // A deferred recording just stopped. Only interrupt the player with the render
            // prompt when they opted into it; otherwise render in the background (auto-render)
            // or quietly queue the session so we do not ask "render now?" after every recording.
            String deferredSession = RecordingManager.getInstance().consumePendingDeferredSession();
            if (deferredSession != null) {
                RecordableConfig deferredCfg = RecordableConfig.get();
                if (deferredCfg != null && deferredCfg.deferredShowRenderPrompt) {
                    RenderPromptScreen.openFor(client, deferredSession);
                } else if (deferredCfg != null && deferredCfg.deferredAutoRender) {
                    ToastQueue.push("Recording captured. Rendering in the background...", 8_000L);
                    OfflineRenderer.renderAsync(deferredSession, deferredCfg.getOutputDirectory(),
                            deferredCfg.deferredKeepTempFrames, p -> {})
                        .whenComplete((outPath, err) -> {
                            if (err != null) {
                                ToastQueue.push("Offline render failed: " + err.getMessage()
                                        + " (temp frames kept).", 10_000L);
                            } else {
                                // The video is fully rendered and ready now, so this is the right
                                // moment to confirm the save and offer the rename prompt (instead of
                                // firing it at capture-stop time when the file was not ready yet).
                                RecordableConfig cfgAfter = RecordableConfig.get();
                                RecordingManager rm = RecordingManager.getInstance();
                                if (cfgAfter != null && cfgAfter.promptRenameAfterRecording) {
                                    rm.requestPendingRename(outPath, rm.getRenamePromptWindowMs());
                                    String key = rm.getRenameKeyDisplayOrNull();
                                    String msg = key == null
                                            ? "Render complete: " + outPath.getFileName()
                                                    + " saved! Bind a \"Name recording\" key to rename it."
                                            : "Render complete: " + outPath.getFileName()
                                                    + " saved! Press " + key + " within 15s to name it.";
                                    ToastQueue.push(msg, rm.getRenamePromptWindowMs());
                                } else {
                                    ToastQueue.push("Offline render complete: "
                                            + outPath.getFileName() + " saved!", 8_000L);
                                }
                            }
                        });
                } else {
                    ToastQueue.push("Recording captured. Render it anytime from "
                            + "Settings > Pending Renders.", 9_000L);
                }
            }

            if (autoClipManager != null) autoClipManager.onClientTick(client);
            if (autoRecordManager != null) autoRecordManager.onClientTick(client);
            RecordingManager.getInstance().onClientTick(client);
            ReplayCompatBridge.onClientTick(client, ModCompatibilityChecker.detectReplayPlayback(client));
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
            if (autoRecordManager != null) autoRecordManager.onClientStopping(client);
            ReplayBuffer.getInstance().stop();
            RecordingManager.getInstance().shutdown();
        });

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                RecordingManager rm = RecordingManager.getInstance();
                if (rm.isActiveOrStopping()) {
                    LOGGER.info("JVM shutdown detected while recording. Finalizing...");
                    Thread worker = new Thread(() -> {
                        try { rm.forceShutdown(); } catch (Throwable t) {
                            LOGGER.warn("forceShutdown failed.", t);
                        }
                    }, "Record-able Shutdown Worker");
                    worker.setDaemon(true);
                    worker.start();
                    worker.join(45_000L);
                    if (worker.isAlive()) LOGGER.warn("Recording finalization timed out after 45 s.");
                }
            } catch (Throwable t) {
                LOGGER.warn("Failed to finalize recording during JVM shutdown.", t);
            }
        }, "Record-able Shutdown Hook"));

        RecordingOverlay.register();
        registerPauseMenuButtons();
        PerformanceMetrics.getInstance();

        LOGGER.info("Record-able audio sync offset: {}ms (preset: {})",
                RecordableConfig.get().getEffectiveAudioDelay(), RecordableConfig.get().audioDelayPreset);
        ModCompatibilityChecker.checkAndLog();
        LOGGER.info("Record-able MultiVersion: {}", VersionHelper.getVersionInfo());

        try {
            String freeSpace = DiskSpaceGuardian.getFormattedFreeSpace(config.getOutputDirectory());
            LOGGER.info("Record-able initialized. Recording enabled: {}. Free disk space: {}",
                    config.enabled, freeSpace);
        } catch (Exception e) {
            LOGGER.info("Record-able initialized. Recording enabled: {}", config.enabled);
        }
    }

    // ── Keybinding access ─────────────────────────────────────────────────────

    /**
     * Registers a keybinding through Fabric's {@link KeyBindingHelper} so it is
     * added to {@code GameOptions.allKeys} and shows up (rebindable) in the
     * vanilla <i>Options &gt; Controls</i> screen.
     */
    private static KeyBinding registerKeyBinding(KeyBinding keyBinding) {
        return KeyBindingHelper.registerKeyBinding(keyBinding);
    }

    /** Returns {@code true} if the keybinding at the given index was pressed. */
    private static boolean kbWasPressed(int index) {
        KeyBinding kb = keyBindings[index];
        return kb != null && kb.wasPressed();
    }

    /**
     * Detects the "Name recording" key while a menu screen is open. After the player
     * leaves a world/server they land on the title/multiplayer screen, where game
     * keybindings do NOT accumulate presses (keyboard input is routed to the Screen),
     * so the "press X to name it" prompt shown on leave would otherwise do nothing.
     *
     * <p>We poll the bound key's raw GLFW state and fire once on the press edge, but
     * only while a rename is actually pending (the short window opened on save). Outside
     * that window this is a no-op, so it cannot hijack normal typing in menus.</p>
     */
    private static void pollPendingRenameKeyWhileMenuOpen(MinecraftClient client) {
        if (client == null || client.currentScreen == null
                || client.currentScreen instanceof RenameRecordingScreen) {
            renameKeyDownLastTick = false;
            return;
        }
        RecordingManager mgr = RecordingManager.getInstance();
        if (!mgr.hasPendingRename()) {
            renameKeyDownLastTick = false;
            return;
        }
        boolean down = false;
        KeyBinding kb = keyBindings[KB_RENAME];
        if (kb != null) {
            try {
                InputUtil.Key key = InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey());
                if (key != null && key.getCategory() == InputUtil.Type.KEYSYM
                        && key.getCode() != InputUtil.UNKNOWN_KEY.getCode()) {
                    down = InputUtil.isKeyPressed(client.getWindow().getHandle(), key.getCode());
                }
            } catch (Throwable ignored) {
            }
        }
        if (down && !renameKeyDownLastTick) {
            java.nio.file.Path target = mgr.getPendingRenameTarget();
            if (target != null) {
                RenameRecordingScreen.openFor(client, target);
            }
        }
        renameKeyDownLastTick = down;
    }

    /**
     * Detects the "Open settings" key while a menu screen is open.
     * Game keybindings do not accumulate while menus are focused, so this keeps
     * the default F9 hotkey working from ESC/title-style screens too.
     */
    private static void pollOpenSettingsKeyWhileMenuOpen(MinecraftClient client) {
        if (client == null || client.currentScreen == null
                || client.currentScreen instanceof RecordableSettingsScreen) {
            settingsKeyDownLastTick = false;
            return;
        }
        boolean down = false;
        KeyBinding kb = keyBindings[KB_SETTINGS];
        if (kb != null) {
            try {
                InputUtil.Key key = InputUtil.fromTranslationKey(kb.getBoundKeyTranslationKey());
                if (key != null && key.getCategory() == InputUtil.Type.KEYSYM
                        && key.getCode() != InputUtil.UNKNOWN_KEY.getCode()) {
                    down = InputUtil.isKeyPressed(client.getWindow().getHandle(), key.getCode());
                }
            } catch (Throwable ignored) {
            }
        }
        if (down && !settingsKeyDownLastTick) {
            client.setScreen(new RecordableSettingsScreen(client.currentScreen));
        }
        settingsKeyDownLastTick = down;
    }

    /**
     * Consumes up to {@code max} queued key presses for a hotkey in this tick.
     *
     * <p>During lag spikes, key repeat can enqueue many press events. Processing
     * every queued event in one frame can create long client-tick stalls and
     * accidental double-toggles. This limiter keeps controls responsive by
     * coalescing bursts to a bounded amount of work per tick.</p>
     */
    private static int consumeKeyPresses(int index, int max) {
        if (max <= 0) {
            return 0;
        }
        int consumed = 0;
        while (consumed < max && kbWasPressed(index)) {
            consumed++;
        }
        return consumed;
    }

    // ── Public keybinding accessors (used by UI, e.g. ThemeSettingsScreen) ─────

    /** Stable identifiers for the mod's keybindings, for use by UI code. */
    public enum Hotkey {
        TOGGLE_RECORDING(KB_TOGGLE),
        PAUSE_RESUME(KB_PAUSE),
        OPEN_SETTINGS(KB_SETTINGS),
        OPEN_VIDEOS(KB_VIDEOS),
        ADD_BOOKMARK(KB_BOOKMARK),
        PUSH_TO_TALK(KB_PTT),
        SAVE_REPLAY_BUFFER(KB_SAVE_REPLAY),
        TOGGLE_CENSOR_OVERLAY(KB_TOGGLE_CENSOR),
        OPEN_CENSOR_EDITOR(KB_OPEN_CENSOR_EDITOR),
        CANCEL_RECORDING(KB_CANCEL),
        NAME_RECORDING(KB_RENAME),
        OPEN_RECORDING_SETTINGS(KB_RECORDING_SETTINGS),
        OPEN_PENDING_RENDERS(KB_PENDING_RENDERS);

        private final int index;
        Hotkey(int index) { this.index = index; }
    }

    /**
     * Toggles the live on-screen censor overlay (used when "Bake in Overlay" is
     * OFF). Flips {@link RecordableConfig#censorOverlayHidden}, persists it, and
     * shows an action-bar confirmation. Has no effect on the baked recording.
     */
    private static void toggleCensorOverlay(MinecraftClient client) {
        RecordableConfig config = RecordableConfig.get();
        if (config == null) return;
        config.censorOverlayHidden = !config.censorOverlayHidden;
        config.save();
        String msg = config.censorOverlayHidden
                ? "\u00a7eCensor overlay hidden"
                : "\u00a7aCensor overlay shown";
        RecordableMod.sendClientMessage(ChatCategory.GENERAL, client, msg, true);
    }

    /**
     * Opens the full-screen live Censor Editor overlay, where the player can add,
     * move, stretch and remove their censor bars over the running game.
     */
    private static void openCensorEditor(MinecraftClient client) {
        if (client == null) return;
        if (client.currentScreen instanceof dev.recordable.screen.CensorOverlayEditorScreen) return;
        client.setScreen(new dev.recordable.screen.CensorOverlayEditorScreen(client.currentScreen));
    }

    /**
     * Returns the currently bound key for the given hotkey as display text
     * (e.g. "-", "=", "F6"), reflecting any user rebinding. Returns
     * {@code "Not Bound"} when the action has no key assigned, and {@code "-"}
     * if the binding has not been initialised yet.
     *
     * <p>This reads the live {@link KeyBinding} each call, so callers that
     * invoke it every frame automatically pick up rebinds without extra
     * refresh logic.</p>
     */
    public static String getBoundKeyDisplay(Hotkey hotkey) {
        if (hotkey == null) return "-";
        KeyBinding kb = keyBindings[hotkey.index];
        if (kb == null) return "-";
        if (kb.isUnbound()) return "Not Bound";
        return kb.getBoundKeyLocalizedText().getString();
    }

    // ── KeyBinding creation ────────────────────────────────────────────────────

    /**
     * Creates a {@link KeyBinding} using the native MC 1.20.0-1.20.4
     * {@code KeyBinding(String, InputUtil.Type, int, String)} constructor with
     * the Record-able category string.
     */
    private static KeyBinding createKeyBinding(String translationKey, InputUtil.Type type, int code) {
        return new KeyBinding(translationKey, type, code, KEY_CATEGORY_STRING);
    }

    // ── Smart F-key auto-assignment system ────────────────────────────────────

    /**
     * Collects key codes already claimed by vanilla and other mods so the smart
     * F-key auto-assignment avoids conflicts. Failures here are non-fatal - the
     * worst case is a less-informed default assignment.
     */
    private static Set<Integer> collectClaimedKeyBindings() {
        Set<Integer> claimed = new LinkedHashSet<>(VANILLA_RESERVED_FKEYS);
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null && mc.options != null) {
                for (KeyBinding kb : mc.options.allKeys) {
                    int code = KeyBindingHelper.getBoundKeyOf(kb).getCode();
                    if (code != GLFW.GLFW_KEY_UNKNOWN) claimed.add(code);
                }
            }
        } catch (Throwable t) {
            LOGGER.debug("Could not read existing key bindings for auto-assignment.", t);
        }
        return claimed;
    }

    private static int findAvailableFKey(Set<Integer> claimed) {
        for (int fkey : FKEY_PRIORITY_ORDER) {
            if (!claimed.contains(fkey)) return fkey;
        }
        return GLFW.GLFW_KEY_UNKNOWN;
    }

    private static int resolveHotkeyDefault(int configValue, int compileDefault, Set<Integer> claimed) {
        if (configValue == GLFW.GLFW_KEY_UNKNOWN && compileDefault == GLFW.GLFW_KEY_UNKNOWN)
            return GLFW.GLFW_KEY_UNKNOWN;
        if (configValue != compileDefault) {
            if (configValue != GLFW.GLFW_KEY_UNKNOWN) claimed.add(configValue);
            return configValue;
        }
        if (compileDefault != GLFW.GLFW_KEY_UNKNOWN && !claimed.contains(compileDefault)) {
            claimed.add(compileDefault);
            return compileDefault;
        }
        int assigned = findAvailableFKey(claimed);
        if (assigned != GLFW.GLFW_KEY_UNKNOWN) {
            claimed.add(assigned);
            LOGGER.info("Auto-assigned F-key {} for hotkey (default {} was {})",
                    glfwKeyName(assigned), glfwKeyName(compileDefault),
                    compileDefault == GLFW.GLFW_KEY_UNKNOWN ? "unbound" : "taken");
        }
        return assigned;
    }

    private static String glfwKeyName(int keyCode) {
        if (keyCode == GLFW.GLFW_KEY_UNKNOWN) return "UNBOUND";
        if (keyCode >= GLFW.GLFW_KEY_F1 && keyCode <= GLFW.GLFW_KEY_F12)
            return "F" + (keyCode - GLFW.GLFW_KEY_F1 + 1);
        return "key=" + keyCode;
    }

    /**
     * Adds "Recording Settings" and "Pending Renders" buttons to the pause menu
     * ({@link GameMenuScreen}). The buttons are anchored to the bottom-left corner
     * so they do not overlap the centered vanilla buttons.
     */
    private static void registerPauseMenuButtons() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof GameMenuScreen)) return;
            int btnW = 120;
            int btnH = 20;
            int x = 5;
            int settingsY = scaledHeight - 46;
            int rendersY = scaledHeight - 23;
            Screens.getButtons(screen).add(ButtonWidget.builder(
                    Text.literal("Recording Settings"),
                    b -> client.setScreen(new RecordingSettingsScreen(screen))
            ).dimensions(x, settingsY, btnW, btnH).build());
            Screens.getButtons(screen).add(ButtonWidget.builder(
                    Text.literal("Pending Renders"),
                    b -> client.setScreen(new PendingRendersScreen(screen))
            ).dimensions(x, rendersY, btnW, btnH).build());

            // Small 20x20 mod-logo button that opens the Video Collection, mirroring
            // the title-screen home button. Placed just to the right of the two
            // stacked buttons and vertically centered across both rows.
            int iconSize = 20;
            int iconX = x + btnW + 4;
            int iconY = settingsY + ((rendersY + btnH) - settingsY) / 2 - iconSize / 2;
            Screens.getButtons(screen).add(dev.recordable.screen.HomeButtonWidget.create(screen, iconX, iconY));

            // Recording control buttons in the lower-center area (the spot the
            // player asked for): a Start/Stop toggle and a Pause/Resume toggle.
            // Labels reflect the recorder state at the moment the pause menu is
            // opened. The Pause/Resume button is only active while a recording
            // is running or paused.
            RecordingManager rm = RecordingManager.getInstance();
            boolean recActive = rm.isActiveOrStopping();
            boolean recPaused = rm.isPaused();
            int ctrlW = 120;
            int ctrlH = 20;
            int ctrlGap = 4;
            int rowW = ctrlW * 2 + ctrlGap;
            int ctrlX = scaledWidth / 2 - rowW / 2;
            int ctrlY = scaledHeight - 23;
            Screens.getButtons(screen).add(ButtonWidget.builder(
                    Text.literal(recActive ? "Stop Recording" : "Start Recording"),
                    b -> RecordingManager.getInstance().toggleRecording(client)
            ).dimensions(ctrlX, ctrlY, ctrlW, ctrlH)
             .tooltip(Tooltip.of(Text.literal("Start or stop recording (Record-able)")))
             .build());
            ButtonWidget pauseResumeBtn = ButtonWidget.builder(
                    Text.literal(recPaused ? "Resume" : "Pause"),
                    b -> RecordingManager.getInstance().togglePause(client)
            ).dimensions(ctrlX + ctrlW + ctrlGap, ctrlY, ctrlW, ctrlH)
             .tooltip(Tooltip.of(Text.literal("Pause or resume the current recording (Record-able)")))
             .build();
            pauseResumeBtn.active = recActive;
            Screens.getButtons(screen).add(pauseResumeBtn);
        });
    }

    public static void sendClientMessage(MinecraftClient client, String message, boolean actionBar) {
        if (message == null || message.isBlank()) return;
        // V1-0.09: all Record-able notifications are shown as custom themed toasts
        // (logo badge + gray panel + yellow text) instead of vanilla chat / action-bar
        // messages. The toast queue is version-agnostic and rendered by RecordingOverlay.
        try {
            ToastQueue.push(message);
        } catch (Throwable t) {
            LOGGER.warn("Failed to enqueue Record-able toast: {}", message, t);
        }
    }
}
