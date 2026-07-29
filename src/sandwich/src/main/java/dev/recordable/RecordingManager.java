package dev.recordable;

import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/** Coordinates capture, frame pacing, FFmpeg encoding, state, and shutdown. */
public final class RecordingManager {
    public enum State {
        IDLE,
        STARTING,
        RECORDING,
        PAUSED,
        STOPPING
    }

    private static final RecordingManager INSTANCE = new RecordingManager();
    private static final int MAX_QUEUE_SIZE = 240;
    private static final long BACKLOG_LOG_INTERVAL_NANOS = 2_000_000_000L;
    private static final long PERFORMANCE_SAMPLE_INTERVAL_NANOS = 1_000_000_000L;

    private final Object lock = new Object();
    private final AtomicLong capturedFrames = new AtomicLong();
    private final AtomicLong skippedFrames = new AtomicLong();
    private final AtomicLong failedCaptures = new AtomicLong();
    private final AtomicLong adaptiveDroppedFrames = new AtomicLong();
    private final AtomicLong finalEncoderDroppedFrames = new AtomicLong();

    private volatile State state = State.IDLE;
    private ScreenCapture screenCapture;
    private ScreenCapture replayBufferCapture; // Standalone capture for replay buffer when not recording
    private FFmpegEncoder ffmpegEncoder;
    private FFmpegEncoder pendingStartFfmpegEncoder;
    private Path currentOutputFile;
    private Path lastOutputFile;
    private long startedAtNanos;
    private long lastFrameCaptureNanos;
    private long frameIntervalNanos;
    private int recordingFps = 60;
    private int recordingWidth;
    private int recordingHeight;
    private volatile String pendingJoinNotification;
    private long lastBacklogLogAtNanos;
    private long lastPerformanceSampleAtNanos;
    private long lastCapturedSample;
    private long lastEncoderWrittenSample;
    private volatile double captureFpsEstimate;
    private volatile double encoderFpsEstimate;
    private volatile boolean performanceSuggestionSent;
    private volatile int onFrameNullEncoderLogCount;

    // === Deferred (offline smooth-render) capture ===
    // When enabled, recording bypasses the realtime FFmpeg encoder entirely and stores
    // raw RGB frames to disk at a low FPS (5-30) to minimize GPU overhead and FPS hit.
    // Audio is captured via JavaAudioCapture to a .wav file. After recording, frames
    // are rendered offline into smooth 30/60/120fps video with optional interpolation.
    private volatile String deferredSessionId;
    private long lastDeferredStoreNanos;
    private volatile JavaAudioCapture deferredAudioCapture;
    // True while a deferred (offline-render) recording is active. In this mode onFrame()
    // captures pixels and stores them to disk but does NOT feed a realtime FFmpeg encoder.
    private volatile boolean deferredMode;

    /** Stores a captured frame for deferred offline rendering when the mode is enabled. */
    private void maybeStoreDeferredFrame(RecordableConfig cfg, int width, int height,
                                         byte[] frameData, long frameTimestampMs, long now) {
        if (cfg == null || !cfg.deferredRenderEnabled || frameData == null) {
            return;
        }
        DeferredCaptureManager dcm = DeferredCaptureManager.getInstance();
        if (deferredSessionId == null) {
            String prefix = "recordable";
            Path out = currentOutputFile;
            if (out != null) {
                prefix = out.getFileName().toString().replaceFirst("\\.[^.]+$", "");
            }
            deferredSessionId = dcm.startSession(prefix, width, height,
                    cfg.deferredCaptureFps, cfg.deferredTargetFps, cfg.deferredInterpolation, false);
            lastDeferredStoreNanos = 0L;
        }
        long minInterval = 1_000_000_000L / Math.max(1, cfg.deferredCaptureFps);
        if (lastDeferredStoreNanos == 0L || (now - lastDeferredStoreNanos) >= minInterval) {
            dcm.storeFrame(frameData, frameTimestampMs);
            lastDeferredStoreNanos = now;
            capturedFrames.incrementAndGet();
        }
    }

    /** Finalizes or cancels the deferred session (if any) when a recording stops. */
    private void finalizeDeferredSession(StopReason reason) {
        if (deferredSessionId == null) {
            return;
        }

        // Stop audio capture
        if (deferredAudioCapture != null) {
            try {
                deferredAudioCapture.stop();
                RecordableMod.LOGGER.info("JavaAudioCapture stopped ({} bytes captured).",
                        deferredAudioCapture.getCapturedBytes());
            } catch (Exception ex) {
                RecordableMod.LOGGER.warn("Failed to stop JavaAudioCapture: {}", ex.getMessage());
            }
            deferredAudioCapture = null;
        }

        DeferredCaptureManager dcm = DeferredCaptureManager.getInstance();
        if (reason == StopReason.CANCEL) {
            dcm.cancelSession();
            deferredSessionId = null;
            return;
        }

        // Finalize the session
        dcm.finalizeSession();
        
        RecordableConfig cfg = RecordableConfig.get();
        if (cfg.deferredAutoRender) {
            // Auto-render is not wired yet, keep current prompt flow while surfacing config intent.
            RecordableMod.LOGGER.info("[DeferredRender] Auto-render is enabled, queuing render prompt for session {}",
                    deferredSessionId);
        }
        pendingDeferredSessionForPrompt = deferredSessionId;
        
        deferredSessionId = null;
    }
    
    /**
     * Consumes and returns the pending deferred session ID if one is waiting for a render prompt.
     * Called by the keybind handler to open RenderPromptScreen.
     */
    public String consumePendingDeferredSession() {
        String session = pendingDeferredSessionForPrompt;
        pendingDeferredSessionForPrompt = null;
        return session;
    }

    // === Adaptive capture-fps governor (V1-0.08 FPS optimization) ===
    // A small, self-contained governor that lowers the effective capture rate
    // (raising frameIntervalNanos) while the game FPS is below the configured
    // target, then relaxes back toward the base rate once the game recovers.
    // Because the FFmpeg encoder runs in CFR + frame-duplication mode, lowering
    // the capture rate on the fly is safe: missing timestamps are filled by
    // duplicating the last frame, so no encoder restart is needed. This keeps
    // the render thread cheaper on low-end devices (Android/Pojav) where the
    // per-frame readback and pixel conversion are the main FPS cost.
    /** Base capture interval captured at recording start (never changed by the governor). */
    private long baseFrameIntervalNanos;
    /** Last time the governor evaluated game FPS (nanos); gates checks to ~1/sec. */
    private long lastGovernorCheckNanos;
    /** Current effective capture fps chosen by the governor. */
    private int governorCurrentFps;
    /** Consecutive governor checks with game FPS below the target (hysteresis, anti-thrash). */
    private int governorLowStreak;
    /** Consecutive governor checks with game FPS recovered above the target (hysteresis, anti-thrash). */
    private int governorHighStreak;

    // === Capture self-test (Test Capture diagnostic) ===
    // The diagnostic screen requests a one-shot self-test; it runs on the next
    // render frame (valid GL context) and stores its result for the UI to poll.
    private volatile boolean selfTestRequested;
    private volatile CaptureDiagnostics.SelfTestResult selfTestResult;



    // === Pause/Resume timestamp correction ===
    /** Total nanoseconds spent in paused state (accumulated across multiple pauses). */
    private long totalPausedNanos;
    /** Nano timestamp when the current pause started (0 if not paused). */
    private long pauseStartNanos;

    // === Recording Bookmarks ===
    private final List<RecordingBookmark> bookmarks = Collections.synchronizedList(new ArrayList<>());
    private int bookmarkCounter;

    // === Post-recording toast notification ===
    private volatile String pendingToastMessage;
    private volatile Path pendingToastFilePath;
    private volatile long pendingToastExpiresAtMs;
    
    // === Deferred render prompt ===
    private volatile String pendingDeferredSessionForPrompt;

    // === Recording timer ===
    /** Effective recording duration in millis (excluding paused time). */
    public long getEffectiveRecordingMillis() {
        if (!isActiveOrStopping() || startedAtNanos == 0L) return 0L;
        long now = System.nanoTime();
        long totalElapsed = now - startedAtNanos;
        long paused = totalPausedNanos;
        if (state == State.PAUSED && pauseStartNanos > 0) {
            paused += (now - pauseStartNanos);
        }
        return Math.max(0L, (totalElapsed - paused) / 1_000_000L);
    }

    public enum StopReason {
        MANUAL,
        DISCONNECT,
        SHUTDOWN,
        AUTO,
        FILE_SIZE_LIMIT,
        CANCEL,
        /** Player truly left the world/server (quit to title, kicked, server list). */
        LEAVE
    }

    public enum QueueHealth {
        OK,
        SLOW,
        CRITICAL
    }

    private RecordingManager() {
    }

    public static RecordingManager getInstance() {
        return INSTANCE;
    }

    public void initialize() {
        FFmpegEncoder.detectFfmpeg();
    }

    /**
     * Returns {@code true} if the game is in a valid state for recording.
     */
    public static boolean isInGameState(MinecraftClient client) {
        if (client == null) return false;
        return client.world != null && client.player != null;
    }

    public void toggleRecording(MinecraftClient client) {
        State snapshot = state;
        if (snapshot == State.IDLE) {
            MinecraftClient mc = resolveClient(client);
            if (!isInGameState(mc)) {
                return;
            }
            startRecording(client);
        } else if (snapshot == State.RECORDING || snapshot == State.PAUSED) {
            stopRecording(client);
        } else if (snapshot == State.STARTING) {
            RecordableMod.LOGGER.info("Record-able is still initializing; ignoring toggle.");
        } else {
            RecordableMod.LOGGER.info("Record-able is already stopping; ignoring request.");
        }
    }

