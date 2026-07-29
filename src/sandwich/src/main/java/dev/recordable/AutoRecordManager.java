package dev.recordable;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;

/** Handles automatic recording start/stop triggers and delayed countdown. */
public final class AutoRecordManager {
    private static final int TICKS_PER_SECOND = 20;

    /** Maximum number of ticks we keep a recording alive with no world while waiting for a
     *  server transfer / dimension change / proxy switch to complete before we treat the
     *  absence as a real leave. ~30 seconds at 20 TPS is a generous safety fallback; the
     *  terminal-leave screen check below normally decides much sooner. */
    private static final int TRANSFER_GRACE_TICKS = 600;

    private final Object lock = new Object();
    private int countdownTicksRemaining = -1;
    private boolean gameStartHandled;

    /** Transfer tracking: while a recording is active we keep it running across
     *  disconnects that are actually server transfers (dimension change, proxy hop,
     *  server switch) so the output stays one continuous file. Only a true leave
     *  (quit to title, world/server list, disconnect/kick) finalizes it. */
    private boolean awaitingTransfer = false;
    private int transferTicksRemaining = -1;
    private boolean sawWorldDuringRecording = false;

    /** Deferred notification to display after the player is fully in-game. */
    private volatile String deferredJoinNotification;
    private volatile int deferredJoinTickDelay = -1;


