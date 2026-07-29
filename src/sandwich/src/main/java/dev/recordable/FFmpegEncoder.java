package dev.recordable;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.StringJoiner;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.stream.Collectors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import net.minecraft.client.MinecraftClient;

/**
 * Asynchronous FFmpeg raw-video encoder.
 *
 * <p>The render thread only enqueues frame bytes. A writer thread drains the
 * bounded queue into FFmpeg's stdin, so short encoder stalls do not freeze the
 * game. If the queue fills, the current frame is dropped.</p>
 */
public final class FFmpegEncoder {
    public enum EnqueueResult {
        QUEUED,
        REJECTED
    }

    private static final int DEFAULT_QUEUE_CAPACITY = 120;
    /** Watchdog: minimum input frames sent before a no-output stall is considered real. */
    private static final int WATCHDOG_MIN_FRAMES = 1;
    /** Watchdog: how long FFmpeg may accept frames while producing zero output before abort. */
    private static final long WATCHDOG_TIMEOUT_MS = 6000L;
    private static final DateTimeFormatter FILE_TIMESTAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static volatile FfmpegStatus cachedStatus;
    private static volatile java.util.List<String> lastDiagnostics = new java.util.ArrayList<>();
    private static volatile long cachedStatusAtMs;

    private final RecordableConfig config;
    private final int width;
    private final int height;
    private final int fps;
    private final int frameByteSize;
    private final int queueCapacity;
    private final ArrayBlockingQueue<FramePacket> queue;
    private final AtomicBoolean acceptingFrames = new AtomicBoolean(false);
    private final AtomicLong droppedFrames = new AtomicLong();
    private final AtomicLong writtenFrames = new AtomicLong();
    private final String filePrefix;
    private final AtomicLong writerStartedAtNanos = new AtomicLong();
    /** Guards the one-time "high duplicate frame ratio" performance hint (bug #6). */
    private volatile boolean perfWarningLogged = false;
    /** True after stdin is closed during stop(), so final flush does not spuriously warn. */
    private volatile boolean stdinClosed = false;

    private Process process;
    private OutputStream ffmpegStdin;
    private Thread writerThread;
    private Thread stderrThread;
    private Thread watchdogThread;
    private Path outputFile;
    private String commandLine = "";
    private volatile String lastError = "";
    private volatile boolean audioEnabled = false;
    private volatile String audioDeviceInfo = "";
    private volatile long recordingStartNanos = 0L;
    private volatile long recordingStopNanos = 0L;

    private volatile long parsedSizeBytes = 0L;
    private volatile long parsedFrameCount = 0L;
    private volatile double parsedFps = 0.0;
    private volatile double parsedBitrate = 0.0;
    private volatile String parsedSpeed = "";
    /** Nano timestamp when audio recording stream was actually connected. */
    private volatile long audioRecordingStartNanos = 0L;
    /** Whether loopback audio connection was deferred until video is ready. */
    private volatile boolean deferredLoopbackConnection = false;

    private String ffmpegExecutable = "ffmpeg";
    private OpenALAudioCapture openAlCapture;
    private Thread audioWriterThread;
    private Path tempAudioFile;
    private volatile boolean audioWriterRunning;
    private Process ffmpegAudioProcess;
    private Thread ffmpegAudioStderrThread;
    private java.io.BufferedOutputStream loopbackAudioStream;
    private java.io.FileOutputStream loopbackFileStream;
    private volatile AudioCapture.AudioDeviceStatus detectedAudioStatus;
    // ---- Microphone (second, parallel audio input) ----
    private Process micCaptureProcess;
    private Thread micCaptureStderrThread;
    private Path tempMicFile;
    private AndroidMicrophoneCapture androidMicCapture;
    private volatile boolean micEnabled = false;
    private volatile long micRecordingStartNanos = 0L;
    // Wall-clock instant the mic FFmpeg process was launched (closest measurable point
    // to when the DirectShow/ALSA/AVFoundation device begins opening). Used as the
    // deterministic reference for the video->mic start gap, replacing the old post-sleep
    // timestamp that also folded in the variable device-detection time + a fixed 300ms
    // sanity sleep (the source of the 524-923ms micGap variance).
    private volatile long micProcessLaunchNanos = 0L;
    // Wall-clock instant the mic capture was told to stop (when 'q' is sent). Combined
    // with the captured WAV duration this yields a per-recording measurement of the
    // device warm-up dead time (see tryMuxCapturedAudio()).
    private volatile long micRecordingStopNanos = 0L;
    // Count of "Non-monotonic DTS" warnings emitted by the mic FFmpeg process. Buggy
    // DirectShow drivers (e.g. Razer Kraken V4 X) emit backward-jumping timestamps; a
    // high count signals the aresample/wallclock guards are being exercised heavily.
    private final java.util.concurrent.atomic.AtomicInteger micNonMonotonicDtsCount =
            new java.util.concurrent.atomic.AtomicInteger(0);
    private static final int NOISE_SUPPRESSION_NR_DB = 18;
    private static final int NOISE_SUPPRESSION_FLOOR_DB = -28;
    /** Upper bound applied to the dynamically-measured mic warm-up gap (ms). */
    private static final long MIC_WARMUP_MAX_MS = 500L;
    /** Non-monotonic DTS count above which a diagnostic sync warning is logged. */
    private static final int SYNC_DTS_WARN_COUNT = 10;
    /** Measured start-sync error above this (ms) is flagged as likely audible desync. */
    private static final long SYNC_DRIFT_WARN_MS = 250L;

    public FFmpegEncoder(RecordableConfig config, int width, int height, int fps) {
        this(config, width, height, fps, DEFAULT_QUEUE_CAPACITY, null);
    }

    public FFmpegEncoder(RecordableConfig config, int width, int height, int fps, int queueCapacity) {
        this(config, width, height, fps, queueCapacity, null);
    }

    public FFmpegEncoder(RecordableConfig config, int width, int height, int fps, int queueCapacity, String filePrefix) {
        this.config = config;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.frameByteSize = Math.max(1, width * height * 3);
        this.queueCapacity = Math.max(30, queueCapacity);
        this.queue = new ArrayBlockingQueue<>(this.queueCapacity);
        this.filePrefix = filePrefix != null && !filePrefix.isEmpty() ? filePrefix + "_" : "";
    }

    public Path start() throws IOException {
        if (!PlatformUtils.isRecordingSupported()) {
            throw new IOException("Recording is not supported on " + PlatformUtils.detectPlatform().displayName()
                    + ". " + PlatformUtils.getFfmpegInstallHint());
        }

        FfmpegStatus status = detectFfmpeg();
        if (!status.found()) {
            String hint = PlatformUtils.getFfmpegInstallHint();
            RecordableMod.LOGGER.error("FFmpeg not found on {}. {}", PlatformUtils.detectPlatform().displayName(), hint);
            throw new IOException(status.error().isBlank()
                    ? "FFmpeg was not found. " + hint
                    : status.error() + " - " + hint);
        }

        ffmpegExecutable = status.executable();

        Path outputDirectory = config.getOutputDirectory();
        Files.createDirectories(outputDirectory);
        
        // Auto-clips get organized into subfolders by event type
        if (filePrefix != null && filePrefix.startsWith("on-")) {
            // Auto-clip: create subfolder structure
            Path clipsDir = outputDirectory.resolve("clips");
            Path triggerDir = clipsDir.resolve(RecordableConfig.clipSubfolderForPrefix(filePrefix));
            Files.createDirectories(triggerDir);
            // Auto-clip: name it with the user's "Rename File Name" pattern too, kept inside the
            // event subfolder. De-duplicate within the subfolder if the pattern has no time token.
            String clipBase = RecordableConfig.resolveFilenamePattern(
                    RecordableConfig.defaultClipFilenamePatternForPrefix(filePrefix));
            String clipExt = "." + config.getFormat();
            Path clipCandidate = triggerDir.resolve(clipBase + clipExt);
            int clipDupIndex = 1;
            while (Files.exists(clipCandidate)) {
                clipCandidate = triggerDir.resolve(clipBase + "-" + clipDupIndex + clipExt);
                clipDupIndex++;
            }
            outputFile = clipCandidate;
        } else if (filePrefix == null || filePrefix.isEmpty()) {
            // Manual recording: name it using the user's "Rename File Name" pattern.
            String base = RecordableConfig.resolveFilenamePattern(config.filenamePattern);
            String ext = "." + config.getFormat();
            Path candidate = outputDirectory.resolve(base + ext);
            // A pattern with no time token can collide, so append -1, -2, ... until unique.
            int dupIndex = 1;
            while (Files.exists(candidate)) {
                candidate = outputDirectory.resolve(base + "-" + dupIndex + ext);
                dupIndex++;
            }
            outputFile = candidate;
        } else {
            // Replay or other named recording: keep the identifiable prefix.
            outputFile = outputDirectory.resolve("recordable-" + filePrefix + FILE_TIMESTAMP.format(LocalDateTime.now()) + "." + config.getFormat());
        }

        audioEnabled = false;
        audioDeviceInfo = "";
        tempAudioFile = null;
        detectedAudioStatus = null;
        micEnabled = false;
        tempMicFile = null;
        micRecordingStartNanos = 0L;
        micProcessLaunchNanos = 0L;
        micRecordingStopNanos = 0L;
        micNonMonotonicDtsCount.set(0);

        startFfmpegAudioCapture(outputDirectory);

        // Optional: arm Simple Voice Chat capture (no-op unless the mod is installed and enabled).
        VoiceChatIntegration.setRecording(true, outputDirectory);

        List<String> command = buildCommand(ffmpegExecutable, outputFile);
        commandLine = joinCommand(command);
        // Verbose startup diagnostics (full command + per-argument breakdown) are only
        // useful when debugging encoder issues, so keep them at DEBUG to avoid spamming
        // the normal log on every recording start.
        RecordableMod.LOGGER.debug("=== FFmpeg Encoder Configuration ===");
        RecordableMod.LOGGER.debug("Resolution: {}x{} | FPS: {} | Format: {} | Frame size: {} bytes",
                width, height, fps, config.getFormat(), frameByteSize);
        RecordableMod.LOGGER.debug("Queue capacity: {} | Output: {}", queueCapacity, outputFile);
        RecordableMod.LOGGER.debug("Complete FFmpeg command:");
        RecordableMod.LOGGER.debug("{}", commandLine);
        RecordableMod.LOGGER.debug("FFmpeg arguments breakdown:");
        for (int i = 0; i < command.size(); i++) {
            RecordableMod.LOGGER.debug("  [{}] = {}", i, command.get(i));
        }

        ProcessBuilder processBuilder = ffmpegProcess(command);
        processBuilder.directory(outputDirectory.toFile());
        processBuilder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (!RecordingManager.isInGameState(client)) {
                throw new IOException("Blocked FFmpeg start because client is not in-game.");
            }

            process = processBuilder.start();
            int pipeBuffer = Math.max(frameByteSize + 65536, 8 * 1024 * 1024);
            ffmpegStdin = new BufferedOutputStream(process.getOutputStream(), pipeBuffer);

            Thread.sleep(150);

            if (!process.isAlive()) {
                int exitCode = process.exitValue();
                stopAudioCapture();
                throw new IOException("FFmpeg exited immediately with code " + exitCode
                        + ". Command: " + commandLine
                        + (lastError.isEmpty() ? "" : ". Last error: " + lastError));
            }

            acceptingFrames.set(true);

            recordingStartNanos = System.nanoTime();
            writerStartedAtNanos.set(recordingStartNanos);

            // CRITICAL FIX: Connect loopback audio stream NOW, after video is ready.
            // This ensures audio and video start capturing at the same instant,
            // eliminating the 150-300ms audio lead that caused persistent desync.
            connectDeferredLoopbackAudio();

            // Start the microphone (second audio input) once recording is underway so the
            // HUD mic/PTT indicator lights up and the mic stream is captured for the mux.
            startMicrophoneCapture(outputDirectory);

            writerThread = new Thread(this::writerLoop, "Record-able FFmpeg Writer");
            writerThread.setDaemon(true);
            writerThread.setPriority(Math.min(Thread.MAX_PRIORITY - 1, Thread.NORM_PRIORITY + 1));
            writerThread.start();

            stderrThread = new Thread(() -> stderrLoop(process.getErrorStream()), "Record-able FFmpeg Log");
            stderrThread.setDaemon(true);
            stderrThread.start();

            // Runtime watchdog: catches an encoder that initialises but never produces
            // output (silent stall). Runs on its own thread because the writer thread
            // blocks inside ffmpegStdin.write() once the OS pipe buffer fills.
            watchdogThread = new Thread(this::watchdogLoop, "Record-able FFmpeg Watchdog");
            watchdogThread.setDaemon(true);
            watchdogThread.start();

            RecordableMod.LOGGER.info("FFmpeg process started successfully (pid={}). Waiting for frames...",
                    process.pid());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            stopAudioCapture();
            throw new IOException("Interrupted while waiting for FFmpeg to initialize.", exception);
        } catch (IOException exception) {
            stopAudioCapture();
            throw exception;
        }