    public void startRecording(MinecraftClient client) {
        startRecording(client, null);
    }

    public void startRecording(MinecraftClient client, String filePrefix) {
        MinecraftClient activeClient = resolveClient(client);
        if (activeClient != null && !activeClient.isOnThread()) {
            executeOnClientThread(activeClient, () -> startRecording(activeClient, filePrefix), "start recording");
            return;
        }

        if (!isInGameState(activeClient)) {
            return;
        }

        RecordableConfig config;
        int targetWidth;
        int targetHeight;
        int targetFps;

        synchronized (lock) {
            if (state != State.IDLE) {
                RecordableMod.LOGGER.info("Record-able is already active; ignoring start request.");
                return;
            }

            if (!isInGameState(activeClient)) {
                return;
            }

            config = RecordableConfig.get();
            config.sanitize();
            if (!config.enabled) {
                config.enabled = true;
                config.save();
                RecordableMod.LOGGER.info("Record-able was disabled; auto-enabled and starting recording.");
            }
            autoRepairAudioSettingsForRecording(config, activeClient);

            FFmpegEncoder.FfmpegStatus ffStatus = FFmpegEncoder.detectFfmpeg();
            if (!ffStatus.found()) {
                String hint = PlatformUtils.getFfmpegInstallHint();
                RecordableMod.sendClientMessage(ChatCategory.RECORDING, client,
                        "§cFFmpeg is required but not found. " + hint, false);
                RecordableMod.sendClientMessage(ChatCategory.RECORDING, client,
                        "§eOpen Record-able settings and click 'Download FFmpeg' for an automatic install.", false);
                return;
            }

            if (activeClient == null || activeClient.getWindow() == null) {
                RecordableMod.sendClientMessage(ChatCategory.RECORDING, client, "Record-able could not access the Minecraft window.", false);
                return;
            }

            int nativeWidth = activeClient.getWindow().getFramebufferWidth();
            int nativeHeight = activeClient.getWindow().getFramebufferHeight();
            if (nativeWidth <= 0 || nativeHeight <= 0) {
                RecordableMod.sendClientMessage(ChatCategory.RECORDING, client, "Record-able could not determine the framebuffer size.", false);
                return;
            }

            RecordableConfig.CaptureDimensions dimensions = config.resolveCaptureDimensions(nativeWidth, nativeHeight);
            targetWidth = makeEven(Math.max(2, dimensions.width()));
            targetHeight = makeEven(Math.max(2, dimensions.height()));
            targetFps = config.getFps();
            if (targetWidth != dimensions.width() || targetHeight != dimensions.height()) {
                RecordableMod.LOGGER.info("Adjusted recording resolution to even dimensions: {}x{} -> {}x{}",
                        dimensions.width(), dimensions.height(), targetWidth, targetHeight);
            }

            // Pre-flight disk space check
            try {
                Path outputDir = config.getOutputDirectory();
                java.nio.file.Files.createDirectories(outputDir);
                long freeBytes = java.nio.file.Files.getFileStore(outputDir).getUsableSpace();
                long freeMB = freeBytes / (1024L * 1024L);
                if (freeMB < config.diskSpaceMinFreeMB) {
                    RecordableMod.sendClientMessage(ChatCategory.RECORDING, client,
                            "§c⚠ Low disk space! Only " + freeMB + " MB free (minimum: " + config.diskSpaceMinFreeMB + " MB). "
                            + "Free up space or change the output directory.", false);
                }
                long freePercent = 100L - (freeBytes * 100L / Math.max(1L, java.nio.file.Files.getFileStore(outputDir).getTotalSpace()));
                if (freePercent >= config.diskSpaceBlockPercent) {
                    if (config.disableDiskSpaceUsageBlock) {
                        RecordableMod.sendClientMessage(ChatCategory.WARNINGS, client,
                                "§e⚠ Disk usage is " + freePercent + "% (block threshold: " + config.diskSpaceBlockPercent + "%). "
                                + "Safety block is disabled, recording will continue.", false);
                        RecordableMod.sendClientMessage(ChatCategory.WARNINGS, client,
                                "§cBy continuing, you accept all risk. The mod author takes no liability for data loss, failed recordings, or system issues.", false);
                    } else {
                        RecordableMod.sendClientMessage(ChatCategory.RECORDING, client,
                                "§c⛔ Disk usage is " + freePercent + "% (block threshold: " + config.diskSpaceBlockPercent + "%). "
                                + "Recording blocked. Free up space first.", false);
                        return;
                    }
                }
            } catch (Exception diskEx) {
                RecordableMod.LOGGER.warn("Could not check disk space before recording: {}", diskEx.getMessage());
                // Non-fatal: continue with recording anyway
            }

            // === DEFERRED MODE: Bypass realtime encoder, store frames for offline rendering ===
            if (config.deferredRenderEnabled) {
                RecordableMod.LOGGER.info("Starting deferred (offline-render) recording at {}fps capture (will render to {}fps later).",
                        config.deferredCaptureFps, config.deferredTargetFps);

                // Name the deferred output like a realtime recording: manual recordings use the
                // user's "Rename File Name" pattern, and auto-clips are organized into their
                // clips/<event>/ subfolder (matching the realtime encoder's clip layout).
                String sessionName;
                String outputSubfolder = null;
                if (filePrefix != null && filePrefix.startsWith("on-")) {
                    sessionName = RecordableConfig.resolveFilenamePattern(
                            RecordableConfig.defaultClipFilenamePatternForPrefix(filePrefix));
                    outputSubfolder = "clips/" + RecordableConfig.clipSubfolderForPrefix(filePrefix);
                } else if (filePrefix == null || filePrefix.isBlank()) {
                    sessionName = RecordableConfig.resolveFilenamePattern(config.filenamePattern);
                } else {
                    sessionName = "recordable-" + filePrefix;
                }
                DeferredCaptureManager dcm = DeferredCaptureManager.getInstance();
                deferredSessionId = dcm.startSession(sessionName, targetWidth, targetHeight,
                        config.deferredCaptureFps, config.deferredTargetFps, config.deferredInterpolation, false);
                if (outputSubfolder != null) {
                    dcm.setActiveOutputSubfolder(outputSubfolder);
                }

                // Start audio capture
                Path audioPath = dcm.getAudioPath(deferredSessionId);
                if (audioPath != null) {
                    try {
                        deferredAudioCapture = new JavaAudioCapture(audioPath);
                        boolean audioOk = deferredAudioCapture.start();
                        if (!audioOk) {
                            RecordableMod.LOGGER.warn("JavaAudioCapture could not start (no loopback device). Recording will be video-only.");
                        }
                    } catch (Exception audioEx) {
                        RecordableMod.LOGGER.warn("Failed to start JavaAudioCapture: {}", audioEx.getMessage());
                        deferredAudioCapture = null;
                    }
                }

                // Initialize capture state
                recordingWidth = targetWidth;
                recordingHeight = targetHeight;
                recordingFps = targetFps;
                frameIntervalNanos = 1_000_000_000L / Math.max(1, targetFps);
                startedAtNanos = System.nanoTime();
                lastFrameCaptureNanos = 0L;
                lastDeferredStoreNanos = 0L;

                // Set output file path (for UI display, not actually written yet)
                Path sessionDir = dcm.getSessionDir(deferredSessionId);
                currentOutputFile = sessionDir != null ? sessionDir.resolve("pending.mkv") : null;

                // Initialize the screen capture so onFrame() actually grabs pixels. Without
                // this, onFrame() bails out early and the deferred session records ZERO frames.
                screenCapture = new ScreenCapture(targetWidth, targetHeight);
                deferredMode = true;
                onFrameNullEncoderLogCount = 0;
                capturedFrames.set(0L);
                skippedFrames.set(0L);
                failedCaptures.set(0L);

                // Go directly to RECORDING (no async start needed)
                state = State.RECORDING;

                RecordableMod.sendClientMessage(ChatCategory.RECORDING, activeClient,
                        "§aDeferred recording started! §7Capturing at " + config.deferredCaptureFps + "fps (will render to " + config.deferredTargetFps + "fps later)", false);
                return;
            }

            // === NORMAL MODE: Realtime FFmpeg encoder ===
            deferredMode = false;
            pendingStartFfmpegEncoder = new FFmpegEncoder(config, targetWidth, targetHeight, targetFps, MAX_QUEUE_SIZE, filePrefix);
            state = State.STARTING;
        }

        RecordableMod.LOGGER.info("Starting recording with FFmpeg encoder ({}x{} @ {} FPS).",
                targetWidth, targetHeight, targetFps);
        RecordableMod.LOGGER.info("Record-able initializing (FFmpeg).");

        final int fWidth = targetWidth;
        final int fHeight = targetHeight;
        final int fFps = targetFps;

        CompletableFuture.runAsync(() -> {
            Path output = null;
            Throwable startFailure = null;
            try {
                MinecraftClient clientSnapshot = resolveClient(activeClient);
                if (!isInGameState(clientSnapshot)) {
                    synchronized (lock) {
                        if (state == State.STARTING) {
                            state = State.IDLE;
                            pendingStartFfmpegEncoder = null;
                        }
                    }
                    return;
                }

                output = pendingStartFfmpegEncoder.start();
            } catch (Throwable throwable) {
                startFailure = throwable;
            }

            Path finalizedOutput = output;
            Throwable finalizedFailure = startFailure;
            executeOnClientThread(activeClient,
                    () -> completeStartOnClientThread(activeClient, config, fWidth, fHeight, fFps, finalizedOutput, finalizedFailure),
                    "complete recording start");
        });
    }