    public void initialize() {
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            try {
                RecordableConfig config = RecordableConfig.get();
                String pendingSaveNotice = RecordingManager.getInstance().consumePendingJoinNotification();
                if (pendingSaveNotice != null && !pendingSaveNotice.isBlank()) {
                    deferredJoinNotification = pendingSaveNotice;
                    deferredJoinTickDelay = 40; // ~2 seconds after join for player to be fully loaded
                }

                // A world just loaded again. If we were holding a recording open across a
                // transfer (dimension change / proxy hop / server switch), the transfer is
                // now complete: keep the single continuous file rolling.
                if (awaitingTransfer) {
                    awaitingTransfer = false;
                    transferTicksRemaining = -1;
                    if (RecordingManager.getInstance().isPaused()) {
                        RecordingManager.getInstance().resumeRecording(client);
                    }
                    RecordableMod.LOGGER.info("Server transfer completed; continuing the same recording.");
                }

                if (config != null && "world_join".equals(config.autoRecordTrigger)) {
                    scheduleAutoRecording(client, "world join");
                }
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.warn("Failed to process auto-record world join event.", throwable);
            }
        });

        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            try {
                cancelScheduledAutoRecord();
                deferredJoinNotification = null;
                deferredJoinTickDelay = -1;

                beginTransferGraceIfRecording();
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.warn("Failed to process auto-record disconnect event.", throwable);
            }
        });
    }

    /**
     * Called when the play connection drops. A disconnect on its own is ambiguous: it happens
     * both for real leaves (quit to title, kicked) AND for benign server transfers (dimension
     * change, proxy hop, /server switch). Instead of stopping right away we keep the recording
     * running and start a grace window. {@link #resolveTransferState(MinecraftClient)} decides,
     * a few ticks later, whether the player rejoined a world (transfer, keep recording) or
     * landed on a terminal leave screen (real leave, finalize the single continuous file).
     */
    private void beginTransferGraceIfRecording() {
        RecordingManager recordingManager = RecordingManager.getInstance();
        if (!recordingManager.isRecording() && !recordingManager.isPaused()) {
            return;
        }
        if (awaitingTransfer) {
            return;
        }
        awaitingTransfer = true;
        transferTicksRemaining = TRANSFER_GRACE_TICKS;
        RecordableMod.LOGGER.info("Connection dropped while recording; holding the file open in case this is a server transfer.");
    }

    public void onClientTick(MinecraftClient client) {
        if (client == null) {
            return;
        }

        resolveTransferState(client);

        if (deferredJoinTickDelay >= 0) {
            deferredJoinTickDelay--;
            if (deferredJoinTickDelay < 0) {
                String notification = deferredJoinNotification;
                deferredJoinNotification = null;
                if (notification != null && !notification.isBlank()) {
                    RecordableMod.sendClientMessage(ChatCategory.AUTO_RECORD, client, notification, false);
                }
            }
        }

        handleGameStartTrigger(client);

        int countdownSnapshot;
        synchronized (lock) {
            countdownSnapshot = countdownTicksRemaining;
        }

        if (countdownSnapshot < 0) {
            return;
        }

        int nextValue;
        synchronized (lock) {
            if (countdownTicksRemaining < 0) {
                return;
            }

            countdownTicksRemaining--;
            nextValue = countdownTicksRemaining;

            if (nextValue >= 0) {
                return;
            }

            countdownTicksRemaining = -1;
        }

        if (!client.isOnThread()) {
            executeOnClientThread(client, () -> triggerStartNow(client), "auto-record countdown completion");
        } else {
            triggerStartNow(client);
        }
    }

    public void onClientStopping(MinecraftClient client) {
        cancelScheduledAutoRecord();
        try {
            RecordableConfig config = RecordableConfig.get();
            if (config != null && "game_quit".equals(config.autoStopTrigger)) {
                stopIfRecording(client, "game quit");
            }
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to process auto-record game quit stop trigger.", throwable);
        }
    }

    public void scheduleAutoRecording(MinecraftClient client, String reason) {
        RecordableConfig config = RecordableConfig.get();
        if (config == null || !config.enabled || !config.autoRecord || "manual".equals(config.autoRecordTrigger)) {
            return;
        }
        if (RecordingManager.getInstance().isActiveOrStopping()) {
            return;
        }
        if (ModCompatibilityChecker.detectReplayPlayback(client)) {
            RecordableMod.LOGGER.info("Skipping auto-record-on-join: replay playback active (handled by compatibility bridge).");
            return;
        }

        int delaySeconds = Math.max(0, config.autoRecordDelay);
        if (delaySeconds <= 0) {
            triggerStartNow(client);
            return;
        }

        synchronized (lock) {
            countdownTicksRemaining = delaySeconds * TICKS_PER_SECOND;
        }

        RecordableMod.LOGGER.info("Scheduled auto-record start in {} seconds ({})", delaySeconds, reason);
    }

    public void cancelScheduledAutoRecord() {
        synchronized (lock) {
            countdownTicksRemaining = -1;
        }
    }

    private void handleGameStartTrigger(MinecraftClient client) {
        if (gameStartHandled) {
            return;
        }

        RecordableConfig config;
        try {
            config = RecordableConfig.get();
        } catch (Throwable throwable) {
            return;
        }

        if (config == null || !config.autoRecord || !"game_start".equals(config.autoRecordTrigger)) {
            return;
        }

        gameStartHandled = true;
        scheduleAutoRecording(client, "game start");
    }

    /**
     * Runs every client tick to decide the fate of a recording whose connection has dropped.
     *
     * <p>The recording is kept as one continuous file across benign server transfers. Each tick:
     * <ul>
     *   <li>If a world+player are present, the session is healthy: remember we have seen a world
     *       and, if we were mid-transfer, treat it as complete (this also covers the case where
     *       the {@code DISCONNECT} event never fired, e.g. a same-process dimension change).</li>
     *   <li>If there is no world and a recording is active, and the player is sitting on a
     *       terminal leave screen (title, world list, server list, disconnected/kick), this is a
     *       real leave: finalize and save.</li>
     *   <li>Otherwise (loading / connecting / downloading terrain) keep waiting, counting down a
     *       generous grace window as a last-resort safety net.</li>
     * </ul>
     */
    private void resolveTransferState(MinecraftClient client) {
        try {
            RecordingManager recordingManager = RecordingManager.getInstance();
            boolean active = recordingManager.isRecording() || recordingManager.isPaused();
            if (!active) {
                awaitingTransfer = false;
                transferTicksRemaining = -1;
                sawWorldDuringRecording = false;
                return;
            }

            // A replay/flashback timeline is loaded (which has its own world). Leave it to
            // the compatibility bridge so we never interfere with replay playback/exports.
            if (ModCompatibilityChecker.detectReplayPlayback(client)) {
                awaitingTransfer = false;
                transferTicksRemaining = -1;
                return;
            }

            if (client.world != null && client.player != null) {
                // Healthy in-world state. If we came out of a transfer without a JOIN event
                // (same-process dimension change), resume and keep the continuous file.
                if (awaitingTransfer) {
                    awaitingTransfer = false;
                    transferTicksRemaining = -1;
                    if (recordingManager.isPaused()) {
                        recordingManager.resumeRecording(client);
                    }
                    RecordableMod.LOGGER.info("World reloaded; continuing the same recording.");
                }
                sawWorldDuringRecording = true;
                return;
            }

            // From here on there is no world but a recording is still active.
            // Never surprise-stop a recording that was started on the menus and never had a world.
            if (!sawWorldDuringRecording) {
                return;
            }

            // The DISCONNECT event may not have fired (another mod intercepted it); make sure
            // the grace window is running so we still resolve the state.
            if (!awaitingTransfer) {
                awaitingTransfer = true;
                transferTicksRemaining = TRANSFER_GRACE_TICKS;
            }

            if (isTerminalLeaveScreen(client.currentScreen)) {
                RecordableMod.LOGGER.info("Player left to a menu ({}); finalizing recording.",
                        client.currentScreen.getClass().getSimpleName());
                finishLeave(client);
                return;
            }

            if (transferTicksRemaining > 0) {
                transferTicksRemaining--;
                return;
            }

            RecordableMod.LOGGER.info("Transfer grace window elapsed with no world; finalizing recording.");
            finishLeave(client);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Transfer-state resolution failed.", throwable);
        }
    }

    /** Finalizes a recording because the player truly left (not a transfer). */
    private void finishLeave(MinecraftClient client) {
        awaitingTransfer = false;
        transferTicksRemaining = -1;
        sawWorldDuringRecording = false;
        cancelScheduledAutoRecord();
        RecordingManager recordingManager = RecordingManager.getInstance();
        if (recordingManager.isStopping()) {
            return;
        }
        if (recordingManager.isRecording() || recordingManager.isPaused()) {
            recordingManager.stopRecordingOnLeave(client);
        }
    }

    /** True when the screen means the player has genuinely left play (not a transfer). */
    private static boolean isTerminalLeaveScreen(Screen screen) {
        return screen instanceof TitleScreen
                || screen instanceof SelectWorldScreen
                || screen instanceof MultiplayerScreen
                || screen instanceof DisconnectedScreen;
    }

    private void triggerStartNow(MinecraftClient client) {
        MinecraftClient activeClient = resolveClient(client);
        if (activeClient != null && !activeClient.isOnThread()) {
            executeOnClientThread(activeClient, () -> triggerStartNow(activeClient), "auto-record start");
            return;
        }

        RecordableConfig config = RecordableConfig.get();
        if (config == null || !config.enabled || !config.autoRecord) {
            return;
        }

        if (ModCompatibilityChecker.detectReplayPlayback(activeClient)) {
            RecordableMod.LOGGER.info("Skipping auto-record: replay playback active (handled by compatibility bridge).");
            return;
        }

        if (!RecordingManager.getInstance().isActiveOrStopping()) {
            RecordingManager.getInstance().startRecording(activeClient);
            if (RecordingManager.getInstance().isRecording()) {
                RecordableMod.sendClientMessage(ChatCategory.AUTO_RECORD, activeClient, "Auto-recording started", false);
            }
        }
    }

    private static void stopIfRecording(MinecraftClient client, String reason) {
        MinecraftClient activeClient = resolveClient(client);
        if (activeClient != null && !activeClient.isOnThread()) {
            executeOnClientThread(activeClient, () -> stopIfRecording(activeClient, reason), "auto-record stop");
            return;
        }

        RecordingManager recordingManager = RecordingManager.getInstance();
        if (recordingManager.isStopping()) {
            return;
        }

        if (recordingManager.isRecording() || recordingManager.isPaused()) {
            RecordableMod.sendClientMessage(ChatCategory.AUTO_RECORD, activeClient, "Auto-recording stopped (" + reason + ")", true);
            recordingManager.stopRecording(activeClient, RecordingManager.StopReason.AUTO);
        }
    }

    private static MinecraftClient resolveClient(MinecraftClient client) {
        return client == null ? MinecraftClient.getInstance() : client;
    }

    private static void executeOnClientThread(MinecraftClient client, Runnable runnable, String actionDescription) {
        if (client == null || runnable == null) {
            return;
        }
        try {
            client.execute(runnable);
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to schedule Record-able {} on the client thread.", actionDescription, throwable);
        }
    }
}
