package dev.recordable;

import dev.recordable.screen.FfmpegWelcomeScreen;
import dev.recordable.screen.RecordableSettingsScreen;
import dev.recordable.screen.VideoCollectionScreen;
import dev.recordable.theme.ThemeEngine;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.PauseScreen;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

import java.util.LinkedHashSet;
import java.util.Set;

import static dev.recordable.RecordableMod.LOGGER;

/**
 * Client entry point for the Record-able mod - <b>Modern (26.x+)</b>.
 *
 * <p>Uses Mojang official mappings (unobfuscated). Minecraft class names
 * follow Mojang's naming convention directly.</p>
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

    private static KeyMapping toggleRecordingKey;
    private static KeyMapping pauseResumeKey;
    private static KeyMapping openSettingsKey;
    private static KeyMapping openVideoCollectionKey;
    private static KeyMapping addBookmarkKey;
    private static KeyMapping pushToTalkKey;
    private static KeyMapping saveReplayBufferKey;
    private static KeyMapping toggleCensorOverlayKey;
    private static KeyMapping openCensorEditorKey;
    private static KeyMapping cancelRecordingKey;
    private static KeyMapping nameRecordingKey;
    private static KeyMapping openRecordingSettingsKey;
    private static KeyMapping openPendingRendersKey;
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
                sendClientMessage((Minecraft) client, message, actionBar));

        // Replay/Flashback compatibility bridge: drive recording during replay playback.
        ReplayCompatBridge.setRecordingController(new ReplayCompatBridge.RecordingController() {
            @Override
            public boolean isRecording() {
                return RecordingManager.getInstance().isRecording();
            }

            @Override
            public void startRecording(Object client) {
                RecordingManager.getInstance().startRecording((Minecraft) client, "replay");
            }

            @Override
            public void stopRecording(Object client) {
                RecordingManager.getInstance().stopRecording((Minecraft) client);
            }
        });

        RecordableConfig config = RecordableConfig.load();
        if (config.migrateOldConfig()) config.save();

        try { FfmpegBundleManager.runDiagnostics(); }
        catch (Exception e) { LOGGER.warn("[RecordableMod] FFmpeg diagnostics failed: {}", e.getMessage()); }

        ThemeEngine.get().loadFromConfig();
        RecordingManager.getInstance().initialize();

        autoRecordManager = new AutoRecordManager();
        autoRecordManager.initialize();

        Set<Integer> claimedKeys = collectClaimedKeyBindings();
        KeyMapping.Category category = KeyMapping.Category.register(net.minecraft.resources.Identifier.fromNamespaceAndPath(RecordableMod.MOD_ID, "main"));

        toggleRecordingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.recordable.toggle_recording",
                InputConstants.Type.KEYSYM,
                resolveHotkeyDefault(config.hotkeyToggleRecording,
                        RecordableConfig.DEFAULT_HOTKEY_TOGGLE_RECORDING, claimedKeys),
                category
        ));
        pauseResumeKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.recordable.pause_resume",
                InputConstants.Type.KEYSYM,
                resolveHotkeyDefault(config.hotkeyPauseResume,
                        RecordableConfig.DEFAULT_HOTKEY_PAUSE_RESUME, claimedKeys),
                category
        ));
        openSettingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.recordable.open_settings",
                InputConstants.Type.KEYSYM,
                resolveHotkeyDefault(config.hotkeyOpenSettings,
                        RecordableConfig.DEFAULT_HOTKEY_OPEN_SETTINGS, claimedKeys),
                category
        ));
        openVideoCollectionKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.recordable.open_video_collection",
                InputConstants.Type.KEYSYM,
                resolveHotkeyDefault(config.hotkeyOpenVideoCollection,
                        RecordableConfig.DEFAULT_HOTKEY_OPEN_VIDEO_COLLECTION, claimedKeys),
                category
        ));
        addBookmarkKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.recordable.add_bookmark",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                category
        ));
        pushToTalkKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.recordable.push_to_talk",
                InputConstants.Type.KEYSYM,
                resolveHotkeyDefault(config.hotkeyPushToTalk,
                        RecordableConfig.DEFAULT_HOTKEY_PUSH_TO_TALK, claimedKeys),
                category
        ));
        saveReplayBufferKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.recordable.save_replay_buffer",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_UNKNOWN,
                category
        ));
        toggleCensorOverlayKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.recordable.toggle_censor_overlay",
                InputConstants.Type.KEYSYM,
                config.hotkeyToggleCensorOverlay,
                category
        ));
        openCensorEditorKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.recordable.open_censor_editor",
                InputConstants.Type.KEYSYM,
                config.hotkeyOpenCensorEditor,
                category
        ));
        cancelRecordingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.recordable.cancel_recording",
                InputConstants.Type.KEYSYM,
                config.hotkeyCancelRecording,
                category
        ));
        nameRecordingKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.recordable.name_recording",
                InputConstants.Type.KEYSYM,
                config.hotkeyNameRecording,
                category
        ));
        openRecordingSettingsKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.recordable.open_recording_settings",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                category
        ));
        openPendingRendersKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
                "key.recordable.open_pending_renders",
                InputConstants.Type.KEYSYM,
                GLFW.GLFW_KEY_P,
                category
        ));
        // Let RecordingManager show the bound "Name recording" key in its toast.
        RecordingManager.setRenameKeyDisplaySupplier(() -> getBoundKeyDisplay(Hotkey.NAME_RECORDING));

        autoClipManager = new AutoClipManager();
        autoClipManager.initialize();
        RecordableMod.setAutoClipManager(autoClipManager);

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Show FFmpeg welcome screen on first run if FFmpeg is missing
            if (!ffmpegWelcomeShown && client != null && VersionHelper.currentScreen(client) == null) {
                RecordableConfig cfg = RecordableConfig.get();
                if (cfg != null && !cfg.ffmpegFirstRunShown) {
                    FFmpegEncoder.FfmpegStatus status = FFmpegEncoder.detectFfmpeg();
                    if (!status.found()) {
                        ffmpegWelcomeShown = true;
                        client.setScreenAndShow(new FfmpegWelcomeScreen(null));
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
                    && VersionHelper.currentScreen(client) instanceof net.minecraft.client.gui.screens.TitleScreen) {
                recoveryChecked = true;
                RecoveryManager.checkForPending();
                if (RecoveryManager.hasPending()) {
                    client.setScreenAndShow(new RecoveryPromptScreen(VersionHelper.currentScreen(client),
                            RecoveryManager.getPendingName()));
                    return;
                }
            }

            while (toggleRecordingKey.consumeClick()) {
                if (!RecordingManager.isInGameState(client)) {
                    // Pressed the toggle key from a menu / title screen with no world loaded.
                    // Recording can only start inside a world, so tell the player why nothing happened.
                    if (!RecordingManager.getInstance().isActiveOrStopping()) {
                        ToastQueue.push("You can only start recording inside a world.");
                    }
                    continue;
                }
                if (!RecordingManager.getInstance().isRecording()) {
                    ModCompatibilityChecker.warnPlayerIfConflicts(client);
                    RecordableConfig diskConfig = RecordableConfig.get();
                    DiskSpaceGuardian.DiskCheckResult diskCheck = DiskSpaceGuardian.check(
                            diskConfig.getOutputDirectory(), diskConfig);
                    if (diskCheck.status() == DiskSpaceGuardian.DiskStatus.BLOCKED) {
                        if (!diskConfig.disableDiskSpaceUsageBlock) {
                            RecordableMod.sendClientMessage(ChatCategory.WARNINGS, client, diskCheck.message(), false);
                            continue;
                        }
                    }
                    if (diskCheck.status() == DiskSpaceGuardian.DiskStatus.WARNING)
                        RecordableMod.sendClientMessage(ChatCategory.WARNINGS, client, diskCheck.message(), false);
                }
                RecordingManager.getInstance().toggleRecording(client);
            }
            while (pauseResumeKey.consumeClick()) {
                if (RecordingManager.getInstance().isRecording() || RecordingManager.getInstance().isPaused())
                    RecordingManager.getInstance().togglePause(client);
            }
            while (openSettingsKey.consumeClick()) {
                if (client != null && !(VersionHelper.currentScreen(client) instanceof RecordableSettingsScreen))
                    client.setScreenAndShow(new RecordableSettingsScreen(VersionHelper.currentScreen(client)));
            }
            while (openVideoCollectionKey.consumeClick()) {
                if (client != null && !(VersionHelper.currentScreen(client) instanceof VideoCollectionScreen))
                    client.setScreenAndShow(new VideoCollectionScreen(VersionHelper.currentScreen(client)));
            }
            while (addBookmarkKey.consumeClick()) {
                if (RecordableConfig.get().bookmarksEnabled && RecordingManager.getInstance().isRecording()) {
                    String desc = RecordingManager.getInstance().addBookmark();
                    if (desc != null) {
                        long ts = RecordingManager.getInstance().getEffectiveRecordingMillis();
                        RecordableMod.sendClientMessage(ChatCategory.BOOKMARKS, client, "§a🔖 " + desc + " §7at " + RecordingManager.formatDuration(ts), true);
                    }
                }
            }
            while (saveReplayBufferKey.consumeClick()) {
                RecordableConfig cfg = RecordableConfig.get();
                if (!cfg.replayBufferEnabled) {
                    RecordableMod.sendClientMessage(ChatCategory.REPLAY_BUFFER, client, "\u00a7e\u26a0 Replay Buffer is disabled. Enable it in Record-able settings.", true);
                } else if (!ReplayBuffer.getInstance().isActive()) {
                    RecordableMod.sendClientMessage(ChatCategory.REPLAY_BUFFER, client, "\u00a7c\u26a0 Replay Buffer failed to start (likely GL context issue). Try restarting Minecraft.", true);
                } else {
                    ReplayBuffer.getInstance().saveBuffer(client);
                }
            }
            while (toggleCensorOverlayKey.consumeClick()) {
                toggleCensorOverlay(client);
            }
            while (openCensorEditorKey.consumeClick()) {
                openCensorEditor(client);
            }
            while (cancelRecordingKey.consumeClick()) {
                RecordingManager mgr = RecordingManager.getInstance();
                if (mgr.isRecording() || mgr.isPaused()) {
                    mgr.cancelRecording(client);
                }
            }
            while (nameRecordingKey.consumeClick()) {
                java.nio.file.Path renameTarget = RecordingManager.getInstance().getPendingRenameTarget();
                if (renameTarget != null) {
                    RenameRecordingScreen.openFor(client, renameTarget);
                }
            }
            // The keybinding above only fires in-game. After leaving a world/server the
            // player is on the title/multiplayer screen, so poll the bound key directly.
            pollPendingRenameKeyWhileMenuOpen(client);
            pollOpenSettingsKeyWhileMenuOpen(client);
            while (openRecordingSettingsKey.consumeClick()) {
                if (client != null && !(VersionHelper.currentScreen(client) instanceof RecordingSettingsScreen))
                    client.setScreenAndShow(new RecordingSettingsScreen(VersionHelper.currentScreen(client)));
            }
            while (openPendingRendersKey.consumeClick()) {
                if (client != null && !(VersionHelper.currentScreen(client) instanceof PendingRendersScreen))
                    client.setScreenAndShow(new PendingRendersScreen(VersionHelper.currentScreen(client)));
            }
            // Feed the Push-to-Talk key's HELD state every tick (edge-insensitive).
            MicrophoneState.setPushToTalkHeld(pushToTalkKey != null && pushToTalkKey.isDown());

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
                    LOGGER.info("JVM shutdown detected while recording. Finalizing…");
                    Thread worker = new Thread(() -> {
                        try { rm.forceShutdown(); } catch (Throwable t) { LOGGER.warn("forceShutdown failed.", t); }
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
        LOGGER.info("Record-able (Modern 26.x): {}", VersionHelper.getVersionInfo());

        try {
            String freeSpace = DiskSpaceGuardian.getFormattedFreeSpace(config.getOutputDirectory());
            LOGGER.info("Record-able initialized. Recording enabled: {}. Free disk space: {}",
                    config.enabled, freeSpace);
        } catch (Exception e) {
            LOGGER.info("Record-able initialized. Recording enabled: {}", config.enabled);
        }
    }

    // ── Public keybinding accessors (used by UI, e.g. ThemeSettingsScreen) ─────

    /** Stable identifiers for the mod's keybindings, for use by UI code. */
    public enum Hotkey {
        TOGGLE_RECORDING,
        PAUSE_RESUME,
        OPEN_SETTINGS,
        OPEN_VIDEOS,
        ADD_BOOKMARK,
        PUSH_TO_TALK,
        SAVE_REPLAY_BUFFER,
        TOGGLE_CENSOR_OVERLAY,
        OPEN_CENSOR_EDITOR,
        CANCEL_RECORDING,
        NAME_RECORDING,
        OPEN_RECORDING_SETTINGS,
        OPEN_PENDING_RENDERS
    }

    /**
     * Toggles the live on-screen censor overlay (used when "Bake in Overlay" is
     * OFF). Flips {@link RecordableConfig#censorOverlayHidden}, persists it, and
     * shows an action-bar confirmation. Has no effect on the baked recording.
     */
    private static void toggleCensorOverlay(Minecraft client) {
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
    private static void openCensorEditor(Minecraft client) {
        if (client == null) return;
        if (VersionHelper.currentScreen(client) instanceof dev.recordable.screen.CensorOverlayEditorScreen) return;
        client.setScreenAndShow(new dev.recordable.screen.CensorOverlayEditorScreen(VersionHelper.currentScreen(client)));
    }

    /**
     * Detects the "Name recording" key while a menu screen is open. After the player
     * leaves a world/server they land on the title/multiplayer screen, where game
     * keybindings do NOT accumulate presses (input is routed to the Screen), so the
     * "press X to name it" prompt shown on leave would otherwise do nothing.
     *
     * <p>We poll the bound key's raw GLFW state and fire once on the press edge, but
     * only while a rename is actually pending (the short window opened on save). Outside
     * that window this is a no-op, so it cannot hijack normal typing in menus.</p>
     */
    private static void pollPendingRenameKeyWhileMenuOpen(Minecraft client) {
        if (client == null) {
            renameKeyDownLastTick = false;
            return;
        }
        net.minecraft.client.gui.screens.Screen screen = VersionHelper.currentScreen(client);
        if (screen == null || screen instanceof RenameRecordingScreen) {
            renameKeyDownLastTick = false;
            return;
        }
        RecordingManager mgr = RecordingManager.getInstance();
        if (!mgr.hasPendingRename()) {
            renameKeyDownLastTick = false;
            return;
        }
        boolean down = false;
        if (nameRecordingKey != null && !nameRecordingKey.isUnbound()) {
            try {
                // saveString() returns the bound key's name (e.g. "key.keyboard.n"),
                // reflecting any user rebind; resolve it to a raw GLFW keycode.
                InputConstants.Key key = InputConstants.getKey(nameRecordingKey.saveString());
                if (key != null && key.getType() == InputConstants.Type.KEYSYM
                        && key.getValue() != GLFW.GLFW_KEY_UNKNOWN) {
                    down = InputConstants.isKeyDown(client.getWindow(), key.getValue());
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
    private static void pollOpenSettingsKeyWhileMenuOpen(Minecraft client) {
        if (client == null) {
            settingsKeyDownLastTick = false;
            return;
        }
        net.minecraft.client.gui.screens.Screen screen = VersionHelper.currentScreen(client);
        if (screen == null || screen instanceof RecordableSettingsScreen) {
            settingsKeyDownLastTick = false;
            return;
        }
        boolean down = false;
        if (openSettingsKey != null && !openSettingsKey.isUnbound()) {
            try {
                InputConstants.Key key = InputConstants.getKey(openSettingsKey.saveString());
                if (key != null && key.getType() == InputConstants.Type.KEYSYM
                        && key.getValue() != GLFW.GLFW_KEY_UNKNOWN) {
                    down = InputConstants.isKeyDown(client.getWindow(), key.getValue());
                }
            } catch (Throwable ignored) {
            }
        }
        if (down && !settingsKeyDownLastTick) {
            client.setScreenAndShow(new RecordableSettingsScreen(screen));
        }
        settingsKeyDownLastTick = down;
    }

    /**
     * Returns the currently bound key for the given hotkey as display text
     * (e.g. "-", "=", "F6"), reflecting any user rebinding. Returns
     * {@code "Not Bound"} when the action has no key assigned, and {@code "-"}
     * if the binding has not been initialised yet.
     *
     * <p>This reads the live {@link KeyMapping} each call, so callers that
     * invoke it every frame automatically pick up rebinds without extra
     * refresh logic.</p>
     */
    public static String getBoundKeyDisplay(Hotkey hotkey) {
        if (hotkey == null) return "-";
        KeyMapping kb = switch (hotkey) {
            case TOGGLE_RECORDING -> toggleRecordingKey;
            case PAUSE_RESUME     -> pauseResumeKey;
            case OPEN_SETTINGS    -> openSettingsKey;
            case OPEN_VIDEOS      -> openVideoCollectionKey;
            case ADD_BOOKMARK     -> addBookmarkKey;
            case PUSH_TO_TALK      -> pushToTalkKey;
            case SAVE_REPLAY_BUFFER -> saveReplayBufferKey;
            case TOGGLE_CENSOR_OVERLAY -> toggleCensorOverlayKey;
            case OPEN_CENSOR_EDITOR -> openCensorEditorKey;
            case CANCEL_RECORDING -> cancelRecordingKey;
            case NAME_RECORDING   -> nameRecordingKey;
            case OPEN_RECORDING_SETTINGS -> openRecordingSettingsKey;
            case OPEN_PENDING_RENDERS -> openPendingRendersKey;
        };
        if (kb == null) return "-";
        if (kb.isUnbound()) return "Not Bound";
        return kb.getTranslatedKeyMessage().getString();
    }

    private static Set<Integer> collectClaimedKeyBindings() {
        Set<Integer> claimed = new LinkedHashSet<>(VANILLA_RESERVED_FKEYS);
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null && mc.options != null) {
                for (KeyMapping kb : mc.options.keyMappings) {
                    int code = KeyMappingHelper.getBoundKeyOf(kb).getValue();
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
        if (assigned != GLFW.GLFW_KEY_UNKNOWN) claimed.add(assigned);
        return assigned;
    }

    /**
     * Adds "Recording Settings" and "Pending Renders" buttons to the pause menu
     * ({@link PauseScreen}). The buttons are anchored to the bottom-left corner
     * so they do not overlap the centered vanilla buttons.
     */
    private static void registerPauseMenuButtons() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (!(screen instanceof PauseScreen)) return;
            int btnW = 120;
            int btnH = 20;
            int x = 5;
            int settingsY = scaledHeight - 46;
            int rendersY = scaledHeight - 23;
            Screens.getWidgets(screen).add(Button.builder(
                    Component.literal("Recording Settings"),
                    b -> client.setScreenAndShow(new RecordingSettingsScreen(screen))
            ).bounds(x, settingsY, btnW, btnH).build());
            Screens.getWidgets(screen).add(Button.builder(
                    Component.literal("Pending Renders"),
                    b -> client.setScreenAndShow(new PendingRendersScreen(screen))
            ).bounds(x, rendersY, btnW, btnH).build());

            // Small 20x20 mod-logo button that opens the Video Collection, mirroring
            // the title-screen home button. Placed just to the right of the two
            // stacked buttons and vertically centered across both rows.
            int iconSize = 20;
            int iconX = x + btnW + 4;
            int iconY = settingsY + ((rendersY + btnH) - settingsY) / 2 - iconSize / 2;
            Screens.getWidgets(screen).add(dev.recordable.screen.HomeButtonWidget.create(screen, iconX, iconY));

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
            Screens.getWidgets(screen).add(Button.builder(
                    Component.literal(recActive ? "Stop Recording" : "Start Recording"),
                    b -> RecordingManager.getInstance().toggleRecording(client)
            ).bounds(ctrlX, ctrlY, ctrlW, ctrlH)
             .tooltip(Tooltip.create(Component.literal("Start or stop recording (Record-able)")))
             .build());
            Button pauseResumeBtn = Button.builder(
                    Component.literal(recPaused ? "Resume" : "Pause"),
                    b -> RecordingManager.getInstance().togglePause(client)
            ).bounds(ctrlX + ctrlW + ctrlGap, ctrlY, ctrlW, ctrlH)
             .tooltip(Tooltip.create(Component.literal("Pause or resume the current recording (Record-able)")))
             .build();
            pauseResumeBtn.active = recActive;
            Screens.getWidgets(screen).add(pauseResumeBtn);
        });
    }

    public static void sendClientMessage(Minecraft client, String message, boolean actionBar) {
        if (message == null || message.isBlank()) return;
        try {
            // Route all mod notifications through the custom Record-able toast system.
            ToastQueue.push(message);
        } catch (Throwable t) {
            LOGGER.warn("Failed to display Record-able client message: {}", message, t);
        }
    }
}