    private static void autoRepairAudioSettingsForRecording(RecordableConfig config, MinecraftClient client) {
        if (config == null || config.captureAudio) {
            return;
        }
        if (!OpenALLoopbackCapture.getInstance().isActive()) {
            return;
        }
        config.captureAudio = true;
        config.save();
        RecordableMod.LOGGER.warn("captureAudio was disabled while loopback audio is active. Auto-enabled game audio capture to prevent silent recordings.");
        RecordableMod.sendClientMessage(ChatCategory.RECORDING, client,
                "§eAudio capture was OFF and would produce silent recordings. Game audio capture was re-enabled automatically.", true);
    }

    private void completeStartOnClientThread(MinecraftClient client,
                                             RecordableConfig config,
                                             int targetWidth,
                                             int targetHeight,
                                             int targetFps,
                                             Path output,
                                             Throwable startFailure) {
        boolean cancelled;
        synchronized (lock) {
            cancelled = state != State.STARTING;
        }

        if (cancelled) {
            RecordableMod.LOGGER.info("Background start completed after cancellation; stopping encoder instance.");
            stopPendingEncoder();
            return;
        }

        if (startFailure != null || output == null) {
            synchronized (lock) {
                state = State.IDLE;
                pendingStartFfmpegEncoder = null;
            }
            stopPendingEncoder();
            String reason = startFailure == null ? "Unknown startup failure" : startFailure.getMessage();
            RecordableMod.LOGGER.warn("Failed to start Record-able recording (resolution={}x{}, fps={}).",
                    targetWidth, targetHeight, targetFps, startFailure);
            RecordableMod.sendClientMessage(ChatCategory.RECORDING, client, "Record-able could not start: " + reason, false);
            return;
        }

        ScreenCapture newCapture = null;
        try {
            newCapture = new ScreenCapture(targetWidth, targetHeight);

            synchronized (lock) {
                if (state != State.STARTING) {
                    throw new IOException("Recording start was cancelled before capture initialization completed.");
                }
                ffmpegEncoder = pendingStartFfmpegEncoder;
                RecordableMod.LOGGER.info("Assigned active FFmpeg encoder: {}", ffmpegEncoder != null ? "OK" : "NULL!");
                pendingStartFfmpegEncoder = null;
                screenCapture = newCapture;
                currentOutputFile = output;
                lastOutputFile = output;
                // Crash-recovery marker: record the active output so an unclean exit
                // (crash / hard kill) can offer to recover this file on next launch.
                RecoveryManager.markActive(output, output.getFileName().toString());
                recordingWidth = targetWidth;
                recordingHeight = targetHeight;
                recordingFps = targetFps;
                capturedFrames.set(0L);
                skippedFrames.set(0L);
                failedCaptures.set(0L);
                adaptiveDroppedFrames.set(0L);
                finalEncoderDroppedFrames.set(0L);
                startedAtNanos = System.nanoTime();
                lastFrameCaptureNanos = 0L;
                totalPausedNanos = 0L;
                pauseStartNanos = 0L;
                frameIntervalNanos = Math.max(1L, 1_000_000_000L / Math.max(1, recordingFps));
                baseFrameIntervalNanos = frameIntervalNanos;
                governorCurrentFps = recordingFps;
                governorLowStreak = 0;
                governorHighStreak = 0;
                lastGovernorCheckNanos = startedAtNanos;
                lastBacklogLogAtNanos = 0L;
                lastPerformanceSampleAtNanos = startedAtNanos;
                lastCapturedSample = 0L;
                lastEncoderWrittenSample = 0L;
                captureFpsEstimate = 0.0D;
                encoderFpsEstimate = 0.0D;
                performanceSuggestionSent = false;
                onFrameNullEncoderLogCount = 0;
                // V1-0.08: toggle frame-buffer pooling per config (reduces GC churn).
                FrameBufferPool.getInstance().setEnabled(config.frameBufferPoolingEnabled);
                state = State.RECORDING;
                
                // V1-0.09: notify HudHideManager that recording started (for clean recording features)
                HudHideManager.onRecordingStart();

                // Reset bookmarks for new recording
                bookmarks.clear();
                bookmarkCounter = 0;

                // Clean recording: no overlays baked into the video stream
            }

            // Start replay buffer if enabled, with disk-space safety check
            if (config.replayBufferEnabled) {
                String diskSafetyWarning = ReplayBuffer.checkMemorySafety(
                        recordingWidth, recordingHeight, recordingFps, config.replayBufferDurationSeconds);
                if (diskSafetyWarning != null) {
                    RecordableMod.LOGGER.warn("Replay buffer disabled due to low disk space: {}", diskSafetyWarning);
                    RecordableMod.sendClientMessage(ChatCategory.RECORDING, client,
                            "§eReplay buffer skipped: insufficient disk space. " + diskSafetyWarning, true);
                } else {
                    ReplayBuffer.getInstance().start(recordingWidth, recordingHeight,
                            recordingFps, config.replayBufferDurationSeconds);
                }
            }

            boolean audioActive = ffmpegEncoder != null && ffmpegEncoder.isAudioEnabled();
            String audioDevStr = ffmpegEncoder != null ? ffmpegEncoder.getAudioDeviceInfo() : "";
            String audioInfo;
            if (audioActive) {
                audioInfo = " + audio [" + audioDevStr + "]";
            } else if (config.captureAudio) {
                audioInfo = " (audio unavailable - recording video only)";
                RecordableMod.sendClientMessage(ChatCategory.RECORDING, client,
                        "§eAudio unavailable. Recording video only.", true);
            } else {
                audioInfo = "";
            }

            RecordableMod.sendClientMessage(ChatCategory.RECORDING, client, "Recording started!", false);
            RecordableMod.LOGGER.info("Recording started: file={} resolution={}x{} fps={} audioEnabled={} audioDevice={}",
                    output, recordingWidth, recordingHeight, recordingFps, audioActive, audioDevStr);
        } catch (Throwable throwable) {
            synchronized (lock) {
                state = State.IDLE;
                pendingStartFfmpegEncoder = null;
                ffmpegEncoder = null;
                screenCapture = null;
            }
            if (newCapture != null) {
                try {
                    newCapture.close();
                } catch (Throwable closeThrowable) {
                    RecordableMod.LOGGER.debug("Failed to close capture after start failure.", closeThrowable);
                }
            }
            stopPendingEncoder();
            RecordableMod.LOGGER.warn("Failed to finalize Record-able start on client thread.", throwable);
            RecordableMod.sendClientMessage(ChatCategory.RECORDING, client, "Record-able could not start: " + throwable.getMessage(), false);
        }
    }

    private void stopPendingEncoder() {
        try {
            if (pendingStartFfmpegEncoder != null) {
                pendingStartFfmpegEncoder.stop();
            }
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("Encoder cleanup after failed/cancelled start.", t);
        }
    }

    public void pauseRecording(MinecraftClient client) {
        MinecraftClient activeClient = resolveClient(client);
        if (activeClient != null && !activeClient.isOnThread()) {
            executeOnClientThread(activeClient, () -> pauseRecording(activeClient), "pause recording");
            return;
        }

        synchronized (lock) {
            if (state != State.RECORDING) {
                return;
            }
            state = State.PAUSED;
            pauseStartNanos = System.nanoTime();
            lastFrameCaptureNanos = 0L;
        }
        RecordableMod.LOGGER.info("Recording paused. Effective duration so far: {}",
                formatDuration(getEffectiveRecordingMillis()));
        RecordableMod.sendClientMessage(ChatCategory.RECORDING, activeClient, "§e⏸ Record-able paused.", true);
    }

    public void resumeRecording(MinecraftClient client) {
        MinecraftClient activeClient = resolveClient(client);
        if (activeClient != null && !activeClient.isOnThread()) {
            executeOnClientThread(activeClient, () -> resumeRecording(activeClient), "resume recording");
            return;
        }

        synchronized (lock) {
            if (state != State.PAUSED) {
                return;
            }
            // Accumulate pause duration
            if (pauseStartNanos > 0) {
                totalPausedNanos += (System.nanoTime() - pauseStartNanos);
                pauseStartNanos = 0;
            }
            state = State.RECORDING;
            lastFrameCaptureNanos = 0L;
        }
        RecordableMod.LOGGER.info("Recording resumed. Total paused time: {}ms",
                totalPausedNanos / 1_000_000L);
        RecordableMod.sendClientMessage(ChatCategory.RECORDING, activeClient, "§a▶ Record-able resumed.", true);
    }

    /** Toggle between paused and recording states. */
    public void togglePause(MinecraftClient client) {
        State snapshot = state;
        if (snapshot == State.RECORDING) {
            pauseRecording(client);
        } else if (snapshot == State.PAUSED) {
            resumeRecording(client);
        }
    }

    public void stopRecording(MinecraftClient client) {
        stopRecording(client, StopReason.MANUAL);
    }