        return outputFile;
    }

    public EnqueueResult writeFrame(ByteBuffer frameData) {
        if (frameData == null) {
            return EnqueueResult.REJECTED;
        }
        ByteBuffer duplicate = frameData.slice();
        byte[] copy = new byte[duplicate.remaining()];
        duplicate.get(copy);
        return writeFrame(copy, -1L);
    }

    public EnqueueResult writeFrame(byte[] frameData) {
        return writeFrame(frameData, -1L);
    }

    /**
     * Enqueues a frame with a wall-clock timestamp (ms since recording started).
     *
     * <p>When the queue is full, the frame is dropped but the timestamp gap is
     * remembered. The writer loop uses timestamps to duplicate the most recent
     * frame and fill gaps, keeping video duration in sync with real elapsed time.</p>
     */
    public EnqueueResult writeFrame(byte[] frameData, long frameTimestampMs) {
        if (!acceptingFrames.get() || frameData == null || frameData.length != frameByteSize) {
            if (frameData != null && frameData.length != frameByteSize) {
                RecordableMod.LOGGER.warn("Frame size mismatch: expected {} bytes, got {} bytes. Frame rejected.",
                        frameByteSize, frameData.length);
            }
            return EnqueueResult.REJECTED;
        }

        long ts = frameTimestampMs;
        if (ts < 0 && recordingStartNanos > 0L) {
            ts = (System.nanoTime() - recordingStartNanos) / 1_000_000L;
        }

        FramePacket packet = new FramePacket(frameData, ts);
        if (queue.offer(packet)) {
            long totalWritten = writtenFrames.get();
            if (totalWritten == 0 && queue.size() == 1) {
                RecordableMod.LOGGER.info("First frame enqueued! Size: {} bytes, expected: {} bytes. FFmpeg alive: {}",
                        frameData.length, frameByteSize, process != null && process.isAlive());
            }
            return EnqueueResult.QUEUED;
        }

        droppedFrames.incrementAndGet();
        return EnqueueResult.REJECTED;
    }

    public Path stop() {
        acceptingFrames.set(false);
        recordingStopNanos = System.nanoTime();

        if (writerThread != null) {
            try {
                writerThread.join(15_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        closeStdinQuietly();

        if (process != null) {
            try {
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    RecordableMod.LOGGER.warn("FFmpeg did not exit after stdin closed; destroying process.");
                    process.destroy();
                    if (!process.waitFor(3, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                }
                int exitCode = process.exitValue();
                if (exitCode != 0) {
                    RecordableMod.LOGGER.warn("FFmpeg exited with code {}. Last message: {}", exitCode, lastError);
                    String userMsg = String.format("\u00a7cFFmpeg encoding failed (exit code %d). Check logs for details.", exitCode);
                    if (lastError != null && !lastError.isBlank()) {
                        userMsg += String.format(" Last error: %s", lastError.substring(0, Math.min(100, lastError.length())));
                    }
                    RecordableMod.sendClientMessage(ChatCategory.RECORDING, null, userMsg, false);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                process.destroyForcibly();
            } finally {
                closeProcessStreamsQuietly();
            }
        }

        if (stderrThread != null) {
            try {
                stderrThread.join(1_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }

        stopAudioCapture();

        if (outputFile == null) {
            deleteTempAudioFileQuietly();
            return null;
        }

        try {
            if (!Files.exists(outputFile) || Files.size(outputFile) <= 0L) {
                RecordableMod.LOGGER.warn("FFmpeg output file was not finalized correctly (0 bytes): {}", outputFile);
                RecordableMod.sendClientMessage(ChatCategory.RECORDING, null, 
                        "\u00a7cRecording failed: output file is empty. Check that FFmpeg is working correctly.", false);
                // Delete the 0-byte file so it doesn't confuse users
                try {
                    if (Files.exists(outputFile)) {
                        Files.delete(outputFile);
                        RecordableMod.LOGGER.info("Deleted empty output file: {}", outputFile);
                    }
                } catch (IOException deleteEx) {
                    RecordableMod.LOGGER.warn("Failed to delete empty output file: {}", outputFile, deleteEx);
                }
                // Clear outputFile so we return null
                outputFile = null;
                deleteTempAudioFileQuietly();
                return null;
            }
        } catch (IOException exception) {
            RecordableMod.LOGGER.warn("Failed to verify FFmpeg output file: {}", outputFile, exception);
        }

        tryMuxCapturedAudio();
        tryOverlayVoiceChat();
        tryNormalizeContainerTimestamps();
        deleteTempAudioFileQuietly();

        // Log recording completion summary
        try {
            long fileSize = Files.exists(outputFile) ? Files.size(outputFile) : 0L;
            long totalFrames = writtenFrames.get();
            long dropped = droppedFrames.get();
            long durationMs = recordingStartNanos > 0
                    ? (System.nanoTime() - recordingStartNanos) / 1_000_000L : 0L;
            RecordableMod.LOGGER.info("=== Recording Complete ===");
            RecordableMod.LOGGER.info("Output: {} ({} bytes, {} MB)", outputFile.getFileName(),
                    fileSize, String.format("%.1f", fileSize / (1024.0 * 1024.0)));
            RecordableMod.LOGGER.info("Frames: {} written, {} dropped | Duration: {}s | Audio: {}",
                    totalFrames, dropped, durationMs / 1000L,
                    audioEnabled ? "yes" : "no");
        } catch (Exception logEx) {
            RecordableMod.LOGGER.debug("Could not log recording summary", logEx);
        }

        // Copy to Android gallery folder if enabled (so videos appear in the gallery app automatically)
        if (PlatformUtils.isAndroid() && RecordableConfig.get().saveToGalleryOnAndroid) {
            PlatformUtils.copyToAndroidGallery(outputFile);
        }

        // Auto-compress on Android if enabled. Runs in a background daemon thread so it
        // never blocks the recording-stop path. The compressed copy is also added to the
        // gallery when gallery saving is enabled.
        if (PlatformUtils.isAndroid() && RecordableConfig.get().autoCompressOnAndroid) {
            final java.nio.file.Path toCompress = outputFile;
            Thread compressThread = new Thread(() -> {
                java.nio.file.Path compressed = PlatformUtils.compressVideoForMobile(toCompress);
                if (compressed != null && RecordableConfig.get().saveToGalleryOnAndroid) {
                    PlatformUtils.copyToAndroidGallery(compressed);
                }
            }, "recordable-compress");
            compressThread.setDaemon(true);
            compressThread.start();
        }

        return outputFile;
    }

    public int getQueueSize() {
        return queue.size();
    }

    public int getQueueCapacity() {
        return queueCapacity;
    }

    public double getQueueUsageRatio() {
        return queueCapacity <= 0 ? 0.0D : queue.size() / (double) queueCapacity;
    }

    public boolean isBacklogged() {
        return getQueueUsageRatio() >= 0.90D;
    }

    public long getDroppedFrames() {
        return droppedFrames.get();
    }

    public long getWrittenFrames() {
        return writtenFrames.get();
    }

    public double getEstimatedEncoderFps() {
        long started = writerStartedAtNanos.get();
        if (started <= 0L) {
            return 0.0D;
        }
        double elapsedSeconds = Math.max(0.001D, (System.nanoTime() - started) / 1_000_000_000.0D);
        return writtenFrames.get() / elapsedSeconds;
    }

    public Path getOutputFile() {
        return outputFile;
    }

    public String getCommandLine() {
        return commandLine;
    }

    /**
     * Returns the current output file size in bytes.
     *
     * <p>Inspired by OBS Studio's {@code total_bytes} tracking: we first check the
     * real-time size parsed from FFmpeg's stderr progress output (which is always
     * up-to-date even when the OS file system buffers haven't flushed). Falls back
     * to filesystem size if no progress has been parsed yet.</p>
     */
    public long getOutputFileSizeBytes() {
        long parsed = parsedSizeBytes;
        if (parsed > 0L) {
            return parsed;
        }
        if (outputFile == null) {
            return 0L;
        }
        try {
            return Files.exists(outputFile) ? Files.size(outputFile) : 0L;
        } catch (IOException exception) {
            return 0L;
        }
    }

    /** Returns real-time encoding FPS as reported by FFmpeg's progress output. */
    public double getParsedFps() {
        return parsedFps;
    }

    /** Returns real-time encoding bitrate (kbits/s) as reported by FFmpeg. */
    public double getParsedBitrate() {
        return parsedBitrate;
    }

    /** Returns FFmpeg's speed factor (e.g., "1.2x"). */
    public String getParsedSpeed() {
        return parsedSpeed;
    }

    /**
     * Builds a ProcessBuilder for an ffmpeg/ffprobe invocation, transparently
     * wrapping the command for the execution method that works on this device
     * (e.g. launching via /system/bin/linker64 on Android where a direct exec of
     * an app-storage binary is blocked by SELinux). On desktop this is a no-op.
     */
    private boolean startAndroidMicrophoneCapture(Path outputDirectory) {
        try {
            int sampleRate = config.audioSampleRate > 0 ? config.audioSampleRate : 48000;
            int channels = config.audioChannelCount == 1 ? 1 : 2;
            tempMicFile = outputDirectory.resolve(
                    "recordable-mic-" + FILE_TIMESTAMP.format(LocalDateTime.now()) + ".wav");

            androidMicCapture = AndroidMicrophoneCapture.start(tempMicFile, sampleRate, channels);
            if (androidMicCapture == null) {
                RecordableMod.LOGGER.warn("Android microphone capture could not be initialized.");
                tempMicFile = null;
                notifyMicWarning("Microphone: Android capture failed to initialize. Recording continues without mic.");
                return false;
            }

            micEnabled = true;
            micRecordingStartNanos = System.nanoTime();
            micProcessLaunchNanos = micRecordingStartNanos;
            micRecordingStopNanos = 0L;
            MicrophoneState.beginRecording(true, config.microphonePushToTalk, micRecordingStartNanos);
            audioDeviceInfo = audioDeviceInfo + " + Mic (Android Microphone)";
            RecordableMod.LOGGER.info("Android microphone capture started: {} -> {}", "Android Microphone", tempMicFile);
            return true;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to start Android microphone capture; continuing without mic.", throwable);
            androidMicCapture = null;
            tempMicFile = null;
            micEnabled = false;
            return false;
        }
    }

    private static ProcessBuilder ffmpegProcess(java.util.List<String> command) {
        java.util.List<String> wrapped = FfmpegBundleManager.wrapCommandForExec(command);
        ProcessBuilder pb = new ProcessBuilder(wrapped);
        if (command != null && !command.isEmpty()) {
            FfmpegBundleManager.applyExecEnv(pb, command.get(0));
        }
        return pb;
    }

    private static ProcessBuilder ffmpegProcess(String... command) {
        return ffmpegProcess(new java.util.ArrayList<>(java.util.Arrays.asList(command)));
    }

    private List<String> buildCommand(String executable, Path output) {
        ArrayList<String> args = new ArrayList<>();
        args.add(executable);
        args.add("-loglevel");
        args.add("info");
        args.add("-stats");  // Ensure progress output to stderr (for real-time size tracking)
        args.add("-y");

        args.add("-f");
        args.add("rawvideo");
        args.add("-pix_fmt");
        args.add("rgb24");
        args.add("-video_size");
        args.add(width + "x" + height);
        args.add("-framerate");
        args.add(Integer.toString(fps));
        args.add("-i");
        args.add("pipe:0");

        // Constant frame rate (CFR) mode: FFmpeg 5.1+ uses -fps_mode, older versions use -vsync.
        // The encoder duplicates frames in writerLoop() to match the target FPS, so this just
        // tells FFmpeg to trust the stream timing without trying to guess or drop frames.
        int ffmpegMajor = FfmpegBundleManager.getMajorVersion();
        if (ffmpegMajor >= 5) {
            // FFmpeg 5.1+ (June 2022): -fps_mode replaces the deprecated -vsync
            args.add("-fps_mode");
            args.add("cfr");
        } else {
            // FFmpeg <5.1 (or unknown/very old): use legacy -vsync 1 (CFR mode)
            args.add("-vsync");
            args.add("1");
        }
        args.add("-r");
        args.add(Integer.toString(fps));

        // V1-0.08: Lunar-style smooth motion (optional). Applies an FFmpeg
        // minterpolate filter so playback stays fluid even when the game
        // renders below the recording frame rate.
        String smoothFilter = SmoothMotion.buildFilter(config, fps);
        if (smoothFilter != null) {
            args.add("-vf");
            args.add(smoothFilter);
            RecordableMod.LOGGER.info("Smooth motion enabled: {}", smoothFilter);
        }

        RecordableConfig.VideoEncoder activeEncoder = resolveVideoEncoder();
        addVideoCodecArgs(args, activeEncoder);

        if (!audioEnabled) {
            args.add("-an");
        }

        if (config.maxFileSizeMB > 0) {
            args.add("-fs");
            args.add(Long.toString(config.maxFileSizeMB * 1024L * 1024L));
        }

        args.add(output.toAbsolutePath().toString());
        return args;
    }

    /**
     * Starts audio capture for the recording session.
     *
     * <p><b>Priority order:</b></p>
     * <ol>
     *   <li><b>OpenAL Loopback</b> (best): Intercepts Minecraft's audio engine directly
     *       via ALC_SOFT_loopback. Full-volume, zero-noise game audio.</li>
     *   <li><b>FFmpeg system capture</b> (fallback): DirectShow/PulseAudio loopback.</li>
     *   <li><b>OpenAL alcCapture</b> (last resort): Usually captures microphone.</li>
     * </ol>
     */
    private void startFfmpegAudioCapture(Path outputDirectory) {
        if (!config.captureAudio) {
            audioEnabled = false;
            audioDeviceInfo = "";
            return;
        }

        try {
            if (startLoopbackAudioCapture(outputDirectory)) {
                return;
            }

            RecordableMod.LOGGER.info("OpenAL loopback unavailable; trying system audio capture...");
            if (startFfmpegSystemAudioCapture(outputDirectory)) {
                return;
            }

            RecordableMod.LOGGER.info("System audio unavailable; trying OpenAL capture as last resort...");
            if (startOpenALAudioCapture(outputDirectory)) {
                return;
            }

            RecordableMod.LOGGER.warn("No audio capture method available. Recording video only.");
            audioEnabled = false;
            if (PlatformUtils.isAndroid()) {
                audioDeviceInfo = "No audio device found. Grant the microphone permission to your launcher and check the device microphone settings.";
            } else if (PlatformUtils.isWindows()) {
                audioDeviceInfo = "No audio device found. Enable Stereo Mix or install a virtual audio device.";
            } else {
                audioDeviceInfo = "No audio device found. Enable a system audio loopback/monitor source (e.g. PulseAudio/PipeWire monitor).";
            }
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Audio initialization failed unexpectedly; continuing in video-only mode.", throwable);
            stopAudioCapture();
            audioEnabled = false;
            audioDeviceInfo = "Audio init failed; recording video only.";
        }
    }

    /**
     * PRIMARY audio capture: uses the OpenAL loopback device to capture
     * Minecraft's mixed audio output directly at full digital quality.
     *
     * <p>The loopback device is set up by {@link dev.recordable.mixin.SoundEngineMixin}
     * when the game starts. This method simply connects the loopback's PCM output
     * to a WAV file for recording.</p>
     *
     * @return true if loopback capture was activated successfully
     */
    private boolean startLoopbackAudioCapture(Path outputDirectory) {
        try {
            OpenALLoopbackCapture loopback = OpenALLoopbackCapture.getInstance();
            if (!loopback.isActive()) {
                RecordableMod.LOGGER.info("OpenAL loopback not active, skipping loopback capture.");
                return false;
            }

            tempAudioFile = outputDirectory.resolve("recordable-" + filePrefix + "audio-" + FILE_TIMESTAMP.format(LocalDateTime.now()) + ".wav");

            java.io.FileOutputStream fos = new java.io.FileOutputStream(tempAudioFile.toFile());
            java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(fos, 131072);

            writeWavHeader(bos, OpenALLoopbackCapture.SAMPLE_RATE, OpenALLoopbackCapture.CHANNELS,
                    OpenALLoopbackCapture.BITS_PER_SAMPLE, 0);

            loopbackAudioStream = bos;
            loopbackFileStream = fos;

            // CRITICAL FIX: Do NOT connect the recording stream yet!
            // The stream will be connected in connectDeferredLoopbackAudio()
            // AFTER the video FFmpeg process is started and recordingStartNanos is set.
            // This prevents 150-300ms of audio from being captured before video starts.
            deferredLoopbackConnection = true;

            audioEnabled = true;
            audioDeviceInfo = "OpenAL Loopback (" + OpenALLoopbackCapture.SAMPLE_RATE + "Hz Stereo)";
            RecordableMod.LOGGER.info("OpenAL loopback audio capture PREPARED (deferred connection): {} -> {}", audioDeviceInfo, tempAudioFile);
            return true;
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("Failed to start loopback audio capture.", t);
            return false;
        }
    }

    /**
     * Starts OpenAL-based audio capture. Captures Minecraft's game audio directly
     * through LWJGL's OpenAL capture APIs and writes PCM data to a WAV file.
     *
     * @return true if OpenAL capture started successfully
     */
    private boolean startOpenALAudioCapture(Path outputDirectory) {
        try {
            int sampleRate = config.audioSampleRate > 0 ? config.audioSampleRate : 48000;
            int channels = config.audioChannelCount == 1 ? 1 : 2;

            openAlCapture = new OpenALAudioCapture(sampleRate, channels);
            if (!openAlCapture.start()) {
                RecordableMod.LOGGER.warn("OpenAL capture device could not be opened.");
                openAlCapture = null;
                return false;
            }

            tempAudioFile = outputDirectory.resolve("recordable-" + filePrefix + "audio-" + FILE_TIMESTAMP.format(LocalDateTime.now()) + ".wav");

            audioWriterRunning = true;
            audioWriterThread = new Thread(() -> openAlAudioWriterLoop(sampleRate, channels), "Record-able OpenAL Audio Writer");
            audioWriterThread.setDaemon(true);
            audioWriterThread.start();

            audioEnabled = true;
            audioDeviceInfo = "OpenAL Game Audio (" + sampleRate + "Hz " + (channels == 2 ? "Stereo" : "Mono") + ")";
            RecordableMod.LOGGER.info("OpenAL audio capture started: {} -> {}", audioDeviceInfo, tempAudioFile);
            return true;
        } catch (Throwable throwable) {
            RecordableMod.LOGGER.warn("Failed to start OpenAL audio capture.", throwable);
            if (openAlCapture != null) {
                try { openAlCapture.stop(); } catch (Throwable ignored) {}
                openAlCapture = null;
            }
            return false;
        }
    }

    /**
     * Background loop that reads audio frames from OpenAL and writes them to a WAV file.
     * The WAV header is written first with placeholder sizes, then updated at the end.
     */
    private void openAlAudioWriterLoop(int sampleRate, int channels) {
        int bitsPerSample = 16;
        long totalDataBytes = 0;

        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempAudioFile.toFile());
             java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(fos, 65536)) {

            writeWavHeader(bos, sampleRate, channels, bitsPerSample, 0);

            while (audioWriterRunning || (openAlCapture != null && openAlCapture.getQueueSize() > 0)) {
                byte[] audioFrame = null;
                if (openAlCapture != null) {
                    try {
                        audioFrame = openAlCapture.getNextAudioFrame(50);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                if (audioFrame != null && audioFrame.length > 0) {
                    bos.write(audioFrame);
                    totalDataBytes += audioFrame.length;
                }
            }

            bos.flush();

            try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(tempAudioFile.toFile(), "rw")) {
                updateWavHeaderSize(raf, totalDataBytes);
            }

            RecordableMod.LOGGER.info("OpenAL audio writer finished: {} bytes written to {}", totalDataBytes, tempAudioFile);
        } catch (IOException e) {
            RecordableMod.LOGGER.warn("Error writing OpenAL audio to WAV file.", e);
        }
    }

    /**
     * Writes a standard PCM WAV header.
     */
    private static void writeWavHeader(java.io.OutputStream out, int sampleRate, int channels, int bitsPerSample, long dataSize) throws IOException {
        int byteRate = sampleRate * channels * (bitsPerSample / 8);
        int blockAlign = channels * (bitsPerSample / 8);
        long chunkSize = 36 + dataSize;

        out.write("RIFF".getBytes(StandardCharsets.US_ASCII));
        writeLittleEndianInt(out, (int) chunkSize);
        out.write("WAVE".getBytes(StandardCharsets.US_ASCII));

        out.write("fmt ".getBytes(StandardCharsets.US_ASCII));
        writeLittleEndianInt(out, 16); // Sub-chunk size
        writeLittleEndianShort(out, (short) 1); // PCM format
        writeLittleEndianShort(out, (short) channels);
        writeLittleEndianInt(out, sampleRate);
        writeLittleEndianInt(out, byteRate);
        writeLittleEndianShort(out, (short) blockAlign);
        writeLittleEndianShort(out, (short) bitsPerSample);

        out.write("data".getBytes(StandardCharsets.US_ASCII));
        writeLittleEndianInt(out, (int) dataSize);
    }

    private static void writeLittleEndianInt(java.io.OutputStream out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void writeLittleEndianShort(java.io.OutputStream out, short value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
    }

    /**
     * Updates the WAV header with the actual data size after recording is complete.
     */
    private static void updateWavHeaderSize(java.io.RandomAccessFile raf, long dataSize) throws IOException {
        raf.seek(4);
        int chunkSize = (int) (36 + dataSize);
        raf.write(chunkSize & 0xFF);
        raf.write((chunkSize >> 8) & 0xFF);
        raf.write((chunkSize >> 16) & 0xFF);
        raf.write((chunkSize >> 24) & 0xFF);

        raf.seek(40);
        int dataSizeInt = (int) dataSize;
        raf.write(dataSizeInt & 0xFF);
        raf.write((dataSizeInt >> 8) & 0xFF);
        raf.write((dataSizeInt >> 16) & 0xFF);
        raf.write((dataSizeInt >> 24) & 0xFF);
    }

    /**
     * PRIMARY audio capture method (OBS-inspired approach).
     *
     * <p>Uses FFmpeg to capture system audio via DirectShow (Windows), PulseAudio (Linux),
     * or AVFoundation (macOS). This is the same fundamental approach OBS Studio uses:
     * capturing desktop/game audio through OS-level loopback devices like Stereo Mix,
     * PulseAudio monitor sources, or BlackHole.</p>
     *
     * @return true if system audio capture started successfully
     */
    private boolean startFfmpegSystemAudioCapture(Path outputDirectory) {
        try {
            AudioCapture.AudioDeviceStatus status = AudioCapture.detectAudioDevice(ffmpegExecutable, normalizeConfiguredAudioDevice(config.audioDevice));
            detectedAudioStatus = status;

            if (!status.available()) {
                RecordableMod.LOGGER.info("No system audio device available: {}.", status.message());
                audioDeviceInfo = status.message();
                return false;
            }

            tempAudioFile = outputDirectory.resolve("recordable-" + filePrefix + "audio-" + FILE_TIMESTAMP.format(LocalDateTime.now()) + ".wav");

            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegExecutable);
            cmd.add("-loglevel");
            cmd.add("warning");
            cmd.add("-y");
            cmd.addAll(status.ffmpegArgs());
            cmd.add("-af");
            // NOTE: do NOT apply a large fixed pre-gain (e.g. volume=20.0) or dynaudnorm here.
            // This fallback captures an OS analog loopback ("Stereo Mix" / PulseAudio monitor)
            // that is already at line level; a 20x boost slams it into hard clipping (earrape)
            // and dynaudnorm pumps the quiet noise floor into a constant roar. Band-limit gently
            // (kill subsonic rumble + ultrasonic hiss) and preserve natural dynamics. Users who
            // need more level use audioVolume / audioVolumeBoostDb, applied at mux time as a
            // predictable fixed gain.
            cmd.add("highpass=f=80,lowpass=f=18000");
            cmd.add("-c:a");
            cmd.add("pcm_s16le");
            cmd.add("-ar");
            cmd.add(Integer.toString(config.audioSampleRate));
            cmd.add("-ac");
            cmd.add(Integer.toString(config.audioChannelCount));
            cmd.add(tempAudioFile.toAbsolutePath().toString());

            RecordableMod.LOGGER.info("Starting FFmpeg system audio capture (primary): {}", joinCommand(cmd));
            ProcessBuilder pb = ffmpegProcess(cmd);
            pb.directory(outputDirectory.toFile());

            MinecraftClient client = MinecraftClient.getInstance();
            if (!RecordingManager.isInGameState(client)) {
                throw new IOException("Blocked FFmpeg audio start because client is not in-game.");
            }

            Process audioCaptureProcess = pb.start();
            this.ffmpegAudioProcess = audioCaptureProcess;

            Thread audioStderrThread = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(audioCaptureProcess.getErrorStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        RecordableMod.LOGGER.info("Audio FFmpeg: {}", line);
                    }
                } catch (IOException ignored) {}
            }, "Record-able Audio FFmpeg Log");
            audioStderrThread.setDaemon(true);
            audioStderrThread.start();
            this.ffmpegAudioStderrThread = audioStderrThread;

            audioEnabled = true;
            audioDeviceInfo = status.deviceName() + " (" + status.platform() + ")";
            RecordableMod.LOGGER.info("FFmpeg system audio capture started: device='{}' tempFile={}", status.deviceName(), tempAudioFile);
            return true;
        } catch (Exception exception) {
            RecordableMod.LOGGER.warn("Unable to start system audio capture.", exception);
            stopAudioCapture();
            audioEnabled = false;
            audioDeviceInfo = "Failed: " + exception.getMessage();
            return false;
        }
    }

    /**
     * Connects the deferred loopback audio recording stream.
     * Called after the video FFmpeg process is started and recordingStartNanos is set,
     * so that audio and video begin capturing at the same instant.
     */
    private void connectDeferredLoopbackAudio() {
        if (!deferredLoopbackConnection || loopbackAudioStream == null) {
            return;
        }
        try {
            OpenALLoopbackCapture loopback = OpenALLoopbackCapture.getInstance();
            if (loopback.isActive()) {
                audioRecordingStartNanos = System.nanoTime();
                loopback.setRecordingStream(loopbackAudioStream);
                deferredLoopbackConnection = false;
                long gapMs = (audioRecordingStartNanos - recordingStartNanos) / 1_000_000L;
                RecordableMod.LOGGER.info("Loopback audio stream connected ({}ms after video start). Audio and video are now synchronized.", gapMs);
            } else {
                RecordableMod.LOGGER.warn("Loopback became inactive before audio stream could be connected.");
                deferredLoopbackConnection = false;
            }
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("Failed to connect deferred loopback audio stream.", t);
            deferredLoopbackConnection = false;
        }
    }

    /**
     * Stops all audio capture (loopback, OpenAL, and/or FFmpeg).
     */
    private void stopAudioCapture() {
        // Close any open Push-to-Talk interval and mark mic capture as ended.
        try { MicrophoneState.endRecording(); } catch (Throwable ignored) {}

        try {
            OpenALLoopbackCapture loopback = OpenALLoopbackCapture.getInstance();
            loopback.setRecordingStream(null);
        } catch (Throwable ignored) {}

        if (loopbackAudioStream != null) {
            try {
                loopbackAudioStream.flush();
                loopbackAudioStream.close();
            } catch (Throwable ignored) {}
            loopbackAudioStream = null;
        }
        if (loopbackFileStream != null) {
            try {
                long fileSize = Files.size(tempAudioFile);
                long dataSize = fileSize - 44; // WAV header is 44 bytes
                if (dataSize > 0) {
                    try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(tempAudioFile.toFile(), "rw")) {
                        updateWavHeaderSize(raf, dataSize);
                    }
                }
                loopbackFileStream.close();
            } catch (Throwable ignored) {}
            loopbackFileStream = null;
        }

        audioWriterRunning = false;
        if (openAlCapture != null) {
            try {
                openAlCapture.stop();
            } catch (Throwable ignored) {}
            openAlCapture = null;
        }

        if (audioWriterThread != null) {
            try {
                audioWriterThread.join(5_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            audioWriterThread = null;
        }

        if (ffmpegAudioProcess != null) {
            try {
                OutputStream audioStdin = ffmpegAudioProcess.getOutputStream();
                if (audioStdin != null) {
                    try {
                        audioStdin.write('q');
                        audioStdin.flush();
                        audioStdin.close();
                    } catch (IOException ignored) {}
                }

                if (!ffmpegAudioProcess.waitFor(8, TimeUnit.SECONDS)) {
                    RecordableMod.LOGGER.warn("Audio capture FFmpeg did not exit after 'q'; destroying.");
                    ffmpegAudioProcess.destroy();
                    if (!ffmpegAudioProcess.waitFor(3, TimeUnit.SECONDS)) {
                        ffmpegAudioProcess.destroyForcibly();
                    }
                }

                int exitCode = ffmpegAudioProcess.exitValue();
                if (exitCode != 0 && exitCode != 255) {
                    RecordableMod.LOGGER.warn("Audio capture FFmpeg exited with code {}", exitCode);
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                ffmpegAudioProcess.destroyForcibly();
            } finally {
                // The stdout stream is never read anywhere for this process (only stderr is
                // drained by audioStderrThread), so it must be closed explicitly here or the
                // file descriptor leaks every time system-audio capture is used.
                try {
                    ffmpegAudioProcess.getInputStream().close();
                } catch (Throwable ignored) {
                }
                ffmpegAudioProcess = null;
            }
        }

        if (androidMicCapture != null) {
            try {
                if (micRecordingStopNanos == 0L) {
                    micRecordingStopNanos = System.nanoTime();
                }
                androidMicCapture.stop();
            } catch (Throwable throwable) {
                RecordableMod.LOGGER.warn("Failed to stop Android microphone capture", throwable);
            } finally {
                androidMicCapture = null;
            }
        }

        if (micCaptureProcess != null) {
            try {
                // Mark the instant the mic capture is told to stop. Together with the WAV
                // duration this lets the muxer back out the variable device warm-up time.
                if (micRecordingStopNanos == 0L) {
                    micRecordingStopNanos = System.nanoTime();
                }
                OutputStream micStdin = micCaptureProcess.getOutputStream();
                if (micStdin != null) {
                    try {
                        micStdin.write('q');
                        micStdin.flush();
                        micStdin.close();
                    } catch (IOException ignored) {
                    }
                }
                if (!micCaptureProcess.waitFor(8, TimeUnit.SECONDS)) {
                    RecordableMod.LOGGER.warn("Microphone FFmpeg did not exit after 'q'; destroying.");
                    micCaptureProcess.destroy();
                    if (!micCaptureProcess.waitFor(3, TimeUnit.SECONDS)) {
                        micCaptureProcess.destroyForcibly();
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                micCaptureProcess.destroyForcibly();
            } finally {
                micCaptureProcess = null;
            }
        }

        if (micCaptureStderrThread != null) {
            try {
                micCaptureStderrThread.join(2_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            micCaptureStderrThread = null;
        }

        if (ffmpegAudioStderrThread != null) {
            try {
                ffmpegAudioStderrThread.join(2_000L);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            ffmpegAudioStderrThread = null;
        }
    }

    private void startMicrophoneCapture(Path outputDirectory) {
        if (!config.captureAudio) {
            return;
        }
        // Record the microphone only when the player actually wants it:
        //   - the "Capture microphone" setting is ON, OR
        //   - Simple Voice Chat is present and actively listening (enabled and not muted).
        // If the mic setting is OFF and Simple Voice Chat is muted / disabled / absent,
        // the microphone is never recorded.
        boolean micSettingOn = config.captureMicrophone;
        boolean svcListening = VoiceChatIntegration.isMicrophoneListening();
        if (!micSettingOn && !svcListening) {
            RecordableMod.LOGGER.info(
                    "Microphone capture skipped: mic setting is off and Simple Voice Chat is not listening "
                            + "(muted / disabled / absent). installed={}", VoiceChatIntegration.isAvailable());
            return;
        }
        if (PlatformUtils.isAndroid()) {
            if (startAndroidMicrophoneCapture(outputDirectory)) {
                return;
            }
        }
        try {
            // Build an ordered list of devices to try. The user's configured device is attempted
            // first; if it cannot be opened we fall back to the auto-detected default input so a
            // single bad / busy / mis-named device does not silently lose ALL microphone audio.
            java.util.List<AudioCapture.AudioDeviceStatus> candidates = new ArrayList<>();
            AudioCapture.AudioDeviceStatus primary =
                    AudioCapture.detectMicrophoneDevice(ffmpegExecutable, config.microphoneDevice);
            if (primary != null && primary.available() && !primary.ffmpegArgs().isEmpty()) {
                candidates.add(primary);
            } else {
                RecordableMod.LOGGER.info("Microphone capture: configured device '{}' unavailable: {}",
                        config.microphoneDevice, primary == null ? "null" : primary.message());
            }
            boolean configuredIsAuto = config.microphoneDevice == null
                    || config.microphoneDevice.isBlank()
                    || config.microphoneDevice.trim().equalsIgnoreCase("auto");
            if (!configuredIsAuto) {
                AudioCapture.AudioDeviceStatus auto =
                        AudioCapture.detectMicrophoneDevice(ffmpegExecutable, "auto");
                if (auto != null && auto.available() && !auto.ffmpegArgs().isEmpty()
                        && (primary == null || !auto.deviceName().equals(primary.deviceName()))) {
                    candidates.add(auto);
                }
            }
            if (candidates.isEmpty()) {
                RecordableMod.LOGGER.warn("Microphone capture requested but no usable input device was found.");
                notifyMicWarning("Microphone: no usable input device found. Recording continues without mic.");
                return;
            }

            tempMicFile = outputDirectory.resolve(
                    "recordable-mic-" + FILE_TIMESTAMP.format(LocalDateTime.now()) + ".wav");

            for (int attempt = 0; attempt < candidates.size(); attempt++) {
                AudioCapture.AudioDeviceStatus mic = candidates.get(attempt);

                List<String> cmd = new ArrayList<>();
                cmd.add(ffmpegExecutable);
                cmd.add("-loglevel");
                // 'info' (not 'warning') so the device-open line and any failure reason are logged.
                cmd.add("info");
                cmd.add("-y");
                // Larger input thread queue: live capture devices can briefly stall; this prevents
                // FFmpeg from dropping packets (and logging "thread queue blocking") on the mic input.
                cmd.add("-thread_queue_size");
                cmd.add("1024");
                cmd.addAll(mic.ffmpegArgs());
                cmd.add("-af");
                // Same conservative band-limiting as game-audio system capture: trims subsonic
                // rumble + ultrasonic hiss without dynamic processing (no noise pumping).
                StringBuilder micAf = new StringBuilder("highpass=f=80,lowpass=f=18000");
                if (config.noiseSuppression) {
                    // FFT-based denoiser (OBS-style background-noise removal). nr = noise
                    // reduction in dB, nf = noise floor; afftdn tracks the noise profile
                    // adaptively so steady background hiss/hum/fan noise is attenuated while
                    // speech is preserved.
                    micAf.append(",afftdn=nr=").append(NOISE_SUPPRESSION_NR_DB)
                            .append(":nf=").append(NOISE_SUPPRESSION_FLOOR_DB).append(":tn=1");
                    RecordableMod.LOGGER.info("Microphone noise suppression ENABLED (afftdn nr={}dB nf={}dB).",
                            NOISE_SUPPRESSION_NR_DB, NOISE_SUPPRESSION_FLOOR_DB);
                }
                // Resample the mic to a continuous, strictly-monotonic timeline. DirectShow
                // devices (e.g. Razer Kraken V4 X) emit backward-jumping DTS; FFmpeg otherwise
                // clamps them, which quietly accumulates drift over a long recording. async=1
                // pads timing gaps with silence and gently compresses overlaps so the written
                // WAV stays sample-accurate. first_pts=0 anchors the stream start at 0 so the
                // -itsoffset applied at mux time is the only thing positioning the audio.
                micAf.append(",aresample=async=1:min_hard_comp=0.100:first_pts=0");
                cmd.add(micAf.toString());
                cmd.add("-c:a");
                cmd.add("pcm_s16le");
                cmd.add("-ar");
                cmd.add(Integer.toString(config.audioSampleRate));
                cmd.add("-ac");
                cmd.add(Integer.toString(config.audioChannelCount));
                cmd.add(tempMicFile.toAbsolutePath().toString());

                RecordableMod.LOGGER.info("Starting microphone capture (attempt {}/{}, device='{}'): {}",
                        attempt + 1, candidates.size(), mic.deviceName(), joinCommand(cmd));

                ProcessBuilder pb = ffmpegProcess(cmd);
                pb.directory(outputDirectory.toFile());
                pb.redirectErrorStream(true);
                // Capture the launch instant as close as possible to the child process
                // spawning. This is the deterministic reference for the video->mic start gap.
                final long launchNanos = System.nanoTime();
                Process proc = pb.start();

                // Drain FFmpeg output into BOTH a small shared buffer and the log, so an immediate
                // failure (bad/busy device, unsupported format) is captured and visible.
                final StringBuilder micLog = new StringBuilder();
                Thread logThread = new Thread(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(proc.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            synchronized (micLog) {
                                if (micLog.length() < 4000) {
                                    micLog.append(line).append('\n');
                                }
                            }
                            // Track backward-jumping timestamps from buggy hardware drivers so
                            // the sync validator can report how hard the guards were working.
                            if (line.contains("on-monotonic")) {
                                micNonMonotonicDtsCount.incrementAndGet();
                            }
                            RecordableMod.LOGGER.info("[mic-ffmpeg] {}", line);
                        }
                    } catch (IOException ignored) {
                    }
                }, "Record-able Mic Log");
                logThread.setDaemon(true);
                logThread.start();

                // Give FFmpeg a moment to open the device. If it dies right away, the device could
                // not be opened with these args, so we report it and try the next candidate.
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }

                if (proc.isAlive()) {
                    this.micCaptureProcess = proc;
                    this.micCaptureStderrThread = logThread;
                    // Use the process-launch instant (not "now", which folds in the 300ms
                    // liveness sleep) as the recording epoch. This makes the video->mic gap a
                    // deterministic measurement and keeps the PTT gate aligned with it.
                    this.micProcessLaunchNanos = launchNanos;
                    this.micRecordingStartNanos = launchNanos;
                    this.micEnabled = true;

                    // Publish mic + Push-to-Talk state so the keybind tick handler can record held
                    // intervals and the HUD overlay can show the mic indicator.
                    MicrophoneState.beginRecording(true, config.microphonePushToTalk, this.micRecordingStartNanos);

                    audioDeviceInfo = audioDeviceInfo + " + Mic (" + mic.deviceName() + ")";
                    RecordableMod.LOGGER.info("Microphone capture started: {} -> {}", mic.deviceName(), tempMicFile);
                    return;
                }

                // Process exited immediately - log why and fall through to the next candidate.
                int code = -1;
                try {
                    code = proc.exitValue();
                } catch (Throwable ignored) {
                }
                String why;
                synchronized (micLog) {
                    why = micLog.toString().trim();
                }
                RecordableMod.LOGGER.warn("Microphone device '{}' failed to open (ffmpeg exit={}). Output:\n{}",
                        mic.deviceName(), code, why.isEmpty() ? "(no output)" : why);
                try {
                    logThread.join(500);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }

            // Every candidate failed to open.
            micEnabled = false;
            tempMicFile = null;
            notifyMicWarning("Microphone failed to start (see log). Recording continues without mic.");
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("Failed to start microphone capture; continuing without mic.", t);
            micEnabled = false;
            tempMicFile = null;
        }
    }

    /** Logs a microphone diagnostic warning. */
    private void notifyMicWarning(String message) {
        RecordableMod.LOGGER.warn("[mic] {}", message);
        // Surface as an on-screen toast instead of chat. ToastQueue is backed by a
        // CopyOnWriteArrayList, so it is safe to call from the encoder/writer threads.
        // A longer duration gives the player time to read the (often instructional) text.
        try {
            ToastQueue.push(message, 10_000L);
        } catch (Throwable ignored) {
        }
    }

    /**
     * Muxes captured audio (game-audio loopback and/or microphone) with the video-only
     * file into the final output. Game + mic are mixed with amix (PTT gating applied to
     * the mic); either source alone is mapped directly. Probes levels afterward.
     */
    /**
     * Overlays directly-captured Simple Voice Chat audio onto the finished recording.
     *
     * <p>This runs as an isolated second pass AFTER {@link #tryMuxCapturedAudio()} so it
     * never disturbs the finely-tuned primary audio/video sync. It runs whenever the
     * Simple Voice Chat mod is installed, the {@code includeVoiceChat} option is enabled,
     * and other players' voices were captured. Simple Voice Chat plays incoming voices
     * through its OWN audio output device, NOT through Minecraft's blaze3d OpenAL context,
     * so the OpenAL loopback never captures them - which is why they were previously
     * missing from finished recordings. The sidecar holds ONLY other players' voices (the
     * local mic is captured separately and excluded from the sidecar), so overlaying it
     * adds exactly the missing voices without doubling or echo, whether or not loopback
     * was active. Any failure leaves the primary recording untouched.</p>
     */
    private void tryOverlayVoiceChat() {
        Path sidecar;
        try {
            // Finalise the sidecar WAV (safe/no-op if capture was never armed).
            VoiceChatIntegration.setRecording(false, null);

            if (outputFile == null) return;
            if (!RecordableConfig.get().includeVoiceChat) { cleanupVoiceSidecar(); return; }
            if (!VoiceChatIntegration.hasCapturedData()) { cleanupVoiceSidecar(); return; }

            sidecar = VoiceChatIntegration.getSidecarFile();
            if (sidecar == null || !Files.exists(sidecar)) { cleanupVoiceSidecar(); return; }
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("[Record-able] Voice chat overlay setup failed: {}", t.toString());
            cleanupVoiceSidecar();
            return;
        }

        try {
            boolean hasExistingAudio = fileHasAudioStream(outputFile);

            String outputName = outputFile.getFileName().toString();
            int dotIndex = outputName.lastIndexOf('.');
            String baseName = dotIndex > 0 ? outputName.substring(0, dotIndex) : outputName;
            String ext = dotIndex > 0 ? outputName.substring(dotIndex) : ".mp4";
            Path overlaid = outputFile.resolveSibling(baseName + "-vc" + ext);

            double vcVol = Math.max(0, Math.min(200, RecordableConfig.get().gameAudioVolume)) / 100.0;

            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegExecutable);
            cmd.add("-nostdin");
            cmd.add("-loglevel");
            cmd.add("info");
            cmd.add("-y");
            cmd.add("-i");
            cmd.add(outputFile.toAbsolutePath().toString());
            cmd.add("-i");
            cmd.add(sidecar.toAbsolutePath().toString());
            cmd.add("-map");
            cmd.add("0:v:0");
            addAudioCodecArgs(cmd);
            if (hasExistingAudio) {
                cmd.add("-filter_complex");
                cmd.add(String.format(Locale.ROOT,
                        "[1:a]volume=%.2f[vc];[0:a][vc]amix=inputs=2:duration=first:dropout_transition=0:normalize=0[aout]", vcVol));
                cmd.add("-map");
                cmd.add("[aout]");
            } else {
                cmd.add("-filter_complex");
                cmd.add(String.format(Locale.ROOT, "[1:a]volume=%.2f[aout]", vcVol));
                cmd.add("-map");
                cmd.add("[aout]");
            }
            cmd.add("-shortest");
            cmd.add(overlaid.toAbsolutePath().toString());

            RecordableMod.LOGGER.info("[Record-able] Overlaying voice chat audio: {}", joinCommand(cmd));
            Process p = ffmpegProcess(cmd).redirectErrorStream(true).start();
            String out;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = reader.lines().collect(Collectors.joining("\n"));
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                RecordableMod.LOGGER.warn("[Record-able] Voice chat overlay timed out; keeping original recording.");
                deleteMuxedQuietly(overlaid);
                cleanupVoiceSidecar();
                return;
            }
            if (p.exitValue() != 0) {
                RecordableMod.LOGGER.warn("[Record-able] Voice chat overlay failed (exit {}): {}", p.exitValue(), out);
                deleteMuxedQuietly(overlaid);
                cleanupVoiceSidecar();
                return;
            }
            // Keep both files: the original (without voice chat) and the -vc version (with voice chat).
            // The -vc file is the final output with all audio sources mixed together.
            RecordableMod.LOGGER.info("[Record-able] Voice chat audio overlaid: {}", overlaid);
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("[Record-able] Voice chat overlay error; keeping original recording: {}", t.toString());
        } finally {
            cleanupVoiceSidecar();
        }
    }

    private void cleanupVoiceSidecar() {
        try {
            Path s = VoiceChatIntegration.getSidecarFile();
            if (s != null) Files.deleteIfExists(s);
        } catch (Throwable ignored) {
        }
        VoiceChatIntegration.clearSidecar();
    }

    /** Returns true if the given media file contains at least one audio stream. */
    private boolean fileHasAudioStream(Path file) {
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegExecutable);
            cmd.add("-nostdin");
            cmd.add("-i");
            cmd.add(file.toAbsolutePath().toString());
            Process p = ffmpegProcess(cmd).redirectErrorStream(true).start();
            String out;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = reader.lines().collect(Collectors.joining("\n"));
            }
            p.waitFor(10, TimeUnit.SECONDS);
            return out.contains("Audio:");
        } catch (Throwable t) {
            return true;
        }
    }

    private void tryMuxCapturedAudio() {
        if (outputFile == null) {
            return;
        }

        // Decide which sources actually produced usable data (more than a bare WAV header).
        boolean hasGame = false;
        try {
            hasGame = audioEnabled && tempAudioFile != null
                    && Files.exists(tempAudioFile) && Files.size(tempAudioFile) > 44L;
        } catch (IOException ignored) {
        }
        boolean hasMic = false;
        try {
            hasMic = micEnabled && tempMicFile != null
                    && Files.exists(tempMicFile) && Files.size(tempMicFile) > 44L;
        } catch (IOException ignored) {
        }

        // Diagnostic: the mic was requested/started but produced no usable audio. This is the
        // common "mic not recorded" symptom (wrong/unavailable device, or capture failed to
        // open it). Surface it clearly in the log so the cause is obvious.
        if (config.captureAudio
                && (config.captureMicrophone || VoiceChatIntegration.isMicrophoneListening())
                && !hasMic) {
            long micBytes = -1L;
            try {
                if (tempMicFile != null && Files.exists(tempMicFile)) {
                    micBytes = Files.size(tempMicFile);
                }
            } catch (IOException ignored) {
            }
            RecordableMod.LOGGER.warn(
                    "Microphone capture produced no usable audio (micEnabled={}, tempMicFile={}, bytes={}). "
                            + "Check the selected mic device in settings - 'auto' may have found no real input, "
                            + "or the chosen device could not be opened.",
                    micEnabled, tempMicFile, micBytes);
            notifyMicWarning("Microphone recorded no audio. Check the selected mic device in settings.");
        }

        // Diagnostic: the mic file exists and has data, but DirectShow/CoreAudio devices can open
        // successfully and stream PCM that is entirely SILENT (every sample == 0). This is the
        // "mic selected, capture 'works', but no voice in the recording" symptom and is almost
        // always a Windows/OS-side block rather than a mod bug. Measure the actual level here and,
        // if the captured mic audio is effectively silent, tell the user exactly how to fix it.
        if (hasMic) {
            double micMeanDb = probeMicMeanVolume(tempMicFile);
            if (!Double.isNaN(micMeanDb)) {
                RecordableMod.LOGGER.info("Microphone WAV level check: mean_volume={} dB (device='{}')",
                        micMeanDb, config.microphoneDevice);
                if (micMeanDb <= -75.0) {
                    if (PlatformUtils.isAndroid()) {
                        RecordableMod.LOGGER.warn(
                                "Microphone captured ONLY SILENCE (mean={} dB). The device '{}' opened and "
                                        + "FFmpeg recorded {} bytes, but every sample is silent - so there is nothing "
                                        + "to mix into the recording. This is an Android permission/OS-side block, NOT a mod bug. "
                                        + "Fix: (1) Android Settings > Apps > your launcher (e.g. PojavLauncher/Zalith) > "
                                        + "Permissions: turn ON the Microphone permission. (2) Make sure the device "
                                        + "microphone is not muted and no other app is holding it.",
                                micMeanDb, config.microphoneDevice, micFileBytesQuietly());
                        notifyMicWarning("Mic captured only silence. On Android: grant the Microphone permission to "
                                + "your launcher in Settings > Apps > Permissions, and make sure the mic is not muted.");
                    } else if (PlatformUtils.isWindows()) {
                        RecordableMod.LOGGER.warn(
                                "Microphone captured ONLY SILENCE (mean={} dB). The device '{}' opened and "
                                        + "FFmpeg recorded {} bytes, but every sample is silent - so there is nothing "
                                        + "to mix into the recording. This is a Windows/OS-side block, NOT a mod bug. "
                                        + "Fix: (1) Windows Settings > Privacy & security > Microphone: turn ON "
                                        + "'Microphone access' AND 'Let desktop apps access your microphone' (Java must "
                                        + "be allowed). (2) Sound settings > Recording > your mic > Properties > Levels: "
                                        + "unmute and raise to ~100%. (3) In Razer Synapse, ensure the mic is not muted "
                                        + "and no app holds it in Exclusive Mode.",
                                micMeanDb, config.microphoneDevice, micFileBytesQuietly());
                        notifyMicWarning("Mic captured only silence. In Windows: Privacy > Microphone, turn ON "
                                + "'Let desktop apps access your microphone', then unmute/raise the mic level in "
                                + "Sound settings (and in Razer Synapse).");
                    } else {
                        RecordableMod.LOGGER.warn(
                                "Microphone captured ONLY SILENCE (mean={} dB). The device '{}' opened and "
                                        + "FFmpeg recorded {} bytes, but every sample is silent - so there is nothing "
                                        + "to mix into the recording. This is an OS-side block, NOT a mod bug. "
                                        + "Fix: check your sound server (PulseAudio/PipeWire) input settings - unmute "
                                        + "the microphone source and raise its level, and make sure no other app holds "
                                        + "the device exclusively.",
                                micMeanDb, config.microphoneDevice, micFileBytesQuietly());
                        notifyMicWarning("Mic captured only silence. Check your PulseAudio/PipeWire input settings: "
                                + "unmute the microphone source and raise its level.");
                    }
                    // Prevent silent, delayed mic tracks from introducing perceived A/V timing issues.
                    hasMic = false;
                    micEnabled = false;
                    RecordableMod.LOGGER.warn("Dropping silent microphone track from mux (mean_volume={} dB).", micMeanDb);
                }
            }
        }

        if (!hasGame && !hasMic) {
            RecordableMod.LOGGER.warn("No usable audio captured (gameEnabled={}, micEnabled={}). Keeping video-only output.",
                    audioEnabled, micEnabled);
            return;
        }

        try {
            int userOffsetMs = RecordableConfig.get().getEffectiveAudioDelay();

            String outputName = outputFile.getFileName().toString();
            int dotIndex = outputName.lastIndexOf('.');
            String baseName = dotIndex > 0 ? outputName.substring(0, dotIndex) : outputName;
            String ext = dotIndex > 0 ? outputName.substring(dotIndex) : ".mp4";
            RecordableConfig.AudioEncoder selectedAudioEncoder = config.audioEncoder == null
                    ? RecordableConfig.AudioEncoder.AAC
                    : config.audioEncoder;
            Path finalOutputFile = outputFile;
            String muxExt = ext;
            if (PlatformUtils.isAndroid()
                    && ".mkv".equalsIgnoreCase(ext)
                    && selectedAudioEncoder == RecordableConfig.AudioEncoder.AAC) {
                // Many Android gallery players fail AAC-in-MKV playback. Write an MP4 output
                // for the final muxed file to maximize device compatibility.
                muxExt = ".mp4";
                finalOutputFile = outputFile.resolveSibling(baseName + muxExt);
                RecordableMod.LOGGER.info("Android compatibility: remux target switched from MKV to MP4 for AAC audio playback.");
            }
            Path muxedOutput = outputFile.resolveSibling(baseName + "-muxed" + muxExt);

            List<String> muxCommand = new ArrayList<>();
            muxCommand.add(ffmpegExecutable);
            muxCommand.add("-nostdin");
            muxCommand.add("-loglevel");
            muxCommand.add("info");
            muxCommand.add("-y");

            // Input 0: the finished video-only file.
            muxCommand.add("-i");
            muxCommand.add(outputFile.toAbsolutePath().toString());

            // Audio inputs are added in order; track their ffmpeg input indices.
            int nextInput = 1;
            int gameInputIdx = -1;
            int micInputIdx = -1;

            if (hasGame) {
                long startGapMs = 0;
                if (audioRecordingStartNanos > 0 && recordingStartNanos > 0) {
                    startGapMs = (audioRecordingStartNanos - recordingStartNanos) / 1_000_000L;
                }
                // The audio capture starts startGapMs AFTER video frame 0, which means the
                // captured loopback stream is already shifted late by the OpenAL buffer latency.
                // Empirically (legacy/sandwich), advancing the audio by startGap (-startGap) keeps
                // it in sync; delaying it (+startGap) produced a very noticeable lag. The user
                // delay knob is added on top.
                int gameTotalOffsetMs = userOffsetMs - (int) startGapMs;
                double offsetSec = gameTotalOffsetMs / 1000.0;
                RecordableMod.LOGGER.info("Game audio: {} bytes, itsoffset={}ms (user={}ms, startGap={}ms)",
                        Files.size(tempAudioFile), gameTotalOffsetMs, userOffsetMs, startGapMs);
                muxCommand.add("-itsoffset");
                muxCommand.add(String.format(Locale.ROOT, "%.3f", offsetSec));
                muxCommand.add("-i");
                muxCommand.add(tempAudioFile.toAbsolutePath().toString());
                gameInputIdx = nextInput++;
            }

            if (hasMic) {
                // The mic must be DELAYED by +micGap (leading silence) to line the first
                // captured sample up with the video moment it was spoken. The previous build
                // used a single wall-clock timestamp taken AFTER a 300ms liveness sleep, which
                // folded the (variable) device-detection time and that fixed sleep into the
                // offset - producing the 524-923ms swing that broke sync run-to-run.
                //
                // micGap now has two independently-measured parts:
                //   launchGap = mic process launch relative to video frame 0 (deterministic)
                //   warmup    = device dead-time before the first real sample was delivered,
                //               measured per-recording as (aliveWallMs - capturedAudioMs).
                // Because the capture stage resamples to first_pts=0, the WAV starts at the
                // first real sample, so the true video->firstSample gap is launchGap + warmup.
                long launchGapMs = 0L;
                long warmupMs = 0L;
                long refStartNanos = micProcessLaunchNanos > 0 ? micProcessLaunchNanos : micRecordingStartNanos;
                if (refStartNanos > 0 && recordingStartNanos > 0) {
                    launchGapMs = (refStartNanos - recordingStartNanos) / 1_000_000L;
                    long stopN = micRecordingStopNanos > 0 ? micRecordingStopNanos
                            : (recordingStopNanos > 0 ? recordingStopNanos : System.nanoTime());
                    long aliveMs = (stopN - refStartNanos) / 1_000_000L;
                    long capturedMs = wavDurationMs(tempMicFile);
                    if (capturedMs > 0 && aliveMs > 0) {
                        warmupMs = Math.max(0L, Math.min(MIC_WARMUP_MAX_MS, aliveMs - capturedMs));
                    }
                }
                long micGapMs = launchGapMs + warmupMs;
                if (micGapMs < 0) {
                    micGapMs = 0;
                }
                double micOffsetSec = (userOffsetMs + micGapMs) / 1000.0;
                RecordableMod.LOGGER.info(
                        "Microphone: {} bytes, itsoffset={}ms (user={}ms, launchGap={}ms, warmup={}ms, nonMonotonicDTS={})",
                        Files.size(tempMicFile), (int) (userOffsetMs + micGapMs), userOffsetMs,
                        launchGapMs, warmupMs, micNonMonotonicDtsCount.get());
                validateMicSync(launchGapMs, warmupMs);
                muxCommand.add("-itsoffset");
                muxCommand.add(String.format(Locale.ROOT, "%.3f", micOffsetSec));
                muxCommand.add("-i");
                muxCommand.add(tempMicFile.toAbsolutePath().toString());
                micInputIdx = nextInput++;
            }

            muxCommand.add("-map");
            muxCommand.add("0:v:0");
            muxCommand.add("-c:v");
            muxCommand.add("copy");
            addAudioCodecArgs(muxCommand);

            // Master volume/boost applied AFTER any per-source gain or mixing.
            String masterFilter = buildMasterVolumeFilter();

            if (hasGame && hasMic) {
                // Mix game audio + microphone using amix. normalize=0 keeps gains predictable.
                double gameVol = Math.max(0, Math.min(200, config.gameAudioVolume)) / 100.0;
                double micVol = Math.max(0, Math.min(200, config.microphoneVolume)) / 100.0;
                StringBuilder fc = new StringBuilder();
                fc.append(String.format(Locale.ROOT, "[%d:a]volume=%.2f[g];", gameInputIdx, gameVol));
                fc.append(String.format(Locale.ROOT, "[%d:a]%s[m];", micInputIdx, buildMicVolumeFilter(micVol)));
                if (config.separateAudioTracks) {
                    // V1-0.06 Feature 5: keep game audio and microphone as two DISCRETE
                    // tracks instead of mixing them. Backward-compatible: only taken when
                    // the user explicitly enables separate tracks.
                    String fcStr = fc.toString();
                    if (fcStr.endsWith(";")) {
                        fcStr = fcStr.substring(0, fcStr.length() - 1);
                    }
                    muxCommand.add("-filter_complex");
                    muxCommand.add(fcStr);
                    muxCommand.add("-map");
                    muxCommand.add("[g]");
                    muxCommand.add("-map");
                    muxCommand.add("[m]");
                    muxCommand.add("-metadata:s:a:0");
                    muxCommand.add("title=Game");
                    muxCommand.add("-metadata:s:a:1");
                    muxCommand.add("title=Microphone");
                    muxCommand.add("-disposition:a:0");
                    muxCommand.add("default");
                    RecordableMod.LOGGER.info("Writing SEPARATE audio tracks: game (track 0) + microphone (track 1)");
                } else {
                    fc.append("[g][m]amix=inputs=2:duration=longest:dropout_transition=0:normalize=0");
                    if (!masterFilter.isEmpty()) {
                        fc.append(",").append(masterFilter);
                    }
                    fc.append("[aout]");
                    muxCommand.add("-filter_complex");
                    muxCommand.add(fc.toString());
                    muxCommand.add("-map");
                    muxCommand.add("[aout]");
                    RecordableMod.LOGGER.info("Mixing game audio + microphone into single file: gameVol={} micVol={}", gameVol, micVol);
                }
            } else if (hasGame) {
                // Game audio only.
                muxCommand.add("-map");
                muxCommand.add(gameInputIdx + ":a:0");
                StringBuilder af = new StringBuilder(masterFilter);
                if (!(deferredLoopbackConnection || audioRecordingStartNanos > 0)) {
                    // Non-loopback (system capture): gentle async resampling to avoid drift.
                    if (af.length() > 0) {
                        af.append(",");
                    }
                    af.append("aresample=async=1000");
                }
                if (af.length() > 0) {
                    muxCommand.add("-af");
                    muxCommand.add(af.toString());
                }
                RecordableMod.LOGGER.info("Muxing game audio only into single file.");
            } else {
                // Microphone only (game-audio loopback unavailable). Still goes into the ONE file.
                double micVol = Math.max(0, Math.min(200, config.microphoneVolume)) / 100.0;
                StringBuilder fc = new StringBuilder();
                fc.append(String.format(Locale.ROOT, "[%d:a]%s", micInputIdx, buildMicVolumeFilter(micVol)));
                if (!masterFilter.isEmpty()) {
                    fc.append(",").append(masterFilter);
                }
                fc.append("[aout]");
                muxCommand.add("-filter_complex");
                muxCommand.add(fc.toString());
                muxCommand.add("-map");
                muxCommand.add("[aout]");
                RecordableMod.LOGGER.info("Muxing microphone only into single file (no game audio available): micVol={}", micVol);
            }

            muxCommand.add("-shortest");
            muxCommand.add(muxedOutput.toAbsolutePath().toString());

            RecordableMod.LOGGER.info("Muxing audio: {}", joinCommand(muxCommand));
            Process muxProcess = ffmpegProcess(muxCommand).redirectErrorStream(true).start();
            String muxOutput;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(muxProcess.getInputStream(), StandardCharsets.UTF_8))) {
                muxOutput = reader.lines().collect(Collectors.joining("\n"));
            }

            if (!muxProcess.waitFor(30, TimeUnit.SECONDS)) {
                muxProcess.destroyForcibly();
                RecordableMod.LOGGER.warn("Audio mux timed out. Keeping original video-only file.");
                // Delete the partial/orphaned "-muxed" file so it does not linger in the
                // recordings folder as a duplicate entry and waste storage.
                deleteMuxedQuietly(muxedOutput);
                return;
            }

            if (muxProcess.exitValue() != 0) {
                RecordableMod.LOGGER.warn("Audio mux failed with exit code {}: {}", muxProcess.exitValue(), muxOutput);
                // Mux failed; the original video-only file is intact, so discard the
                // incomplete "-muxed" intermediate to avoid a duplicate UI entry.
                deleteMuxedQuietly(muxedOutput);
                return;
            }

            // Replace the original video-only file with the muxed (audio+video) file.
            // On Windows another process (the just-exited video FFmpeg, a duration
            // prober, or antivirus) can briefly keep a handle open on either file, so
            // this rename can fail with "the process cannot access the file because it
            // is being used by another process". Retry a few times with short back-off.
            //
            // CRITICAL: the previous code fell back to a plain Files.move(REPLACE_EXISTING)
            // which on Windows deletes the target first and THEN renames the source - if
            // the rename then failed, the original was already gone and only the orphaned
            // "-muxed" file survived, producing a 0-byte recording. We must never lose the
            // recording: if every attempt fails, keep the muxed file (it is the complete
            // output) and point outputFile at it.
            boolean replaced = false;
            IOException lastMoveError = null;
            for (int attempt = 1; attempt <= 6 && !replaced; attempt++) {
                try {
                    try {
                        Files.move(muxedOutput, finalOutputFile,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException atomicMoveFailure) {
                        // Atomic rename unsupported/blocked - fall back to a plain replace.
                        Files.move(muxedOutput, finalOutputFile,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    replaced = true;
                } catch (IOException moveFailure) {
                    lastMoveError = moveFailure;
                    try {
                        Thread.sleep(300L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (replaced) {
                if (!finalOutputFile.equals(outputFile)) {
                    try {
                        Files.deleteIfExists(outputFile);
                    } catch (IOException ignored) {
                    }
                    outputFile = finalOutputFile;
                }
                RecordableMod.LOGGER.info("Audio mux completed successfully: {}", outputFile);
            } else if (Files.exists(muxedOutput) && Files.size(muxedOutput) > 0L) {
                // Could not overwrite the original (still locked). The muxed file is the
                // complete recording - keep it and make it the authoritative output so the
                // final file is never lost or reported as 0 bytes.
                RecordableMod.LOGGER.warn("Could not replace original with muxed file after retries "
                        + "(locked by another process). Keeping muxed file as the final recording: {}",
                        muxedOutput, lastMoveError);
                try {
                    Files.deleteIfExists(outputFile);
                } catch (IOException ignored) {
                    // Original may still be locked; leave it. The muxed file is authoritative.
                }
                outputFile = muxedOutput;
            } else {
                RecordableMod.LOGGER.warn("Audio mux move failed and muxed file is missing; "
                        + "keeping original video-only file.", lastMoveError);
            }

            probeAudioLevel();
        } catch (Exception exception) {
            RecordableMod.LOGGER.warn("Failed to mux audio into output.", exception);
        }
    }

    private String buildMasterVolumeFilter() {
        StringBuilder audioFilter = new StringBuilder();
        if (config.audioVolume != 100 && config.audioVolume >= 0 && config.audioVolume <= 200) {
            double volumeFactor = config.audioVolume / 100.0;
            audioFilter.append("volume=").append(String.format(Locale.ROOT, "%.2f", volumeFactor));
            RecordableMod.LOGGER.info("Applying user audio volume: {}x ({}%)", volumeFactor, config.audioVolume);
        }
        if (config.audioVolumeBoostDb > 0 && config.audioVolumeBoostDb <= 24) {
            if (audioFilter.length() > 0) {
                audioFilter.append(",");
            }
            audioFilter.append("volume=").append(config.audioVolumeBoostDb).append("dB");
            RecordableMod.LOGGER.info("Applying audio boost: +{} dB", config.audioVolumeBoostDb);
        }
        
        // Android OpenAL loopback captures audio at much lower levels than desktop.
        // Apply automatic gain normalization to bring recordings to audible levels.
        if (PlatformUtils.isAndroid()) {
            if (audioFilter.length() > 0) {
                audioFilter.append(",");
            }
            audioFilter.append("volume=18dB");
            RecordableMod.LOGGER.info("Android detected: applying automatic +18dB gain boost for OpenAL loopback audio");
        }
        
        return audioFilter.toString();
    }

    /**
     * Final MP4/MOV normalization pass that regenerates timeline metadata so strict muxers
     * do not complain about non-monotonic DTS from mixed capture sources.
     *
     * <p>This is stream-copy only (no re-encode), so quality is unchanged.</p>
     */
    private void tryNormalizeContainerTimestamps() {
        if (outputFile == null) {
            return;
        }
        String name = outputFile.getFileName().toString().toLowerCase(Locale.ROOT);
        if (!(name.endsWith(".mp4") || name.endsWith(".mov"))) {
            return;
        }
        String originalName = outputFile.getFileName().toString();
        int dot = originalName.lastIndexOf('.');
        String base = dot > 0 ? originalName.substring(0, dot) : originalName;
        String ext = dot > 0 ? originalName.substring(dot) : ".mp4";
        Path normalized = outputFile.resolveSibling(base + "-normalized" + ext);
        try {
            List<String> cmd = new ArrayList<>();
            cmd.add(ffmpegExecutable);
            cmd.add("-nostdin");
            cmd.add("-loglevel");
            cmd.add("warning");
            cmd.add("-y");
            cmd.add("-fflags");
            cmd.add("+genpts");
            cmd.add("-i");
            cmd.add(outputFile.toAbsolutePath().toString());
            cmd.add("-map");
            cmd.add("0");
            cmd.add("-c");
            cmd.add("copy");
            cmd.add("-movflags");
            cmd.add("+faststart");
            cmd.add("-avoid_negative_ts");
            cmd.add("make_zero");
            cmd.add(normalized.toAbsolutePath().toString());

            Process p = ffmpegProcess(cmd).redirectErrorStream(true).start();
            String out;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                out = reader.lines().collect(Collectors.joining("\n"));
            }
            if (!p.waitFor(30, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                deleteNormalizedQuietly(normalized);
                return;
            }
            if (p.exitValue() != 0 || !Files.exists(normalized) || Files.size(normalized) <= 0L) {
                RecordableMod.LOGGER.warn("Timestamp normalization skipped (ffmpeg exit={}): {}", p.exitValue(), out);
                deleteNormalizedQuietly(normalized);
                return;
            }

            boolean replaced = false;
            IOException lastMoveError = null;
            for (int attempt = 1; attempt <= 6 && !replaced; attempt++) {
                try {
                    try {
                        Files.move(normalized, outputFile,
                                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                                java.nio.file.StandardCopyOption.ATOMIC_MOVE);
                    } catch (IOException atomicMoveFailure) {
                        Files.move(normalized, outputFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                    }
                    replaced = true;
                } catch (IOException moveFailure) {
                    lastMoveError = moveFailure;
                    try {
                        Thread.sleep(250L);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
            if (!replaced) {
                RecordableMod.LOGGER.warn("Timestamp normalization could not replace original file.", lastMoveError);
                deleteNormalizedQuietly(normalized);
                return;
            }
            RecordableMod.LOGGER.info("Container timeline normalized: {}", outputFile);
        } catch (Exception e) {
            RecordableMod.LOGGER.warn("Failed to normalize container timestamps.", e);
            deleteNormalizedQuietly(normalized);
        }
    }

    private void deleteNormalizedQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private String buildMicVolumeFilter(double micVol) {
        int userOffsetMs = RecordableConfig.get().getEffectiveAudioDelay();
        java.util.List<double[]> pttIntervals = null;
        try {
            pttIntervals = MicrophoneState.getPushToTalkIntervalsSeconds(micRecordingStartNanos);
        } catch (Throwable ignored) {
        }

        if (pttIntervals == null) {
            // Always-on microphone (Push-to-Talk disabled).
            return String.format(Locale.ROOT, "volume=%.2f", micVol);
        }
        if (pttIntervals.isEmpty()) {
            // PTT enabled but key never held: mute the mic entirely.
            RecordableMod.LOGGER.info("Push-to-Talk: key never held during recording; microphone muted.");
            notifyMicWarning("Push-to-Talk was ON but the PTT key was never held, so the mic is silent. "
                    + "Hold the Push-to-Talk key while talking, or turn Push-to-Talk OFF for always-on mic.");
            return "volume=0";
        }
        // PTT key intervals are measured relative to the mic launch epoch
        // (micRecordingStartNanos == micProcessLaunchNanos). The -itsoffset applied to the
        // WAV at mux time is (userOffset + launchGap + warmup), while the WAV's own timeline
        // starts one warmup later than the launch epoch, so the warmup term cancels: the gate
        // window on the post-offset filter timeline is simply (interval + userOffset + launchGap).
        long pttMicGapMs = (micRecordingStartNanos > 0 && recordingStartNanos > 0)
                ? (micRecordingStartNanos - recordingStartNanos) / 1_000_000L : 0L;
        double pttMicOffsetSec = (userOffsetMs + pttMicGapMs) / 1000.0;
        StringBuilder gate = new StringBuilder();
        for (int i = 0; i < pttIntervals.size(); i++) {
            double[] iv = pttIntervals.get(i);
            double s = Math.max(0.0, iv[0] + pttMicOffsetSec);
            double e = Math.max(0.0, iv[1] + pttMicOffsetSec);
            if (i > 0) {
                gate.append("+");
            }
            gate.append(String.format(Locale.ROOT, "between(t,%.3f,%.3f)", s, e));
        }
        RecordableMod.LOGGER.info("Push-to-Talk: gating microphone to {} held interval(s).", pttIntervals.size());
        return String.format(Locale.ROOT, "volume=volume='%.2f*(%s)':eval=frame", micVol, gate);
    }

    /**
     * Estimates the duration (ms) of a PCM {@code pcm_s16le} WAV file from its size.
     * Uses the configured sample rate / channel count (2 bytes per sample) rather than
     * parsing the header, which is sufficient for the warm-up estimate. Returns -1 if the
     * file is missing or the parameters are unusable.
     */
    private long wavDurationMs(Path wav) {
        try {
            if (wav == null || !Files.exists(wav)) {
                return -1L;
            }
            long size = Files.size(wav);
            long dataBytes = Math.max(0L, size - 44L); // canonical WAV header is 44 bytes
            int sr = config.audioSampleRate > 0 ? config.audioSampleRate : 44100;
            int ch = config.audioChannelCount > 0 ? config.audioChannelCount : 2;
            long byteRate = (long) sr * ch * 2L; // pcm_s16le => 2 bytes/sample/channel
            if (byteRate <= 0L) {
                return -1L;
            }
            return dataBytes * 1000L / byteRate;
        } catch (IOException e) {
            return -1L;
        }
    }

    /**
     * Emits diagnostic warnings when the measured microphone sync parameters suggest the
     * recording is likely to be audibly out of sync, so problems are visible in the log
     * without having to eyeball the waveform. Non-fatal: never blocks the mux.
     */
    private void validateMicSync(long launchGapMs, long warmupMs) {
        int dtsCount = micNonMonotonicDtsCount.get();
        if (dtsCount > SYNC_DTS_WARN_COUNT) {
            RecordableMod.LOGGER.warn(
                    "[sync] Microphone reported {} non-monotonic DTS warnings during capture. The device "
                            + "driver is emitting backward-jumping timestamps; aresample/wallclock guards were "
                            + "applied, but consider updating the audio driver if sync still drifts.", dtsCount);
        }
        // A very large warm-up means the device took an unusually long time to deliver its
        // first sample. The offset compensates for it, but flag it so a persistent hardware
        // stall is diagnosable.
        if (warmupMs >= SYNC_DRIFT_WARN_MS) {
            RecordableMod.LOGGER.warn(
                    "[sync] Microphone warm-up gap was {}ms (launchGap={}ms). This has been compensated via "
                            + "-itsoffset, but a consistently high warm-up indicates a slow-opening capture device.",
                    warmupMs, launchGapMs);
        }
        if (warmupMs >= MIC_WARMUP_MAX_MS) {
            RecordableMod.LOGGER.warn(
                    "[sync] Measured mic warm-up hit the {}ms clamp ceiling; the offset estimate may be "
                            + "unreliable for this recording.", MIC_WARMUP_MAX_MS);
        }
    }

    private double probeMicMeanVolume(Path micWav) {
        if (micWav == null) {
            return Double.NaN;
        }
        try {
            List<String> cmd = List.of(
                    ffmpegExecutable, "-nostdin", "-i", micWav.toAbsolutePath().toString(),
                    "-af", "volumedetect", "-vn", "-f", "null", "-"
            );
            Process probe = ffmpegProcess(cmd).redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(probe.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            if (probe.waitFor(10, TimeUnit.SECONDS)) {
                for (String line : output.split("\n")) {
                    if (line.contains("mean_volume")) {
                        String[] parts = line.split("mean_volume:\\s*");
                        if (parts.length > 1) {
                            try {
                                return Double.parseDouble(parts[1].trim().split("\\s+")[0]);
                            } catch (NumberFormatException ignored) {
                                return Double.NaN;
                            }
                        }
                    }
                }
            } else {
                probe.destroyForcibly();
            }
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("Mic level probe failed (non-critical).", e);
        }
        return Double.NaN;
    }

    private long micFileBytesQuietly() {
        try {
            if (tempMicFile != null && Files.exists(tempMicFile)) {
                return Files.size(tempMicFile);
            }
        } catch (IOException ignored) {
        }
        return -1L;
    }

    /**
     * Runs a quick FFmpeg volumedetect on the final output to check if audio is essentially silent.
     * Logs a diagnostic warning if mean volume is below -80 dB.
     */
    private void probeAudioLevel() {
        if (outputFile == null) return;
        try {
            List<String> cmd = List.of(
                    ffmpegExecutable, "-nostdin", "-i", outputFile.toAbsolutePath().toString(),
                    "-map", "0:a:0", "-af", "volumedetect",
                    "-vn", "-f", "null", "-"
            );
            Process probe = ffmpegProcess(cmd).redirectErrorStream(true).start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(probe.getInputStream(), StandardCharsets.UTF_8))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }
            if (probe.waitFor(10, TimeUnit.SECONDS)) {
                for (String line : output.split("\n")) {
                    if (line.contains("mean_volume")) {
                        RecordableMod.LOGGER.info("Audio level check: {}", line.trim());
                        try {
                            String[] parts = line.split("mean_volume:\\s*");
                            if (parts.length > 1) {
                                double meanDb = Double.parseDouble(parts[1].trim().split("\\s+")[0]);
                                if (meanDb < -70.0) {
                                    String fix;
                                    if (PlatformUtils.isAndroid()) {
                                        // Android has no Stereo Mix; silent audio is almost always a
                                        // missing microphone permission or a muted/low mic input.
                                        fix = "Grant the microphone permission to your launcher (Android Settings > Apps > "
                                                + "permissions) and make sure the device microphone is not muted or turned down.";
                                    } else if (PlatformUtils.isWindows()) {
                                        fix = "Check Stereo Mix volume in Windows Sound Settings > Recording > Properties > Levels. "
                                                + "Set Stereo Mix level to 100%.";
                                    } else {
                                        fix = "Check that your system audio loopback/monitor source is enabled and unmuted "
                                                + "(for example a PulseAudio/PipeWire monitor device) and that its level is turned up.";
                                    }
                                    RecordableMod.LOGGER.warn(
                                            "Audio appears silent or near-silent (mean={} dB). {}", meanDb, fix);
                                }
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                    if (line.contains("max_volume")) {
                        RecordableMod.LOGGER.info("Audio level check: {}", line.trim());
                    }
                }
            } else {
                probe.destroyForcibly();
            }
        } catch (Exception e) {
            RecordableMod.LOGGER.debug("Audio level probe failed (non-critical).", e);
        }
    }

    private void deleteTempAudioFileQuietly() {
        if (tempAudioFile != null) {
            try {
                Files.deleteIfExists(tempAudioFile);
            } catch (IOException ignored) {
            }
            tempAudioFile = null;
        }
        // Microphone temp WAV must also be removed so the recording is not left split
        // into the muxed video plus a stray recordable-mic-*.wav file.
        if (tempMicFile != null) {
            try {
                Files.deleteIfExists(tempMicFile);
            } catch (IOException ignored) {
            }
            tempMicFile = null;
        }
    }

    /**
     * Deletes the intermediate "-muxed" file. Called on mux failure/timeout so the
     * incomplete intermediate never lingers next to the real recording (which would
     * show up as a duplicate entry in the UI and waste disk space). Never deletes the
     * authoritative output - callers only pass the throwaway intermediate here.
     */
    private void deleteMuxedQuietly(Path muxedOutput) {
        if (muxedOutput == null) return;
        try {
            if (Files.deleteIfExists(muxedOutput)) {
                RecordableMod.LOGGER.debug("Removed leftover muxed intermediate: {}", muxedOutput);
            }
        } catch (IOException ignored) {
            // Best effort - if it is still locked we simply leave it.
        }
    }

    /** Returns the last detected audio device status, or null if not yet probed. */
    public AudioCapture.AudioDeviceStatus getDetectedAudioStatus() {
        return detectedAudioStatus;
    }

    private static String normalizeConfiguredAudioDevice(String configuredDevice) {
        if (configuredDevice == null) {
            return "auto";
        }
        String normalized = configuredDevice.trim();
        if (normalized.isEmpty() || "openal".equalsIgnoreCase(normalized)) {
            return "auto";
        }
        return normalized;
    }

    private RecordableConfig.VideoEncoder resolveVideoEncoder() {
        RecordableConfig.VideoEncoder requested = config.encoder == null
                ? RecordableConfig.VideoEncoder.SOFTWARE
                : config.encoder;

        if (requested == RecordableConfig.VideoEncoder.SOFTWARE) {
            return requested;
        }

        List<RecordableConfig.VideoEncoder> available = detectAvailableEncoders();
        if (available.contains(requested)) {
            return requested;
        }

        RecordableMod.LOGGER.warn("Requested encoder {} is not available in this FFmpeg build. Falling back to software x264.",
                requested.displayName);
        return RecordableConfig.VideoEncoder.SOFTWARE;
    }

    public static List<RecordableConfig.VideoEncoder> detectAvailableEncoders() {
        List<RecordableConfig.VideoEncoder> available = new ArrayList<>();
        // SOFTWARE is always offered. Its concrete codec is resolved at command-build
        // time via resolveSoftwareVideoCodec(), which probes a fallback chain
        // (h264_mediacodec on Android, then libx264 -> mpeg4 -> libxvid) so we never
        // emit a codec the FFmpeg build does not actually contain.
        available.add(RecordableConfig.VideoEncoder.SOFTWARE);

        // Resolve (and cache) the concrete codec the SOFTWARE option will actually use.
        // On Android this prefers the h264_mediacodec hardware encoder, then degrades
        // through libx264 -> mpeg4 -> libxvid. Warming it here means the mediacodec
        // probe result is captured up-front and drives real encoding (instead of being
        // logged and thrown away), and it surfaces the chosen codec in the logs early.
        String softwareCodec = resolveSoftwareVideoCodec();
        RecordableMod.LOGGER.info("SOFTWARE encoder will use ffmpeg codec '{}' on this device.", softwareCodec);

        // Discrete-GPU encoders (desktop only; never present on Android ARM). These are
        // probed after the Android hardware path so mediacodec takes priority on mobile.
        if (testEncoder("h264_nvenc")) {
            available.add(RecordableConfig.VideoEncoder.NVIDIA);
        }
        if (testEncoder("h264_amf")) {
            available.add(RecordableConfig.VideoEncoder.AMD);
        }
        if (testEncoder("h264_qsv")) {
            available.add(RecordableConfig.VideoEncoder.INTEL);
        }

        return available;
    }

    /**
     * The concrete FFmpeg video codec string chosen for the current/most-recent
     * recording (e.g. "libx264", "mpeg4", "h264_mediacodec"). Set when
     * {@link #addVideoCodecArgs} runs; useful for logging and diagnostics.
     */
    private String selectedVideoCodec = null;

    /** Cached result of the software-codec fallback probe (process-wide). */
    private static volatile String cachedSoftwareCodec = null;

    /**
     * Set by the runtime watchdog when the encoder accepts frames but produces no
     * output (a silent stall / deadlock). Exposed so callers can report a failed
     * recording instead of a "saved" one.
     */
    private volatile boolean encoderStuck = false;

    /** Returns the concrete ffmpeg codec string selected for the last recording. */
    public String getSelectedVideoCodec() {
        return selectedVideoCodec;
    }

    /**
     * Process-wide cached concrete software codec (e.g. "mpeg4", "libx264",
     * "h264_mediacodec"), or null if it has not been resolved yet. Used by the UI to
     * display the encoder that will actually run rather than the stored config enum.
     */
    public static String getCachedSoftwareCodec() {
        return cachedSoftwareCodec;
    }

    /** True if the runtime watchdog detected a stuck/deadlocked encoder. */
    public boolean isEncoderStuck() {
        return encoderStuck;
    }

    /**
     * Resolves the best available software (or Android-hardware) video codec by
     * probing a fallback chain. On Android we prefer the MediaCodec hardware encoder
     * first because the bundled LGPL FFmpeg build does not contain the GPL libx264
     * encoder. The chain then degrades through the LGPL-compatible software encoders
     * libx264 -> mpeg4 -> libxvid. The result is cached for the process lifetime.
     */
    private static String resolveSoftwareVideoCodec() {
        String cached = cachedSoftwareCodec;
        if (cached != null) {
            return cached;
        }

        List<String> chain = new ArrayList<>();
        if (PlatformUtils.isAndroid()) {
            // h264_mediacodec is Android's hardware H.264 encoder. It is advertised by
            // `ffmpeg -encoders` whenever the build supports it, but it can only reach
            // the MediaCodec/binder framework when the binary runs with DIRECT process
            // permissions. When FFmpeg is launched through the linker workaround
            // (/system/bin/linker64 or linker32, used to bypass SELinux exec blocks on
            // Android 10+), MediaCodec silently DEADLOCKS: FFmpeg accepts stdin but
            // never emits a single encoded frame, producing a 0-byte file. So only
            // offer mediacodec when DIRECT exec has been confirmed.
            FfmpegBundleManager.ExecMethod exec = FfmpegBundleManager.getExecMethod();
            if (exec == FfmpegBundleManager.ExecMethod.DIRECT) {
                chain.add("h264_mediacodec");
            } else {
                RecordableMod.LOGGER.info(
                        "Skipping h264_mediacodec: FFmpeg exec method is {} (MediaCodec needs DIRECT exec "
                                + "for binder access; falling back to a software encoder instead).", exec);
            }
        }
        chain.add("libx264");
        chain.add("mpeg4");
        chain.add("libxvid");

        String chosen = null;
        for (String c : chain) {
            if (testEncoder(c)) {
                chosen = c;
                break;
            }
        }

        if (chosen == null) {
            // Last-resort default. mpeg4 is a built-in LGPL encoder present in virtually
            // every FFmpeg build, so it is the safest fallback if probing itself failed.
            chosen = "mpeg4";
            RecordableMod.LOGGER.warn("No software video encoder could be probed; defaulting to mpeg4.");
        } else {
            RecordableMod.LOGGER.info("Resolved software video codec: {}", chosen);
        }

        cachedSoftwareCodec = chosen;
        return chosen;
    }

    /**
     * Maps an x264-style CRF (~0..51) into an mpeg4/libxvid quantizer
     * (1 = best .. 31 = worst), clamped to a quality-preserving range. With
     * bitrate targeting active this value is applied as a {@code -qmax} ceiling
     * (not {@code -q:v}, which would force constant-quantizer mode and disable
     * the bitrate control).
     */
    private static int mpeg4QualityFromCrf(int crf) {
        int q = Math.round(crf / 2.0f);
        // Widened from 2..8 to 1..15: with explicit bitrate targeting (-b:v) the
        // quantizer is only a -qmax ceiling, so a wider range lets the rate
        // controller pick the best quality that fits the target bitrate.
        if (q < 1) q = 1;
        if (q > 15) q = 15;
        return q;
    }

    public static List<RecordableConfig.AudioEncoder> detectAvailableAudioEncoders() {
        List<RecordableConfig.AudioEncoder> available = new ArrayList<>();
        for (RecordableConfig.AudioEncoder encoder : RecordableConfig.AudioEncoder.values()) {
            if (testAudioEncoder(encoder.ffmpegCodec)) {
                available.add(encoder);
            }
        }
        if (available.isEmpty()) {
            available.add(RecordableConfig.AudioEncoder.AAC);
        }
        return available;
    }

    private static boolean testAudioEncoder(String codec) {
        return testEncoder(codec);
    }

    private static boolean testEncoder(String codec) {
        FfmpegStatus status = detectFfmpeg();
        if (!status.found()) {
            return false;
        }

        Set<String> encoders = new HashSet<>();
        Process process = null;
        try {
            process = ffmpegProcess(status.executable(), "-encoders")
                    .redirectErrorStream(true)
                    .start();

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String lowered = line.toLowerCase(Locale.ROOT).trim();
                    if (lowered.isEmpty()) {
                        continue;
                    }

                    String[] parts = lowered.split("\\s+");
                    if (parts.length >= 2 && parts[0].length() == 6) {
                        encoders.add(parts[1]);
                    }
                }
            }

            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return false;
            }
            return encoders.contains(codec.toLowerCase(Locale.ROOT));
        } catch (Exception exception) {
            RecordableMod.LOGGER.debug("Failed to probe encoder {}", codec, exception);
            return false;
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }

    private static String multiplyBitrateString(String bitrateStr, int factor) {
        if (bitrateStr == null || bitrateStr.isBlank()) return bitrateStr;
        String s = bitrateStr.trim();
        try {
            if (s.endsWith("M") || s.endsWith("m")) {
                double val = Double.parseDouble(s.substring(0, s.length() - 1));
                return Math.round(val * factor) + "M";
            } else if (s.endsWith("k") || s.endsWith("K")) {
                long val = Long.parseLong(s.substring(0, s.length() - 1));
                return (val * factor) + "k";
            } else {
                long val = Long.parseLong(s);
                return Long.toString(val * factor);
            }
        } catch (NumberFormatException e) {
            return bitrateStr; // unparseable - return as-is
        }
    }

    /**
     * Scales a bitrate string ("8M", "800k", "5000000") by a floating-point
     * factor and returns it in the same unit. Used to apply the mpeg4 bitrate
     * boost (2.5x) and to derive maxrate/bufsize from a base bitrate.
     */
    private static String scaleBitrateString(String bitrateStr, double factor) {
        if (bitrateStr == null || bitrateStr.isBlank()) return bitrateStr;
        String s = bitrateStr.trim();
        try {
            if (s.endsWith("M") || s.endsWith("m")) {
                double val = Double.parseDouble(s.substring(0, s.length() - 1));
                return Math.round(val * factor) + "M";
            } else if (s.endsWith("k") || s.endsWith("K")) {
                double val = Double.parseDouble(s.substring(0, s.length() - 1));
                return Math.round(val * factor) + "k";
            } else {
                double val = Double.parseDouble(s);
                return Long.toString(Math.round(val * factor));
            }
        } catch (NumberFormatException e) {
            return bitrateStr; // unparseable - return as-is
        }
    }

    /**
     * Builds the VP9 video-codec arguments for the WebM container. VP9 is the
     * codec the bundled FFmpeg can reliably mux into WebM, and unlike the H.264
     * path it has no -preset/-tune/zerolatency knobs. The 'realtime' deadline plus
     * a high -cpu-used keeps encoding fast enough for live capture (trading some
     * size for speed), and -row-mt spreads the work across cores. WebM streams
     * incrementally, so no +faststart is emitted (that is MP4/MOV-only).
     */
    private void addWebmVp9Args(List<String> args, String bitrate) {
        String vp9Codec = "libvpx-vp9";
        this.selectedVideoCodec = vp9Codec;
        RecordableMod.LOGGER.info("Video encoder selected: WebM/VP9 (ffmpeg codec '{}', real-time mode, exec method {})",
                vp9Codec, FfmpegBundleManager.getExecMethod());
        args.add("-c:v");
        args.add(vp9Codec);
        args.add("-deadline");
        args.add("realtime");
        args.add("-cpu-used");
        args.add("8");
        args.add("-row-mt");
        args.add("1");
        args.add("-b:v");
        args.add(bitrate);
        // Keyframe about once per second for seekability.
        args.add("-g");
        args.add(Integer.toString(Math.max(1, fps)));
        // Leave at least two cores free for Minecraft itself.
        args.add("-threads");
        args.add(Integer.toString(Math.max(1, Runtime.getRuntime().availableProcessors() - 2)));
        args.add("-pix_fmt");
        args.add("yuv420p");
    }

    private void addVideoCodecArgs(List<String> args, RecordableConfig.VideoEncoder selectedEncoder) {
        String format = config.getFormat();
        String bitrate = config.resolveBitrate(width, height);

        // WebM is a special case: the container only accepts VP8/VP9/AV1, never the H.264
        // family produced by the hardware/x264 encoders below. Route it to a real-time VP9
        // encode and return before the H.264 codec selection runs.
        if (config.isWebmFormat()) {
            addWebmVp9Args(args, bitrate);
            return;
        }

        {
                RecordableConfig.VideoEncoder encoderToUse = selectedEncoder == null
                        ? RecordableConfig.VideoEncoder.SOFTWARE
                        : selectedEncoder;
                // Resolve the concrete FFmpeg codec string. For SOFTWARE this probes
                // the fallback chain (h264_mediacodec on Android, else
                // libx264 -> mpeg4 -> libxvid) so we never request a codec this FFmpeg
                // build does not actually contain.
                String codec = (encoderToUse == RecordableConfig.VideoEncoder.SOFTWARE)
                        ? resolveSoftwareVideoCodec()
                        : encoderToUse.ffmpegCodec;
                this.selectedVideoCodec = codec;
                // Build an accurate display label: for SOFTWARE show the real codec
                // ("Software (mpeg4)") instead of the generic "Software (x264)" name,
                // which is misleading when libx264 is absent and mpeg4/mediacodec runs.
                String encoderDisplayLabel = (encoderToUse == RecordableConfig.VideoEncoder.SOFTWARE)
                        ? "Software (" + codec + ")"
                        : encoderToUse.displayName;
                RecordableMod.LOGGER.info("Video encoder selected: {} (ffmpeg codec '{}', exec method {})",
                        encoderDisplayLabel, codec, FfmpegBundleManager.getExecMethod());
                args.add("-c:v");
                args.add(codec);

                switch (encoderToUse) {
                    case NVIDIA -> {
                        // Preset p4 = balanced quality/speed (was p1 = fastest/lowest quality).
                        // RTX-class laptop GPUs (e.g. RTX 3050 Laptop) handle p4 at 1080p60
                        // in real time with headroom to spare, giving noticeably better quality.
                        args.add("-preset");
                        args.add("p4");
                        // Ultra-low-latency tune keeps the NVENC queue from backing up during
                        // live capture (no lookahead, no B-frame reordering delay).
                        args.add("-tune");
                        args.add("ull");
                        // High profile improves compression efficiency / quality at a given bitrate.
                        args.add("-profile:v");
                        args.add("high");
                        // VBR rate control targeting a constant quality (cq) with a bitrate ceiling.
                        args.add("-rc");
                        args.add("vbr");
                        args.add("-cq");
                        args.add(Integer.toString(config.getX264Crf()));
                        args.add("-b:v");
                        args.add(bitrate);
                        // No B-frames: lowers encode latency and avoids reordering stalls that
                        // would otherwise spike the encoder queue during real-time recording.
                        args.add("-bf");
                        args.add("0");
                    }
                    case AMD -> {
                        args.add("-quality");
                        args.add("speed");
                        args.add("-rc");
                        args.add("vbr_latency");
                        args.add("-qp_i");
                        args.add(Integer.toString(config.getX264Crf()));
                        args.add("-qp_p");
                        args.add(Integer.toString(config.getX264Crf()));
                        args.add("-b:v");
                        args.add(bitrate);
                    }
                    case INTEL -> {
                        args.add("-preset");
                        args.add("veryfast");
                        args.add("-global_quality");
                        args.add(Integer.toString(config.getX264Crf()));
                        args.add("-b:v");
                        args.add(bitrate);
                    }
                    case SOFTWARE -> {
                        if (codec.equals("libx264") || codec.equals("libx265")) {
                            // x264/x265 software path. Real-time encoding uses the
                            // "ultrafast" preset + zerolatency tune + CRF. These options
                            // ONLY exist for x264/x265, hence the codec guard above.
                            args.add("-preset");
                            args.add("ultrafast");
                            args.add("-tune");
                            args.add("zerolatency");
                            args.add("-crf");
                            args.add(Integer.toString(config.getX264Crf()));
                            args.add("-b:v");
                            args.add(bitrate);
                            // Threading: leave at least 2 cores free for Minecraft.
                            args.add("-threads");
                            args.add(Integer.toString(Math.max(1, Runtime.getRuntime().availableProcessors() - 2)));
                            // VBV: generous maxrate + bufsize to prevent encoder stalls.
                            String maxrateBitrate = multiplyBitrateString(bitrate, 2);
                            String bufsizeBitrate = multiplyBitrateString(bitrate, 3);
                            args.add("-maxrate");
                            args.add(maxrateBitrate);
                            args.add("-bufsize");
                            args.add(bufsizeBitrate);
                        } else if (codec.equals("h264_mediacodec")) {
                            // Android hardware encoder. It does NOT understand -preset,
                            // -crf or -tune; passing those makes FFmpeg abort with
                            // "Unrecognized option 'preset'" (exit code 8). Drive it with
                            // bitrate control only.
                            args.add("-b:v");
                            args.add(bitrate);
                            args.add("-bf");
                            args.add("0");
                            // GOP size (keyframe interval). Without this MediaCodec defaults
                            // to an i-frame-interval of 1, emitting an I-frame every single
                            // frame (no inter-frame compression, huge overhead, and the
                            // "please set gop_size properly (>= fps)" warning). Match the GOP
                            // to the frame rate => roughly one keyframe per second.
                            args.add("-g");
                            args.add(Integer.toString(Math.max(1, fps)));
                        } else {
                            // Built-in LGPL software encoders (mpeg4, libxvid). These do
                            // NOT support -preset. Older builds drove them with a fixed
                            // -q:v quantizer, which produced very low, "GIF-like" quality
                            // because mpeg4 (Simple Profile) is far less efficient than
                            // H.264. We now do explicit bitrate targeting instead.
                            //
                            // mpeg4 needs roughly 2.5x the bitrate of H.264 to reach a
                            // comparable visual quality, so we scale the resolved H.264
                            // target accordingly. maxrate/bufsize bound the VBV so the
                            // rate controller can spend bits where they matter, and the
                            // quantizer is supplied only as a -qmax ceiling (NOT -q:v,
                            // which would force constant-quantizer mode and IGNORE -b:v).
                            String mpeg4Bitrate = scaleBitrateString(bitrate, 2.5D);
                            String mpeg4Maxrate = scaleBitrateString(mpeg4Bitrate, 1.5D);
                            String mpeg4Bufsize = scaleBitrateString(mpeg4Bitrate, 2.0D);
                            int mpeg4Gop = Math.max(1, fps);
                            int mpeg4Qmax = mpeg4QualityFromCrf(config.getX264Crf());
                            args.add("-b:v");
                            args.add(mpeg4Bitrate);
                            args.add("-maxrate");
                            args.add(mpeg4Maxrate);
                            args.add("-bufsize");
                            args.add(mpeg4Bufsize);
                            // GOP = fps => about one keyframe per second for seekability
                            // without wasting bits on all-intra encoding.
                            args.add("-g");
                            args.add(Integer.toString(mpeg4Gop));
                            // Advanced mpeg4 tools: AIC (AC prediction) + 4MV (four motion
                            // vectors per macroblock) measurably improve quality at the
                            // same bitrate and are safe for Simple/Advanced Simple Profile.
                            args.add("-flags");
                            args.add("+aic+mv4");
                            // Quality ceiling only; bitrate control above stays in charge.
                            args.add("-qmax");
                            args.add(Integer.toString(mpeg4Qmax));
                            // Threading: leave at least 2 cores free for Minecraft.
                            args.add("-threads");
                            args.add(Integer.toString(Math.max(1, Runtime.getRuntime().availableProcessors() - 2)));
                            RecordableMod.LOGGER.info(
                                    "mpeg4 software encode: {}x{} @ {}fps, target bitrate {} (maxrate {}, bufsize {}), GOP {}, qmax {}",
                                    width, height, fps, mpeg4Bitrate, mpeg4Maxrate, mpeg4Bufsize, mpeg4Gop, mpeg4Qmax);
                        }
                    }
                }

                args.add("-pix_fmt");
                args.add("yuv420p");
                // +faststart relocates the moov atom to the front of the file, but it is an
                // MP4/MOV-only feature that forces FFmpeg to buffer output and rewrite the
                // file at the end. Applying it to MKV/AVI/WEBM (which stream/flush
                // incrementally) leaves the file at 0 bytes for the whole recording and can
                // corrupt the output. Only emit it for MP4/MOV.
                String containerFormat = format == null ? "" : format.toLowerCase(Locale.ROOT);
                if (containerFormat.equals("mp4") || containerFormat.equals("mov")) {
                    args.add("-movflags");
                    args.add("+faststart");
                }
        }
    }

    /**
     * Adds audio codec arguments based on selected encoder and container support.
     * Must be called only when audio input has been added to the command.
     */
    private void addAudioCodecArgs(List<String> args) {
        config.validateAudioEncoderCompatibility();
        RecordableConfig.AudioEncoder selected = config.audioEncoder == null
                ? RecordableConfig.AudioEncoder.AAC
                : config.audioEncoder;

        args.add("-c:a");
        args.add(selected.ffmpegCodec);

        args.add("-ar");
        args.add(Integer.toString(config.audioSampleRate));

        args.add("-ac");
        args.add(Integer.toString(config.audioChannelCount));

        if (!selected.isLossless()) {
            args.add("-b:a");
            args.add(config.audioBitrateKbps + "k");
        }

        switch (selected) {
            case OPUS -> {
                args.add("-compression_level");
                args.add("10");
            }
            case MP3 -> {
                args.add("-q:a");
                args.add("2");
            }
            case FLAC -> {
                args.add("-compression_level");
                args.add("8");
            }
            default -> {
            }
        }
    }

    /** Returns whether audio was successfully added to the FFmpeg command. */
    public boolean isAudioEnabled() {
        return audioEnabled;
    }

    /** Returns the name of the audio device being used, or empty if none. */
    public String getAudioDeviceInfo() {
        return audioDeviceInfo;
    }

    /**
     * Writer loop that drains the frame queue into FFmpeg's stdin pipe.
     *
     * <p><b>Frame duplication for correct timing:</b> Each frame carries a wall-clock
     * timestamp (ms since recording started). The writer tracks how many frames FFmpeg
     * has received and how many <em>should</em> have been received based on the timestamp.
     * When dropped frames cause a gap, the current frame data is written multiple times
     * to fill the gap. This keeps the recorded video duration in sync with real time,
     * preventing the "speed-shifting" artefact that occurs when frames are simply lost.</p>
     *
     * <p>Cap: at most 10 duplicate frames per packet to avoid runaway bursts if
     * a single packet has a very large timestamp gap (e.g. game freeze or pause).</p>
     */
    /** Shows a brief in-game chat message to the player (runs on the client thread). */
    private void notifyUser(String message) {
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null) {
                client.execute(() -> {
                    if (client.player != null) {
                        client.player.sendMessage(
                                net.minecraft.text.Text.literal("\u26A0 Record-able: " + message), false);
                    }
                });
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Monitors the encoder for a "silent stall": the writer thread is feeding frames
     * into FFmpeg's stdin but FFmpeg never reports a single encoded frame (parsed from
     * its -stats progress output). This is the signature of an encoder that initialises
     * but cannot actually encode in the current execution context - most notably
     * h264_mediacodec when FFmpeg runs through the Android linker workaround. The writer
     * thread itself cannot detect this because it blocks inside ffmpegStdin.write() once
     * the OS pipe buffer fills, so a dedicated watchdog thread is required.
     */
    private void watchdogLoop() {
        try {
            while (acceptingFrames.get()) {
                Thread.sleep(500L);
                if (encoderStuck) {
                    return;
                }
                long started = writerStartedAtNanos.get();
                if (started <= 0L) {
                    continue;
                }
                long elapsedMs = (System.nanoTime() - started) / 1_000_000L;
                if (elapsedMs < WATCHDOG_TIMEOUT_MS) {
                    continue;
                }
                long written = writtenFrames.get();
                long produced = parsedFrameCount;
                if (written >= WATCHDOG_MIN_FRAMES && produced == 0L) {
                    encoderStuck = true;
                    lastError = "Encoder '" + selectedVideoCodec + "' produced no output after "
                            + written + " input frames in " + elapsedMs + "ms (stuck/deadlocked).";
                    RecordableMod.LOGGER.error(
                            "WATCHDOG: {} FFmpeg exec method={}. Forcibly stopping the stuck encoder so the "
                                    + "recording does not hang and the failure is surfaced.",
                            lastError, FfmpegBundleManager.getExecMethod());
                    notifyUser("Recording failed: the '" + selectedVideoCodec
                            + "' video encoder is not working on this device. Open Record-able settings and choose a "
                            + "different encoder, or update FFmpeg.");
                    acceptingFrames.set(false);
                    // Unblock the writer thread (blocked in ffmpegStdin.write()) by killing FFmpeg.
                    try {
                        if (process != null) {
                            process.destroyForcibly();
                        }
                    } catch (Exception ignored) {
                    }
                    return;
                }
            }
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void writerLoop() {
        int flushCounter = 0;
        long framesWrittenSinceLog = 0L;
        long duplicatedSinceLog = 0L;
        long totalDuplicated = 0L;
        long logWindowStarted = System.nanoTime();
        // FIX: Use microsecond precision to avoid integer division drift.
        // At 60 FPS, 1000/60 = 16 (integer) but actual interval is 16.667ms.
        // Over 60 frames this causes ~40ms/min drift. Using microseconds fixes it.
        final long frameIntervalUs = Math.max(1L, 1_000_000L / Math.max(1, fps));
        final int MAX_DUP_PER_PACKET = 10;

        try {
            while (acceptingFrames.get() || !queue.isEmpty()) {
                if (process != null && !process.isAlive()) {
                    int exitCode = process.exitValue();
                    RecordableMod.LOGGER.error(
                            "FFmpeg process died unexpectedly (exit code {}). Stopping writer. Last FFmpeg error: {}",
                            exitCode, lastError);
                    acceptingFrames.set(false);
                    break;
                }

                FramePacket packet = queue.poll(100L, TimeUnit.MILLISECONDS);
                if (packet == null) {
                    continue;
                }

                long currentWritten = writtenFrames.get();
                long ts = packet.timestampMs();
                int framesToWrite = 1;

                if (ts > 0) {
                    // FIX: Use microsecond math to prevent accumulating drift
                    long tsUs = ts * 1000L;
                    long expectedFrameIndex = tsUs / frameIntervalUs;
                    long gap = expectedFrameIndex - currentWritten;
                    if (gap > 1) {
                        int duplicates = (int) Math.min(gap - 1, MAX_DUP_PER_PACKET);
                        framesToWrite += duplicates;
                        totalDuplicated += duplicates;
                        duplicatedSinceLog += duplicates;
                    }
                }

                for (int i = 0; i < framesToWrite; i++) {
                    ffmpegStdin.write(packet.data());
                    writtenFrames.incrementAndGet();
                    framesWrittenSinceLog++;
                    flushCounter++;
                }
                // V1-0.08: return the frame buffer to the pool for reuse (cuts GC churn).
                FrameBufferPool.getInstance().release(packet.data());

                long now = System.nanoTime();
                if (now - logWindowStarted >= 1_000_000_000L) {
                    // Per-second encoder stats are noise during normal play; keep at DEBUG.
                    RecordableMod.LOGGER.debug("Encoder: {} fps | Queue: {}/{} | Written: {} | Dup: {} (total {})",
                            framesWrittenSinceLog,
                            queue.size(),
                            queueCapacity,
                            writtenFrames.get(),
                            duplicatedSinceLog,
                            totalDuplicated);

                    // Performance feedback (bug #6): a high share of duplicated frames means
                    // the game could not supply frames fast enough for the target FPS. Warn
                    // once (not every second) so the user can react by lowering settings.
                    long written = writtenFrames.get();
                    if (!perfWarningLogged && written >= fps * 5L
                            && totalDuplicated > written * 0.30D) {
                        perfWarningLogged = true;
                        long pct = Math.round(100.0D * totalDuplicated / written);
                        RecordableMod.LOGGER.warn(
                                "Recording performance: {}% of frames are duplicates - the game is "
                                        + "rendering slower than the {} FPS recording target. For smoother "
                                        + "recordings, lower your in-game graphics settings (render distance, "
                                        + "graphics quality) or reduce the recording resolution/FPS.",
                                pct, fps);
                    }

                    framesWrittenSinceLog = 0L;
                    duplicatedSinceLog = 0L;
                    logWindowStarted = now;
                }

                if (flushCounter >= 2) {
                    ffmpegStdin.flush();
                    flushCounter = 0;
                }
            }
            if (!stdinClosed) {
                ffmpegStdin.flush();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (IOException exception) {
            lastError = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            boolean processDead = process != null && !process.isAlive();
            if (processDead) {
                RecordableMod.LOGGER.error(
                        "Write to FFmpeg stdin failed because the process exited (code {}). Last FFmpeg message: {}",
                        process.exitValue(), lastError);
            } else {
                if (stdinClosed) {
                    // Stdin was closed by stop() - this is a normal shutdown race, not an error
                    RecordableMod.LOGGER.debug("Writer caught IOException after stdin closed (normal shutdown): {}", exception.getMessage());
                } else {
                    RecordableMod.LOGGER.warn("Failed while writing raw video to FFmpeg.", exception);
                }
            }
            acceptingFrames.set(false);
        } finally {
            closeStdinQuietly();
            RecordableMod.LOGGER.info("Writer loop finished. Total frames written: {} (duplicated: {})",
                    writtenFrames.get(), totalDuplicated);
        }
    }

    /**
     * Reads FFmpeg stderr, parsing progress lines for real-time stats.
     *
     * <p>FFmpeg outputs progress lines like:
     * {@code frame= 1234 fps= 60 q=23.0 size=   12345KiB time=00:00:20.56 bitrate=4914.2kbits/s speed=1.02x}
     * These use {@code \r} (carriage return) to overwrite the same line. We handle both
     * {@code \r} and {@code \n} as line terminators to capture each progress update.</p>
     *
     * <p>This is inspired by OBS Studio's approach of tracking {@code total_bytes} in
     * real-time from the encoding pipeline.</p>
     */
    private void stderrLoop(InputStream errorStream) {
        try (BufferedInputStream inputStream = new BufferedInputStream(errorStream)) {
            StringBuilder currentLine = new StringBuilder();
            int value;
            while ((value = inputStream.read()) != -1) {
                if (value == '\n' || value == '\r') {
                    String line = currentLine.toString();
                    if (!line.isBlank()) {
                        parseAndLogFfmpegLine(line);
                    }
                    currentLine.setLength(0);
                } else {
                    currentLine.append((char) value);
                }
            }
            if (!currentLine.isEmpty()) {
                parseAndLogFfmpegLine(currentLine.toString());
            }
        } catch (IOException exception) {
            if (acceptingFrames.get()) {
                RecordableMod.LOGGER.debug("FFmpeg stderr reader ended.", exception);
            }
        }
    }

    private static final java.util.regex.Pattern SIZE_PATTERN = java.util.regex.Pattern.compile("size=\\s*(\\d+)\\s*([kKmMgG]i?[bB]?)");
    private static final java.util.regex.Pattern FRAME_PATTERN = java.util.regex.Pattern.compile("frame=\\s*(\\d+)");
    private static final java.util.regex.Pattern FPS_PATTERN = java.util.regex.Pattern.compile("fps=\\s*([\\d.]+)");
    private static final java.util.regex.Pattern BITRATE_PATTERN = java.util.regex.Pattern.compile("bitrate=\\s*([\\d.]+)\\s*([kKmMgG]?)bits/s");
    private static final java.util.regex.Pattern SPEED_PATTERN = java.util.regex.Pattern.compile("speed=\\s*([\\d.]+x)");

    /**
     * Parses an FFmpeg stderr line, extracting progress stats if it's a progress line,
     * or logging it as a warning/error otherwise.
     */
    private void parseAndLogFfmpegLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }

        boolean isProgressLine = line.contains("frame=") && line.contains("size=");

        if (isProgressLine) {
            java.util.regex.Matcher sizeMatcher = SIZE_PATTERN.matcher(line);
            if (sizeMatcher.find()) {
                try {
                    long sizeValue = Long.parseLong(sizeMatcher.group(1));
                    String unit = sizeMatcher.group(2).toLowerCase(Locale.ROOT);
                    if (unit.startsWith("k")) {
                        parsedSizeBytes = sizeValue * 1024L;
                    } else if (unit.startsWith("m")) {
                        parsedSizeBytes = sizeValue * 1024L * 1024L;
                    } else if (unit.startsWith("g")) {
                        parsedSizeBytes = sizeValue * 1024L * 1024L * 1024L;
                    } else {
                        parsedSizeBytes = sizeValue;
                    }
                } catch (NumberFormatException ignored) {}
            }

            java.util.regex.Matcher frameMatcher = FRAME_PATTERN.matcher(line);
            if (frameMatcher.find()) {
                try {
                    parsedFrameCount = Long.parseLong(frameMatcher.group(1));
                } catch (NumberFormatException ignored) {}
            }

            java.util.regex.Matcher fpsMatcher = FPS_PATTERN.matcher(line);
            if (fpsMatcher.find()) {
                try {
                    parsedFps = Double.parseDouble(fpsMatcher.group(1));
                } catch (NumberFormatException ignored) {}
            }

            java.util.regex.Matcher bitrateMatcher = BITRATE_PATTERN.matcher(line);
            if (bitrateMatcher.find()) {
                try {
                    double br = Double.parseDouble(bitrateMatcher.group(1));
                    String brUnit = bitrateMatcher.group(2).toLowerCase(Locale.ROOT);
                    if (brUnit.startsWith("m")) {
                        parsedBitrate = br * 1000.0;
                    } else {
                        parsedBitrate = br; // kbits/s
                    }
                } catch (NumberFormatException ignored) {}
            }

            java.util.regex.Matcher speedMatcher = SPEED_PATTERN.matcher(line);
            if (speedMatcher.find()) {
                parsedSpeed = speedMatcher.group(1);
            }

            if (parsedFrameCount > 0 && parsedFrameCount % 300 == 0) {
                // Periodic encoder progress - DEBUG so it does not flood the normal log.
                RecordableMod.LOGGER.debug("FFmpeg progress: frame={} size={} fps={} bitrate={}kbits/s speed={}",
                        parsedFrameCount,
                        RecordingManager.formatBytes(parsedSizeBytes),
                        String.format(Locale.ROOT, "%.1f", parsedFps),
                        String.format(Locale.ROOT, "%.1f", parsedBitrate),
                        parsedSpeed);
            }
        } else {
            lastError = line;
            RecordableMod.LOGGER.warn("FFmpeg: {}", line);
        }
    }

    private void closeStdinQuietly() {
        if (ffmpegStdin != null) {
            try {
                stdinClosed = true;
                ffmpegStdin.close();
            } catch (IOException ignored) {
            }
        }
    }

    private void closeProcessStreamsQuietly() {
        if (process == null) {
            return;
        }
        try {
            process.getInputStream().close();
        } catch (IOException ignored) {
        }
        try {
            process.getErrorStream().close();
        } catch (IOException ignored) {
        }
    }

    /**
     * Detects FFmpeg using a priority-based fallback chain:
     * <ol>
     *   <li>User-configured path ({@code RECORDABLE_FFMPEG_PATH} env var)</li>
     *   <li>Bundled FFmpeg (managed by {@link FfmpegBundleManager})</li>
     *   <li>System {@code PATH}</li>
     * </ol>
     *
     * @return status describing which FFmpeg was found (or not)
     */
    public static FfmpegStatus detectFfmpeg() {
        long now = System.currentTimeMillis();
        FfmpegStatus cached = cachedStatus;
        if (cached != null && now - cachedStatusAtMs < 5_000L) {
            return cached;
        }

        List<String> diag = new ArrayList<>();
        RecordableConfig config = null;
        try {
            config = RecordableConfig.get();
        } catch (Exception e) {
        }

        // 1. Manual path configured in the mod settings (config.ffmpegPath) -
        //    highest priority. This is the primary Android/Termux workaround.
        String manual = config == null ? null : config.ffmpegPath;
        if (manual != null && !manual.isBlank()) {
            FfmpegStatus s = probeFfmpeg(manual.trim());
            if (s.found()) {
                diag.add("[OK] Manual path (settings): " + manual.trim() + "  ->  " + s.version());
                lastDiagnostics = diag;
                RecordableMod.LOGGER.info("[FFmpeg] Using manual settings path: {}", s.executable());
                cachedStatus = s;
                cachedStatusAtMs = now;
                return s;
            }
            diag.add("[FAIL] Manual path (settings): " + manual.trim() + "  ->  " + s.error());
        } else {
            diag.add("[--] Manual path (settings): not set");
        }

        // 2. Environment variable RECORDABLE_FFMPEG_PATH.
        String configured = System.getenv("RECORDABLE_FFMPEG_PATH");
        if (configured != null && !configured.isBlank()) {
            FfmpegStatus userStatus = probeFfmpeg(configured.trim());
            if (userStatus.found()) {
                diag.add("[OK] Env RECORDABLE_FFMPEG_PATH: " + configured.trim() + "  ->  " + userStatus.version());
                lastDiagnostics = diag;
                RecordableMod.LOGGER.info("[FFmpeg] Using env RECORDABLE_FFMPEG_PATH: {}", userStatus.executable());
                cachedStatus = userStatus;
                cachedStatusAtMs = now;
                return userStatus;
            }
            diag.add("[FAIL] Env RECORDABLE_FFMPEG_PATH: " + configured.trim() + "  ->  " + userStatus.error());
        } else {
            diag.add("[--] Env RECORDABLE_FFMPEG_PATH: not set");
        }

        // 3. Bundled / previously downloaded binary.
        boolean useBundled = config == null || config.useBundledFfmpeg;
        if (useBundled) {
            String bundledPath = FfmpegBundleManager.getBundledFfmpegPath();
            if (bundledPath != null) {
                FfmpegStatus bundledStatus = probeFfmpeg(bundledPath);
                if (bundledStatus.found()) {
                    diag.add("[OK] Bundled/downloaded: " + bundledPath + "  ->  " + bundledStatus.version());
                    lastDiagnostics = diag;
                    RecordableMod.LOGGER.info("[FFmpeg] Using bundled FFmpeg: {}", bundledStatus.executable());
                    cachedStatus = bundledStatus;
                    cachedStatusAtMs = now;
                    return bundledStatus;
                }
                diag.add("[FAIL] Bundled/downloaded: " + bundledPath + "  ->  " + bundledStatus.error());
            } else {
                diag.add("[--] Bundled/downloaded: none installed");
            }
        } else {
            diag.add("[--] Bundled/downloaded: disabled in settings");
        }

        // 4. Well-known install locations (auto-probed so Termux 'pkg install
        //    ffmpeg' just works with no manual configuration on Android).
        for (String cand : commonFfmpegCandidates()) {
            FfmpegStatus s = probeFfmpeg(cand);
            if (s.found()) {
                diag.add("[OK] Known location: " + cand + "  ->  " + s.version());
                lastDiagnostics = diag;
                RecordableMod.LOGGER.info("[FFmpeg] Using known-location FFmpeg: {}", s.executable());
                cachedStatus = s;
                cachedStatusAtMs = now;
                return s;
            }
            diag.add("[FAIL] Known location: " + cand + "  ->  " + s.error());
        }

        // 5. System PATH.
        FfmpegStatus systemStatus = probeFfmpeg("ffmpeg");
        if (systemStatus.found()) {
            diag.add("[OK] System PATH (ffmpeg): " + systemStatus.version());
            RecordableMod.LOGGER.info("[FFmpeg] Using system PATH FFmpeg: {}", systemStatus.version());
        } else {
            diag.add("[FAIL] System PATH (ffmpeg): " + systemStatus.error());
            RecordableMod.LOGGER.warn("[FFmpeg] No FFmpeg found via any method (manual, env, bundled, known locations, PATH).");
        }
        lastDiagnostics = diag;
        cachedStatus = systemStatus;
        cachedStatusAtMs = now;
        return systemStatus;
    }

    /**
     * Well-known absolute locations where an ffmpeg binary is commonly found.
     * Probing these lets Termux installs ("pkg install ffmpeg") work on Android
     * with zero configuration, and covers typical desktop install dirs too.
     */
    private static List<String> commonFfmpegCandidates() {
        List<String> list = new ArrayList<>();
        // Termux (Android) default prefix.
        list.add("/data/data/com.termux/files/usr/bin/ffmpeg");
        // Common *nix locations.
        list.add("/usr/bin/ffmpeg");
        list.add("/usr/local/bin/ffmpeg");
        list.add("/bin/ffmpeg");
        list.add("/system/bin/ffmpeg");
        // Homebrew (macOS).
        list.add("/opt/homebrew/bin/ffmpeg");
        return list;
    }

    /**
     * Returns the per-step diagnostics recorded by the most recent
     * {@link #detectFfmpeg()} call, one entry per path that was attempted. Used
     * by the FFmpeg setup screen to show users exactly what was tried and why
     * each candidate failed.
     */
    public static List<String> getLastDiagnostics() {
        List<String> snapshot = lastDiagnostics;
        return snapshot == null ? new ArrayList<>() : new ArrayList<>(snapshot);
    }

    /** Drops the cached detection result so the next call re-probes from scratch. */
    public static void invalidateDetectionCache() {
        cachedStatus = null;
        cachedStatusAtMs = 0L;
    }

    /**
     * Runs a verbose, on-demand FFmpeg test for the "Test FFmpeg" button. If
     * {@code explicitPath} is non-blank only that path is tested; otherwise the
     * full detection chain runs. Returns a multi-line human-readable report.
     */
    public static String testFfmpegVerbose(String explicitPath) {
        StringBuilder sb = new StringBuilder();
        if (explicitPath != null && !explicitPath.isBlank()) {
            String exe = explicitPath.trim();
            sb.append("Testing: ").append(exe).append('\n');
            FfmpegStatus s = probeFfmpeg(exe);
            if (s.found()) {
                sb.append("RESULT: OK\n").append(s.version()).append('\n');
            } else {
                sb.append("RESULT: FAILED\n").append(s.error()).append('\n');
            }
            return sb.toString();
        }
        invalidateDetectionCache();
        FfmpegStatus s = detectFfmpeg();
        sb.append(s.found() ? "RESULT: FFmpeg FOUND\n" : "RESULT: FFmpeg NOT FOUND\n");
        if (s.found()) {
            sb.append("Using: ").append(s.executable()).append('\n');
            sb.append(s.version()).append('\n');
        }
        sb.append('\n').append("Paths tried:\n");
        for (String d : getLastDiagnostics()) {
            sb.append("  ").append(d).append('\n');
        }
        return sb.toString();
    }

    private static FfmpegStatus probeFfmpeg(String executable) {
        try {
            Process process = ffmpegProcess(executable, "-version")
                    .redirectErrorStream(true)
                    .start();
            boolean exited = process.waitFor(3, TimeUnit.SECONDS);
            if (!exited) {
                process.destroyForcibly();
                return FfmpegStatus.notFound(executable, "Timed out while probing FFmpeg.");
            }

            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String firstLine = output.lines().findFirst().orElse("ffmpeg version unknown");
            if (process.exitValue() == 0) {
                return new FfmpegStatus(true, executable, firstLine, "");
            }
            return FfmpegStatus.notFound(executable, firstLine.isBlank() ? "FFmpeg probe failed." : firstLine);
        } catch (Exception exception) {
            String message = exception.getMessage() == null ? exception.toString() : exception.getMessage();
            return FfmpegStatus.notFound(executable, "FFmpeg not found: " + message);
        }
    }

    private static String joinCommand(List<String> command) {
        StringJoiner joiner = new StringJoiner(" ");
        for (String part : command) {
            if (part.indexOf(' ') >= 0) {
                joiner.add('"' + part.replace("\"", "\\\"") + '"');
            } else {
                joiner.add(part);
            }
        }
        return joiner.toString();
    }

    private record FramePacket(byte[] data, long timestampMs) {
    }

    public record FfmpegStatus(boolean found, String executable, String version, String error) {
        private static FfmpegStatus notFound(String executable, String error) {
            return new FfmpegStatus(false, executable, "", error == null ? "" : error);
        }

        public String displayText() {
            return found ? "Found: " + version : "Not found: " + error;
        }
    }
}