    /**
     * Cancels (discards) the current recording: stops the encoder/capture like a
     * normal stop, then deletes the finalized output file and its sidecars instead
     * of saving. Shows a "Recording cancelled" toast. No rename prompt is shown.
     */
    public void cancelRecording(MinecraftClient client) {
        if (!isRecording() && !isPaused() && state != State.STARTING) {
            RecordableMod.sendClientMessage(ChatCategory.RECORDING, resolveClient(client),
                    "Record-able is not recording.", true);
            return;
        }
        stopRecording(client, StopReason.CANCEL);
    }

    /** Deletes a finalized recording file and any known sidecar files (best effort). */
    private void discardOutputFiles(Path finalizedOutput) {
        if (finalizedOutput == null) {
            return;
        }
        try {
            java.nio.file.Files.deleteIfExists(finalizedOutput);
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("Failed to delete cancelled recording file {}", finalizedOutput, t);
        }
        try {
            Path parent = finalizedOutput.getParent();
            String fileName = finalizedOutput.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String base = dot > 0 ? fileName.substring(0, dot) : fileName;
            if (parent != null) {
                java.nio.file.Files.deleteIfExists(parent.resolve(base + "_bookmarks.txt"));
            }
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("Failed to delete sidecar for cancelled recording {}", finalizedOutput, t);
        }
    }


    /**
     * Renames a finalized recording file (and its "_bookmarks.txt" sidecar, if any)
     * to the given base name, preserving the original extension. The name is
     * sanitized to strip path separators and characters illegal in file names. A
     * blank name, or a name equal to the current one, is treated as a no-op and the
     * original path is returned. If a file with the chosen name already exists, a
     * numeric suffix like " (1)" is appended. Returns the new path on success, the
     * original path on no-op, or {@code null} on failure.
     */
    public Path renameRecording(Path original, String newBaseName) {
        if (original == null) {
            return null;
        }
        try {
            String fileName = original.getFileName().toString();
            int dot = fileName.lastIndexOf('.');
            String ext = dot > 0 ? fileName.substring(dot) : "";
            String currentBase = dot > 0 ? fileName.substring(0, dot) : fileName;
            String sanitized = sanitizeRecordingName(newBaseName);
            if (sanitized.isEmpty() || sanitized.equals(currentBase)) {
                return original;
            }
            Path parent = original.getParent();
            Path target = parent == null ? Path.of(sanitized + ext) : parent.resolve(sanitized + ext);
            int counter = 1;
            while (Files.exists(target)) {
                String candidate = sanitized + " (" + counter + ")" + ext;
                target = parent == null ? Path.of(candidate) : parent.resolve(candidate);
                counter++;
            }
            Files.move(original, target);
            // Move the bookmarks sidecar alongside the renamed video, if it exists.
            Path bookmarksSrc = parent == null
                    ? Path.of(currentBase + "_bookmarks.txt")
                    : parent.resolve(currentBase + "_bookmarks.txt");
            if (Files.exists(bookmarksSrc)) {
                String newName = target.getFileName().toString();
                int nd = newName.lastIndexOf('.');
                String newBase = nd > 0 ? newName.substring(0, nd) : newName;
                Path bookmarksTarget = parent == null
                        ? Path.of(newBase + "_bookmarks.txt")
                        : parent.resolve(newBase + "_bookmarks.txt");
                try {
                    Files.move(bookmarksSrc, bookmarksTarget);
                } catch (Throwable t) {
                    RecordableMod.LOGGER.debug("Failed to move bookmarks sidecar during rename {}", bookmarksSrc, t);
                }
            }
            if (original.equals(lastOutputFile)) {
                lastOutputFile = target;
            }
            if (original.equals(currentOutputFile)) {
                currentOutputFile = target;
            }
            RecordableMod.LOGGER.info("Recording renamed: {} -> {}", original, target);
            return target;
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("Failed to rename recording {}", original, t);
            return null;
        }
    }

    /** Strips path separators / illegal file-name characters and trims the result. */
    private static String sanitizeRecordingName(String name) {
        if (name == null) {
            return "";
        }
        String trimmed = name.trim();
        trimmed = trimmed.replaceAll("[\\/:*?\"<>|]", "_");
        trimmed = trimmed.replaceAll("^[.\\s]+", "").replaceAll("[.\\s]+$", "");
        if (trimmed.length() > 200) {
            trimmed = trimmed.substring(0, 200);
        }
        return trimmed;
    }

    public void stopRecordingForDisconnect(MinecraftClient client) {
        stopRecording(client, StopReason.DISCONNECT);
    }

    /**
     * Stops and SAVES the recording because the player truly left the world or
     * server (quit to title, disconnected, kicked, or went back to the server
     * list). Unlike a transient dimension/proxy transfer - which keeps recording -
     * this finalizes the file and offers the post-recording rename prompt so the
     * player can name it right from the menu.
     */
    public void stopRecordingOnLeave(MinecraftClient client) {
        stopRecording(client, StopReason.LEAVE);
    }

    public void stopRecording(MinecraftClient client, StopReason stopReason) {
        StopReason reason = stopReason == null ? StopReason.MANUAL : stopReason;
        MinecraftClient activeClient = resolveClient(client);
        if (reason != StopReason.DISCONNECT && reason != StopReason.SHUTDOWN) {
            if (activeClient != null && !activeClient.isOnThread()) {
                executeOnClientThread(activeClient, () -> stopRecording(activeClient, reason), "stop recording");
                return;
            }
        }

        FFmpegEncoder ffmpegToStop = null;
        ScreenCapture captureToClose;
        Path output;
        boolean cancelledDuringStart = false;
        synchronized (lock) {
            if (state == State.IDLE) {
                if (reason != StopReason.DISCONNECT && reason != StopReason.SHUTDOWN) {
                    RecordableMod.sendClientMessage(ChatCategory.RECORDING, activeClient, "Record-able is not recording.", true);
                }
                return;
            }
            if (state == State.STOPPING) {
                if (reason != StopReason.DISCONNECT && reason != StopReason.SHUTDOWN) {
                    RecordableMod.LOGGER.info("Record-able is already stopping; ignoring request.");
                }
                return;
            }
            if (state == State.STARTING) {
                cancelledDuringStart = true;
                state = State.IDLE;
                ffmpegToStop = pendingStartFfmpegEncoder;
                pendingStartFfmpegEncoder = null;
                captureToClose = null;
                output = currentOutputFile;
                lastFrameCaptureNanos = 0L;
                frameIntervalNanos = 0L;
            } else {
                state = State.STOPPING;
                ffmpegToStop = ffmpegEncoder;
                captureToClose = screenCapture;
                output = currentOutputFile;
                ffmpegEncoder = null;
                screenCapture = null;
                deferredMode = false;

                lastFrameCaptureNanos = 0L;
                frameIntervalNanos = 0L;
            }
        }

        if (cancelledDuringStart) {
            RecordableMod.LOGGER.info("Recording start cancelled before activation.");
            // Release the replay buffer if it was started before the cancellation.
            if (ReplayBuffer.getInstance().isActive()) {
                ReplayBuffer.getInstance().stop();
            }
            final FFmpegEncoder ffmpegCancel = ffmpegToStop;
            Thread cancelThread = new Thread(() -> {
                try {
                    if (ffmpegCancel != null) ffmpegCancel.stop();
                } catch (Throwable throwable) {
                    RecordableMod.LOGGER.warn("Failed to stop encoder after start cancellation.", throwable);
                }
            }, "Record-able Start Cancel Stopper");
            cancelThread.setDaemon(true);
            cancelThread.start();
            if (reason != StopReason.DISCONNECT && reason != StopReason.SHUTDOWN) {
                RecordableMod.LOGGER.info("Record-able initialization cancelled.");
            }
            return;
        }

        RecordableMod.LOGGER.info(
                "Recording stop requested: reason={} output={} capturedFrames={} droppedFrames={} failedCaptures={}",
                reason, output, capturedFrames.get(), skippedFrames.get(), failedCaptures.get());

        closeCaptureSafely(activeClient, captureToClose, reason);

        final FFmpegEncoder ffmpegFinal = ffmpegToStop;
        Thread stopper = new Thread(() -> finishStop(activeClient, ffmpegFinal, output, reason), "Record-able Stopper");
        stopper.setDaemon(true);
        stopper.start();

        if (reason != StopReason.DISCONNECT && reason != StopReason.SHUTDOWN) {
            RecordableMod.LOGGER.info("Record-able stopping and finalizing video.");
        }
    }

    public void onFrame() {
        // Drive the kill-montage rolling buffer (runs independently of recording state so a
        // kill clip can be captured even while a manual recording is also in progress).
        try {
            KillClipBuffer.getInstance().onRenderFrame();
        } catch (Throwable ignored) {
            // best effort; never let montage capture disrupt rendering
        }

        // Capture frames for standalone replay buffer (when not recording)
        captureFrameForStandaloneReplayBuffer();

        // Capture self-test runs here (render thread = valid GL context), regardless of
        // recording state, so the user can test capture health from the menu at any time.
        if (selfTestRequested) {
            selfTestRequested = false;
            selfTestResult = runCaptureSelfTest();
        }

        if (state != State.RECORDING) {
            return;
        }

        FFmpegEncoder activeFfmpeg;
        ScreenCapture activeCapture;
        boolean deferred;
        synchronized (lock) {
            if (state != State.RECORDING || screenCapture == null) {
                return;
            }
            deferred = deferredMode;
            // In deferred mode there is intentionally no realtime encoder; frames are
            // stored to disk instead. Only the normal path requires an encoder.
            if (!deferred && ffmpegEncoder == null) {
                long captured = capturedFrames.get();
                if (captured == 0 && onFrameNullEncoderLogCount < 3) {
                    onFrameNullEncoderLogCount++;
                    RecordableMod.LOGGER.error("onFrame: encoder is null while RECORDING! state={} screenCapture={}",
                            state, screenCapture != null);
                }
                return;
            }
            activeFfmpeg = ffmpegEncoder;
            activeCapture = screenCapture;
        }

        long now = System.nanoTime();
        if (!shouldCaptureFrame(now)) {
            return;
        }

        try {
            ScreenCapture.CapturedFrame frame = activeCapture.captureFrame();
            if (frame == null) {
                return;
            }

            // Use pause-corrected timestamp (subtract total paused time)
            long nowNs = System.nanoTime();
            long frameTimestampMs = startedAtNanos > 0L
                    ? (nowNs - startedAtNanos - totalPausedNanos) / 1_000_000L
                    : -1L;

            // Clean capture: no overlays or filters are baked into the recording.
            // Filters and HUD overlays only affect the live on-screen preview.
            byte[] frameData = frame.rgbPixels();
            // V1-0.08 Streamer Mode: censor sensitive screen regions in the saved
            // video (and replay buffer) when "Bake in Overlay" is ON. Applied in
            // place on the capture buffer.
            RecordableConfig cfg = RecordableConfig.get();
            if (StreamerModeManager.getInstance().isActive() && cfg != null && cfg.bakeInOverlay) {
                StreamerModeManager.getInstance().applyCensoring(frameData, frame.width(), frame.height());
            }
            if (deferred) {
                // Deferred (offline smooth-render) mode: persist frames to disk only.
                maybeStoreDeferredFrame(cfg, frame.width(), frame.height(), frameData, frameTimestampMs, now);
            } else {
                byte[] encoderData = frameData;

                boolean queued = activeFfmpeg.writeFrame(encoderData, frameTimestampMs) == FFmpegEncoder.EnqueueResult.QUEUED;

                // Feed replay buffer with unfiltered frame data
                if (ReplayBuffer.getInstance().isActive()) {
                    ReplayBuffer.getInstance().addFrame(frameData);
                }

                if (queued) {
                    long totalCaptured = capturedFrames.incrementAndGet();
                    long logInterval = Math.max(1L, (long) recordingFps * 10L);
                    if (totalCaptured % logInterval == 0L) {
                        RecordableMod.LOGGER.info(
                                "Frames captured: {} (encoderWritten={} encoderDropped={} queue={}/{} | adaptiveDropped={})",
                                totalCaptured,
                                activeFfmpeg.getWrittenFrames(),
                                activeFfmpeg.getDroppedFrames(),
                                activeFfmpeg.getQueueSize(),
                                activeFfmpeg.getQueueCapacity(),
                                adaptiveDroppedFrames.get());
                    }
                } else {
                    skippedFrames.incrementAndGet();
                    logBacklogIfNeeded(now);
                }
            }
        } catch (Throwable throwable) {
            failedCaptures.incrementAndGet();
            RecordableMod.LOGGER.warn("Record-able failed to capture a frame.", throwable);
        }
    }

    public void onClientTick(MinecraftClient client) {
        // Manage standalone replay buffer lifecycle (runs even when not recording)
        manageStandaloneReplayBuffer(client);

        if (state != State.RECORDING) {
            return;
        }

        RecordableConfig config = RecordableConfig.get();
        if (config.maxFileSizeMB > 0) {
            long maxBytes = config.maxFileSizeMB * 1024L * 1024L;
            long currentBytes = getCurrentFileSizeBytes();
            if (currentBytes >= maxBytes && currentBytes > 0L) {
                RecordableMod.LOGGER.info("File size limit reached: {} >= {} MB. Stopping recording.",
                        formatBytes(currentBytes), config.maxFileSizeMB);
                RecordableMod.sendClientMessage(ChatCategory.RECORDING, client,
                        "§eRecord-able auto-stopped: file size limit reached (" + config.maxFileSizeMB + " MB). "
                        + "Increase the limit in settings or set to 0 for unlimited.", false);
                stopRecording(client, StopReason.FILE_SIZE_LIMIT);
                return;
            }
        }

        samplePerformanceMetrics();

        evaluateAdaptiveCaptureFps(client, config);

        if (!performanceSuggestionSent && getDroppedFrames() >= 100L) {
            performanceSuggestionSent = true;
            RecordableMod.LOGGER.warn("Performance issue detected - droppedFrames={} queue={}/{}.",
                    getDroppedFrames(), getQueueSize(), getQueueCapacity());
            RecordableMod.sendClientMessage(ChatCategory.RECORDING, client,
                    "§eRecord-able is dropping frames. Try 720p/30fps or Quality=Performance for smoother recording.",
                    true);
        }
    }

    /**
     * Adaptive capture-fps governor. When enabled in config, it watches the live
     * game FPS roughly once per second and lowers the effective capture rate while
     * the game is below the configured target, then relaxes back toward the base
     * rate once the game recovers with some headroom. This trades a little capture
     * smoothness for real in-game FPS on low-end devices (Android/Pojav). Safe with
     * the CFR + frame-duplication encoder: skipped capture timestamps are backfilled
     * by duplicating the previous frame, so the output stays at the requested fps.
     */
    private void evaluateAdaptiveCaptureFps(MinecraftClient client, RecordableConfig config) {
        if (config == null || client == null) {
            return;
        }
        if (!config.perfOptimizerEnabled || !config.perfAutoAdjust || !config.perfActionLowerFps) {
            return;
        }

        long now = System.nanoTime();
        if (now - lastGovernorCheckNanos < 1_000_000_000L) {
            return; // Evaluate about once per second to avoid thrashing.
        }
        lastGovernorCheckNanos = now;

        int gameFps = client.getCurrentFps();
        if (gameFps <= 0) {
            return;
        }
        int minFps = Math.max(1, config.perfMinFps);
        final int FLOOR_FPS = 15; // Never drop capture below this.
        final int STEP = 5;       // Adjust in small steps for stability.

        synchronized (lock) {
            if (state != State.RECORDING || baseFrameIntervalNanos <= 0L) {
                return;
            }
            int baseFps = Math.max(1, (int) Math.round(1_000_000_000.0D / baseFrameIntervalNanos));
            if (governorCurrentFps <= 0) {
                governorCurrentFps = baseFps;
            }

            // Hysteresis / anti-thrash: the previous logic reacted to a single
            // one-second sample, so a game hovering near the target bounced the
            // capture rate up and down every second (e.g. 25->20->15->20->15).
            // That constantly changed the frame-duplication cadence in the CFR
            // writer, which shows up in the recording as uneven judder / a
            // "rubber-banding" stutter. We now require the game FPS to stay below
            // the target for a couple of checks before lowering, and to stay
            // recovered for several checks before raising, so the governor locks
            // onto one steady rate and the duplication cadence stays even.
            final int LOWER_STREAK = 2; // sustained low FPS before lowering
            final int RAISE_STREAK = 5; // longer sustained recovery before raising (anti-thrash)

            if (gameFps < minFps) {
                governorLowStreak++;
                governorHighStreak = 0;
            } else if (gameFps > minFps + STEP) {
                governorHighStreak++;
                governorLowStreak = 0;
            } else {
                // Neutral band around the target: hold the current rate steady and
                // decay both streaks so stale samples never trigger a change.
                governorLowStreak = 0;
                governorHighStreak = 0;
            }

            if (governorLowStreak >= LOWER_STREAK && governorCurrentFps > FLOOR_FPS) {
                // Game is persistently struggling: lower the capture rate a step.
                int newFps = Math.max(FLOOR_FPS, governorCurrentFps - STEP);
                if (newFps < governorCurrentFps) {
                    governorCurrentFps = newFps;
                    governorLowStreak = 0;
                    frameIntervalNanos = Math.max(1L, 1_000_000_000L / newFps);
                    RecordableMod.LOGGER.info(
                            "Adaptive capture-fps governor: game FPS {} < target {}, lowering capture to {} fps.",
                            gameFps, minFps, newFps);
                }
            } else if (governorHighStreak >= RAISE_STREAK && governorCurrentFps < baseFps) {
                // Game has recovered with headroom for a sustained period: relax
                // back toward the base rate one step.
                int newFps = Math.min(baseFps, governorCurrentFps + STEP);
                if (newFps > governorCurrentFps) {
                    governorCurrentFps = newFps;
                    governorHighStreak = 0;
                    frameIntervalNanos = Math.max(1L, 1_000_000_000L / newFps);
                    RecordableMod.LOGGER.info(
                            "Adaptive capture-fps governor: game FPS {} recovered, raising capture to {} fps.",
                            gameFps, newFps);
                }
            }
        }
    }

    /**
     * Manages the lifecycle of the standalone replay buffer. Starts it when enabled
     * and not recording, stops it when disabled or recording starts (to avoid double capture).
     */
    private void manageStandaloneReplayBuffer(MinecraftClient client) {
        RecordableConfig config = RecordableConfig.get();
        if (config == null || client == null || client.getWindow() == null) {
            return;
        }

        boolean shouldRun = config.replayBufferEnabled && state != State.RECORDING;
        boolean isRunning = ReplayBuffer.getInstance().isActive();

        if (shouldRun && !isRunning) {
            // Start standalone replay buffer
            try {
                int winW = client.getWindow().getFramebufferWidth();
                int winH = client.getWindow().getFramebufferHeight();
                // Use conservative settings for background capture: 720p max, 30fps
                int targetW = Math.min(winW, 1280);
                int targetH = Math.min(winH, 720);
                int fps = 30; // Lower fps for minimal performance impact
                
                synchronized (lock) {
                    if (replayBufferCapture == null) {
                        replayBufferCapture = new ScreenCapture(targetW, targetH);
                    }
                }
                
                ReplayBuffer.getInstance().start(targetW, targetH, fps, 
                        config.replayBufferDurationSeconds, config.replayBufferQuality);
                RecordableMod.LOGGER.info("Standalone replay buffer started: {}x{} @{}fps", 
                        targetW, targetH, fps);
            } catch (Exception e) {
                RecordableMod.LOGGER.warn("Failed to start standalone replay buffer", e);
            }
        } else if (!shouldRun && isRunning) {
            // Stop standalone replay buffer
            ReplayBuffer.getInstance().stop();
            synchronized (lock) {
                if (replayBufferCapture != null) {
                    try {
                        replayBufferCapture.close();
                    } catch (Exception ignored) {}
                    replayBufferCapture = null;
                }
            }
            RecordableMod.LOGGER.info("Standalone replay buffer stopped");
        }
    }

    /**
     * Captures a frame for the standalone replay buffer when not recording.
     * Throttled to match the buffer's target fps to minimize performance impact.
     */
    private void captureFrameForStandaloneReplayBuffer() {
        if (state == State.RECORDING) {
            return; // Recording flow handles replay buffer
        }

        if (!ReplayBuffer.getInstance().isActive()) {
            return;
        }

        ScreenCapture capture;
        synchronized (lock) {
            capture = replayBufferCapture;
        }

        if (capture == null) {
            return;
        }

        try {
            ScreenCapture.CapturedFrame frame = capture.captureFrame();
            if (frame != null && frame.rgbPixels() != null) {
                ReplayBuffer.getInstance().addFrame(frame.rgbPixels());
            }
        } catch (Throwable t) {
            // Silent fail - don't spam logs on capture errors
        }
    }

    public void shutdown() {
        try {
            MinecraftClient activeClient = resolveClient(null);
            if (activeClient != null && !activeClient.isOnThread()) {
                try {
                    activeClient.submitAndJoin(this::shutdown);
                    return;
                } catch (Throwable throwable) {
                    RecordableMod.LOGGER.warn("Failed to marshal shutdown onto client thread.", throwable);
                }
            }
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Could not resolve client during shutdown.", throwable);
        }
        doShutdown();
    }

    public void forceShutdown() {
        doShutdown();
    }

    private void doShutdown() {
        FFmpegEncoder ffmpegToStop;
        ScreenCapture captureToClose;
        ScreenCapture replayCapture;
        synchronized (lock) {
            if (state == State.IDLE) {
                return;
            }
            state = State.STOPPING;
            ffmpegToStop = ffmpegEncoder != null ? ffmpegEncoder : pendingStartFfmpegEncoder;
            captureToClose = screenCapture;
            replayCapture = replayBufferCapture;
            ffmpegEncoder = null;
            pendingStartFfmpegEncoder = null;
            screenCapture = null;
            replayBufferCapture = null;
            deferredMode = false;
            lastFrameCaptureNanos = 0L;
            frameIntervalNanos = 0L;
        }

        if (captureToClose != null) {
            try {
                captureToClose.close();
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.warn("Failed to close screen capture during shutdown.", throwable);
            }
        }
        if (replayCapture != null) {
            try {
                replayCapture.close();
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.warn("Failed to close replay buffer capture during shutdown.", throwable);
            }
        }
        if (ffmpegToStop != null) {
            try {
                Path finalized = ffmpegToStop.stop();
                if (finalized != null) {
                    outputFileFromStop(finalized);
                }
                // The encoder finalized cleanly during shutdown: drop the marker so
                // we do not offer to "recover" a file that is already complete.
                RecoveryManager.clear();
                finalEncoderDroppedFrames.addAndGet(ffmpegToStop.getDroppedFrames());
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.warn("Failed to stop FFmpeg encoder during shutdown.", throwable);
            }
        }

        synchronized (lock) {
            state = State.IDLE;
            startedAtNanos = 0L;
            lastFrameCaptureNanos = 0L;
            frameIntervalNanos = 0L;
            captureFpsEstimate = 0.0D;
            encoderFpsEstimate = 0.0D;
            performanceSuggestionSent = false;
        }
        
        // V1-0.09: notify HudHideManager that recording stopped (for clean recording features)
        HudHideManager.onRecordingStop();
    }

    private void finishStop(MinecraftClient client,
                            FFmpegEncoder ffmpegToStop,
                            Path output,
                            StopReason reason) {
        // Stop the replay buffer only if standalone mode is NOT enabled.
        // In standalone mode, the buffer should keep running in the background
        // to preserve the "history" for save-after-the-fact scenarios.
        RecordableConfig cfg = RecordableConfig.get();
        if (ReplayBuffer.getInstance().isActive() && (cfg == null || !cfg.replayBufferEnabled)) {
            ReplayBuffer.getInstance().stop();
        }
        Path finalizedOutput = output;
        long encoderWritten = 0L;
        if (ffmpegToStop != null) {
            Path encoderOutput = ffmpegToStop.stop();
            if (encoderOutput != null) {
                finalizedOutput = encoderOutput;
            }
            finalEncoderDroppedFrames.addAndGet(ffmpegToStop.getDroppedFrames());
            encoderWritten = ffmpegToStop.getWrittenFrames();
        }

        // Deferred (offline smooth-render) mode: finalize or cancel the frame session.
        // Capture this BEFORE finalizeDeferredSession() clears the session id, so we can
        // tell whether this stop belongs to a deferred (offline-render) recording.
        boolean wasDeferred = deferredSessionId != null && reason != StopReason.CANCEL;
        finalizeDeferredSession(reason);

        long fileSize = safeFileSize(finalizedOutput);
        long durationMillis;
        synchronized (lock) {
            durationMillis = startedAtNanos > 0L ? Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L) : 0L;
            startedAtNanos = 0L;
            state = State.IDLE;
            pendingStartFfmpegEncoder = null;
            currentOutputFile = finalizedOutput;
            lastOutputFile = finalizedOutput;
            lastFrameCaptureNanos = 0L;
            frameIntervalNanos = 0L;
            captureFpsEstimate = 0.0D;
            encoderFpsEstimate = 0.0D;
            performanceSuggestionSent = false;
        }

        // Cancelled recording: discard the output file and sidecars, no save toast.
        if (reason == StopReason.CANCEL) {
            RecoveryManager.clear();
            discardOutputFiles(finalizedOutput);
            final MinecraftClient cancelClient = client;
            runOnClient(client, () -> RecordableMod.sendClientMessage(
                    ChatCategory.RECORDING, cancelClient, "\u00a7eRecording cancelled.", false));
            RecordableMod.LOGGER.info("Recording cancelled and discarded: file={}", finalizedOutput);
            return;
        }

        // Save bookmarks to file alongside the video
        if (RecordableConfig.get().bookmarksEnabled) {
            saveBookmarks(finalizedOutput);
        }

        // The file is now finalized on disk: drop the crash-recovery marker.
        RecoveryManager.clear();

        // Deferred recordings are NOT rendered into a playable video at this point - only
        // the raw frames were captured. Showing the "saved / rename" prompt here would point
        // at a file that is not ready yet, and the confirmation would race far ahead of the
        // long offline render. The rename prompt and the "saved" toast are therefore deferred
        // until the offline render finishes (see RecordableModInit and RenderPromptScreen),
        // so the prompt only appears once the file is fully done and ready to be saved.
        if (wasDeferred) {
            RecordableMod.LOGGER.info(
                    "Deferred recording captured; rename/save prompt will appear after the offline render completes.");
            return;
        }

        if (reason == StopReason.DISCONNECT) {
            pendingJoinNotification = "Previous recording was saved: "
                    + (finalizedOutput == null ? "recording" : finalizedOutput.getFileName());
            RecordableMod.LOGGER.info("Recording saved (disconnected): {} ({}).", finalizedOutput, formatBytes(fileSize));
            return;
        }

        // Feature 1: prompt the user for a name after a normal manual save, and
        // also when the player LEAVEs a world/server (so they can name it right
        // from the menu). A leave always offers the prompt regardless of the
        // config toggle, since that is the explicitly requested behavior.
        final Path renameTarget = finalizedOutput;
        boolean promptRename = renameTarget != null
                && (reason == StopReason.LEAVE
                    || (reason == StopReason.MANUAL && RecordableConfig.get().promptRenameAfterRecording));

        if (promptRename) {
            // Instead of opening the rename screen immediately, keep the default
            // name and invite the player to press the "Name recording" key within
            // a short window to rename it. See getPendingRenameTarget().
            requestPendingRename(renameTarget, RENAME_PROMPT_WINDOW_MS);
            String keyDisplay = renameKeyDisplaySupplier.get();
            String savedName = renameTarget.getFileName().toString();
            boolean unbound = keyDisplay == null || keyDisplay.isBlank()
                    || keyDisplay.equalsIgnoreCase("Not Bound") || keyDisplay.equals("-");
            String toastMsg = unbound
                    ? "Recording saved! Bind a \"Name recording\" key in Controls to name it."
                    : "Recording saved! Want to name it? Press " + keyDisplay + " within 15s.";
            ToastQueue.push(toastMsg, RENAME_PROMPT_WINDOW_MS);
        } else if (reason != StopReason.SHUTDOWN && RecordableConfig.get().showPostRecordingToast) {
            // V1-0.09: shown as a custom themed toast (see ToastQueue / RecordingOverlay).
            Path messageOutput = finalizedOutput;
            String savedMsg = "Recording: " + (messageOutput == null ? "recording" : messageOutput.getFileName())
                    + " saved into recordings!";
            runOnClient(client, () -> RecordableMod.sendClientMessage(ChatCategory.RECORDING, client, savedMsg, false));
        }
        RecordableMod.LOGGER.info("Recording stopped: reason={} duration={} framesCaptured={} dropped={} encoderWritten={} file={} ({})",
                reason, formatDuration(durationMillis), capturedFrames.get(), getDroppedFrames(),
                encoderWritten, finalizedOutput, formatBytes(fileSize));
    }

    private void logBacklogIfNeeded(long nowNanos) {
        if (nowNanos - lastBacklogLogAtNanos < BACKLOG_LOG_INTERVAL_NANOS) {
            return;
        }
        lastBacklogLogAtNanos = nowNanos;
        int qSize = getQueueSize();
        int qCapacity = getQueueCapacity();
        double queueRatio = qCapacity <= 0 ? 0.0D : qSize / (double) qCapacity;
        RecordableMod.LOGGER.warn(
                "Encoder falling behind - dropping frames. Queue: {}/{} ({}%) | dropped={} (adaptive={})",
                qSize, qCapacity, Math.round(queueRatio * 100.0D),
                getDroppedFrames(), adaptiveDroppedFrames.get());
    }

    private void samplePerformanceMetrics() {
        FFmpegEncoder ff = ffmpegEncoder;
        if (ff == null) {
            return;
        }

        long now = System.nanoTime();
        if (lastPerformanceSampleAtNanos == 0L) {
            lastPerformanceSampleAtNanos = now;
            lastCapturedSample = capturedFrames.get();
            lastEncoderWrittenSample = ff.getWrittenFrames();
            return;
        }

        long elapsed = now - lastPerformanceSampleAtNanos;
        if (elapsed < PERFORMANCE_SAMPLE_INTERVAL_NANOS) {
            return;
        }

        long capturedNow = capturedFrames.get();
        long writtenNow = ff.getWrittenFrames();
        double elapsedSeconds = Math.max(0.001D, elapsed / 1_000_000_000.0D);

        captureFpsEstimate = Math.max(0.0D, (capturedNow - lastCapturedSample) / elapsedSeconds);
        encoderFpsEstimate = Math.max(0.0D, (writtenNow - lastEncoderWrittenSample) / elapsedSeconds);

        lastCapturedSample = capturedNow;
        lastEncoderWrittenSample = writtenNow;
        lastPerformanceSampleAtNanos = now;
    }

    // === Feature: name-recording prompt after a manual save ===
    // Window (ms) during which the "Name recording" key opens the rename screen.
    private static final long RENAME_PROMPT_WINDOW_MS = 15_000L;
    // The just-saved file awaiting an optional rename, and when the window expires.
    private volatile Path pendingRenameTarget = null;
    private volatile long pendingRenameExpiryMs = 0L;
    // Supplies the display name of the bound "Name recording" key for toast text.
    private static volatile java.util.function.Supplier<String> renameKeyDisplaySupplier = () -> "the assigned key";

    /** Sets the supplier used to show the bound "Name recording" key in toasts. */
    public static void setRenameKeyDisplaySupplier(java.util.function.Supplier<String> supplier) {
        if (supplier != null) renameKeyDisplaySupplier = supplier;
    }

    /** Duration (ms) of the post-recording rename window; reused by the deferred-render flow. */
    public long getRenamePromptWindowMs() {
        return RENAME_PROMPT_WINDOW_MS;
    }

    /**
     * Returns the display name of the bound "Name recording" key, or null if it is unbound.
     * Used to build the deferred-render "press KEY to name it" prompt once a render finishes.
     */
    public String getRenameKeyDisplayOrNull() {
        String keyDisplay = renameKeyDisplaySupplier.get();
        if (keyDisplay == null || keyDisplay.isBlank()
                || keyDisplay.equalsIgnoreCase("Not Bound") || keyDisplay.equals("-")) {
            return null;
        }
        return keyDisplay;
    }

    /** Opens a short window during which the rename key opens the rename screen. */
    public void requestPendingRename(Path target, long windowMs) {
        if (target == null) return;
        pendingRenameTarget = target;
        pendingRenameExpiryMs = System.currentTimeMillis() + windowMs;
    }

    /**
     * Returns the file awaiting rename while the prompt window is still open, else null.
     * Unlike {@link #consumePendingRename()} this does NOT clear active pending state.
     */
    public Path getPendingRenameTarget() {
        Path target = pendingRenameTarget;
        if (target == null) {
            return null;
        }
        if (System.currentTimeMillis() <= pendingRenameExpiryMs) {
            return target;
        }
        pendingRenameTarget = null;
        pendingRenameExpiryMs = 0L;
        return null;
    }

    /**
     * Returns the file awaiting rename if the window is still open, else null.
     * Always clears the pending state so the prompt fires at most once.
     */
    public Path consumePendingRename() {
        Path target = (pendingRenameTarget != null
                && System.currentTimeMillis() <= pendingRenameExpiryMs)
                ? pendingRenameTarget : null;
        pendingRenameTarget = null;
        pendingRenameExpiryMs = 0L;
        return target;
    }

    /**
     * True when a just-saved file is still within its rename window. Unlike
     * {@link #consumePendingRename()} this does NOT clear the pending state, so it
     * is safe to poll every tick (used to detect the rename key while a menu screen
     * is open, where game keybindings do not fire).
     */
    public boolean hasPendingRename() {
        return pendingRenameTarget != null
                && System.currentTimeMillis() <= pendingRenameExpiryMs;
    }

    private boolean shouldCaptureFrame(long nowNanos) {
        long interval = frameIntervalNanos > 0L
                ? frameIntervalNanos
                : Math.max(1L, 1_000_000_000L / Math.max(1, recordingFps));

        if (lastFrameCaptureNanos > 0L && nowNanos - lastFrameCaptureNanos < interval) {
            return false;
        }

        double queueRatio = getActiveQueueUsageRatio();
        if (queueRatio >= 0.90) {
            long total = capturedFrames.get() + skippedFrames.get();
            if (total % 4 != 0) {
                adaptiveDroppedFrames.incrementAndGet();
                return false;
            }
        } else if (queueRatio >= 0.70) {
            long total = capturedFrames.get() + skippedFrames.get();
            if (total % 2 != 0) {
                adaptiveDroppedFrames.incrementAndGet();
                return false;
            }
        }

        lastFrameCaptureNanos = nowNanos;
        return true;
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
            RecordableMod.LOGGER.warn("Failed to schedule Record-able {} on client thread.", actionDescription, throwable);
        }
    }

    private static void runOnClient(MinecraftClient client, Runnable runnable) {
        if (runnable == null) return;
        try {
            MinecraftClient activeClient = resolveClient(client);
            if (activeClient != null && !activeClient.isOnThread()) {
                activeClient.execute(runnable);
            } else {
                runnable.run();
            }
        } catch (Throwable throwable) {
            runnable.run();
        }
    }

    private void closeCaptureSafely(MinecraftClient client, ScreenCapture capture, StopReason reason) {
        if (capture == null) return;

        Runnable closeTask = () -> {
            try {
                capture.close();
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.warn("Failed to close screen capture (reason={}).", reason, throwable);
            }
        };

        MinecraftClient activeClient = resolveClient(client);
        if (activeClient != null && !activeClient.isOnThread()) {
            try {
                activeClient.execute(closeTask);
                return;
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.warn("Failed to schedule screen-capture close; closing on current thread.", throwable);
            }
        }
        closeTask.run();
    }

    public State getState() { return state; }
    public boolean isRecording() { return state == State.RECORDING; }
    public boolean isStopping() { return state == State.STOPPING || state == State.STARTING; }
    public boolean isPaused() { return state == State.PAUSED; }

    public boolean isActiveOrStopping() {
        return state == State.STARTING || state == State.RECORDING || state == State.PAUSED || state == State.STOPPING;
    }

    // === Capture diagnostics (Test Capture) ===================================

    /**
     * Requests a one-shot capture self-test. The actual capture happens on the
     * next render frame (where a valid GL context exists); poll
     * {@link #getCaptureSelfTestResult()} for the outcome.
     */
    public void requestCaptureSelfTest() {
        selfTestResult = null;
        selfTestRequested = true;
    }

    /** Latest self-test result, or {@code null} while one is still pending. */
    public CaptureDiagnostics.SelfTestResult getCaptureSelfTestResult() {
        return selfTestResult;
    }

    /**
     * Snapshot of the live capture statistics, or {@code null} when nothing is
     * recording. Safe to call from the render thread / UI.
     */
    public CaptureDiagnostics.LiveStats getLiveCaptureStats() {
        synchronized (lock) {
            ScreenCapture cap = screenCapture;
            if (cap == null) {
                return null;
            }
            return new CaptureDiagnostics.LiveStats(
                    cap.getTotalFramesProduced(),
                    cap.getTotalBlackFrames(),
                    cap.getConsecutiveBlackFrames(),
                    cap.isPersistentlyBlack(),
                    cap.getReadSourceName(),
                    cap.getSourceWidth(),
                    cap.getSourceHeight(),
                    cap.getRenderTargetWidth(),
                    cap.getRenderTargetHeight(),
                    cap.hasSizeMismatch());
        }
    }

    /**
     * Runs a one-shot synchronous capture on the render thread and validates the
     * result. Never throws; failures are folded into the returned result.
     */
    private CaptureDiagnostics.SelfTestResult runCaptureSelfTest() {
        ScreenCapture probe = null;
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.getWindow() == null) {
                return new CaptureDiagnostics.SelfTestResult(false, false, 0.0, 0, 0,
                        "No game window available.");
            }
            int winW = client.getWindow().getFramebufferWidth();
            int winH = client.getWindow().getFramebufferHeight();
            int[] size = scaledProbeSize(winW, winH);

            probe = new ScreenCapture(size[0], size[1]);
            ScreenCapture.CapturedFrame frame = probe.captureFrameSynchronous();
            if (frame == null || frame.rgbPixels() == null) {
                return new CaptureDiagnostics.SelfTestResult(false, false, 0.0, size[0], size[1],
                        "Synchronous capture returned no frame.");
            }
            byte[] rgb = frame.rgbPixels();
            int w = frame.width();
            int h = frame.height();
            double brightness = FrameValidator.averageBrightness(rgb, w, h);
            boolean black = FrameValidator.isBlackFrame(rgb, w, h);
            return new CaptureDiagnostics.SelfTestResult(true, black, brightness, w, h, null);
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("Capture self-test failed.", t);
            return new CaptureDiagnostics.SelfTestResult(false, false, 0.0, 0, 0,
                    "Self-test threw: " + t.getClass().getSimpleName());
        } finally {
            if (probe != null) {
                try {
                    probe.close();
                } catch (Throwable ignored) {
                    // best effort
                }
            }
        }
    }

    /** Scales a window size down to a small probe size (keeping aspect, min 2px). */
    private static int[] scaledProbeSize(int winW, int winH) {
        int maxW = 480;
        if (winW <= 0 || winH <= 0) {
            return new int[]{320, 180};
        }
        if (winW <= maxW) {
            return new int[]{Math.max(2, winW), Math.max(2, winH)};
        }
        double scale = (double) maxW / winW;
        return new int[]{maxW, Math.max(2, (int) Math.round(winH * scale))};
    }

    public String consumePendingJoinNotification() {
        String notification = pendingJoinNotification;
        pendingJoinNotification = null;
        return notification;
    }

    public long getElapsedMillis() {
        if (!isActiveOrStopping() || startedAtNanos == 0L) return 0L;
        return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
    }

    public long getCapturedFrames() { return capturedFrames.get(); }

    public long getDroppedFrames() {
        FFmpegEncoder ff = ffmpegEncoder;
        long encoderDrops = ff != null ? ff.getDroppedFrames() : finalEncoderDroppedFrames.get();
        return skippedFrames.get() + failedCaptures.get() + encoderDrops;
    }

    public long getAdaptiveDroppedFrames() { return adaptiveDroppedFrames.get(); }

    public QueueHealth getQueueHealth() {
        double ratio = getActiveQueueUsageRatio();
        if (ratio >= 0.90D) return QueueHealth.CRITICAL;
        if (ratio >= 0.50D) return QueueHealth.SLOW;
        return QueueHealth.OK;
    }

    public double getCaptureFpsEstimate() { return captureFpsEstimate; }

    public double getEncoderFpsEstimate() {
        FFmpegEncoder ff = ffmpegEncoder;
        double realtime = ff != null ? ff.getEstimatedEncoderFps() : 0.0D;
        return Math.max(encoderFpsEstimate, realtime);
    }

    public long getUsedMemoryMiB() {
        Runtime runtime = Runtime.getRuntime();
        long usedBytes = Math.max(0L, runtime.totalMemory() - runtime.freeMemory());
        return usedBytes / (1024L * 1024L);
    }

    public int getRecordingFps() { return recordingFps; }
    public int getRecordingWidth() { return recordingWidth; }
    public int getRecordingHeight() { return recordingHeight; }

    public int getQueueSize() {
        FFmpegEncoder ff = ffmpegEncoder;
        return ff != null ? ff.getQueueSize() : 0;
    }

    public int getQueueCapacity() {
        FFmpegEncoder ff = ffmpegEncoder;
        return ff != null ? ff.getQueueCapacity() : MAX_QUEUE_SIZE;
    }

    public long getCurrentFileSizeBytes() {
        FFmpegEncoder ff = ffmpegEncoder;
        long size = ff != null ? ff.getOutputFileSizeBytes() : 0L;
        if (size > 0L) return size;
        return safeFileSize(currentOutputFile == null ? lastOutputFile : currentOutputFile);
    }

    public EncoderType getActiveEncoderType() { return EncoderType.FFMPEG; }
    public Path getCurrentOutputFile() { return currentOutputFile == null ? lastOutputFile : currentOutputFile; }
    public Path getCurrentOutputDirectory() { return RecordableConfig.get().getOutputDirectory(); }

    // === Toast notification getters ===
    public String getPendingToastMessage() {
        if (pendingToastMessage != null && System.currentTimeMillis() > pendingToastExpiresAtMs) {
            pendingToastMessage = null;
            pendingToastFilePath = null;
        }
        return pendingToastMessage;
    }

    public Path getPendingToastFilePath() { return pendingToastFilePath; }

    public void dismissToast() {
        pendingToastMessage = null;
        pendingToastFilePath = null;
    }

    /** Estimate file size based on bitrate and effective recording time. */
    public String getEstimatedFileSize() {
        long effectiveMs = getEffectiveRecordingMillis();
        long currentSize = getCurrentFileSizeBytes();
        if (currentSize > 0 && effectiveMs > 2000) {
            // Extrapolate: current size is our best indicator
            return formatBytes(currentSize);
        }
        return "calculating...";
    }

    private double getActiveQueueUsageRatio() {
        FFmpegEncoder ff = ffmpegEncoder;
        return ff != null ? ff.getQueueUsageRatio() : 0.0D;
    }

    public static String formatDuration(long elapsedMillis) {
        long seconds = elapsedMillis / 1_000L;
        long hours = seconds / 3_600L;
        long minutes = (seconds % 3_600L) / 60L;
        long remainingSeconds = seconds % 60L;
        if (hours > 0L) return String.format("%d:%02d:%02d", hours, minutes, remainingSeconds);
        return String.format("%02d:%02d", minutes, remainingSeconds);
    }

    public static String formatBytes(long bytes) {
        if (bytes < 1024L) return bytes + " B";
        double kib = bytes / 1024.0D;
        if (kib < 1024.0D) return String.format("%.1f KiB", kib);
        double mib = kib / 1024.0D;
        if (mib < 1024.0D) return String.format("%.1f MiB", mib);
        return String.format("%.2f GiB", mib / 1024.0D);
    }

    private void outputFileFromStop(Path finalized) {
        synchronized (lock) {
            currentOutputFile = finalized;
            lastOutputFile = finalized;
        }
    }

    // === Recording Bookmarks API ===

    /**
     * Adds a bookmark at the current recording timestamp.
     * Only works while recording (not paused or idle).
     *
     * @return the bookmark description if added, or null if not recording
     */
    public String addBookmark() {
        if (state != State.RECORDING) {
            return null;
        }
        long timestampMs = getEffectiveRecordingMillis();
        bookmarkCounter++;
        String desc = "Bookmark " + bookmarkCounter;
        RecordingBookmark bookmark = new RecordingBookmark(timestampMs, desc);
        bookmarks.add(bookmark);
        RecordableMod.LOGGER.info("Bookmark added: {} at {}", desc, formatDuration(timestampMs));
        return desc;
    }

    /**
     * Saves all bookmarks to a .txt file alongside the video file.
     * Called automatically when recording stops.
     */
    private void saveBookmarks(Path videoFile) {
        if (bookmarks.isEmpty() || videoFile == null) {
            return;
        }
        try {
            String videoName = videoFile.getFileName().toString();
            int dotIndex = videoName.lastIndexOf('.');
            String baseName = dotIndex > 0 ? videoName.substring(0, dotIndex) : videoName;
            Path bookmarkFile = videoFile.getParent().resolve(baseName + "_bookmarks.txt");

            List<String> lines = new ArrayList<>();
            lines.add("Recording Bookmarks for: " + videoName);
            lines.add("Generated by Record-able");
            lines.add("");
            synchronized (bookmarks) {
                for (RecordingBookmark bm : bookmarks) {
                    lines.add(bm.toFileLine());
                }
            }

            Files.write(bookmarkFile, lines, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            RecordableMod.LOGGER.info("Saved {} bookmarks to {}", bookmarks.size(), bookmarkFile);
        } catch (Exception e) {
            RecordableMod.LOGGER.warn("Failed to save bookmarks file.", e);
        }
    }

    /** Returns an unmodifiable snapshot of the current bookmarks. */
    public List<RecordingBookmark> getBookmarks() {
        return List.copyOf(bookmarks);
    }

    /** Returns the number of bookmarks in the current recording session. */
    public int getBookmarkCount() {
        return bookmarks.size();
    }

    private static int makeEven(int value) { return value % 2 == 0 ? value : value - 1; }

    private static long safeFileSize(Path path) {
        if (path == null) return 0L;
        try { return Files.exists(path) ? Files.size(path) : 0L; }
        catch (IOException exception) { return 0L; }
    }
}
