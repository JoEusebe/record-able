package dev.recordable;

import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Kill-montage capture buffer (Minecraft kill-montage style clips).
 *
 * <p>Maintains a short rolling <b>pre-roll</b> of recent frames while the player is in
 * combat so that, when a kill is detected, the resulting clip can begin a configurable
 * number of seconds <b>before</b> the finishing blow, include the kill itself, and then
 * continue for a configurable number of seconds <b>after</b>.</p>
 *
 * <h3>Why this is separate from the normal auto-clip path</h3>
 * <p>A regular auto-clip starts recording <i>after</i> the trigger fires, so it can only
 * capture footage going forward. To include the second before the finishing blow we must
 * already have those frames buffered. This class keeps a tiny rolling buffer that is only
 * active during combat (armed on each attack, auto-disarmed after a short idle window), so
 * there is no always-on capture cost.</p>
 *
 * <h3>Thread model</h3>
 * <p>{@link #arm}, {@link #triggerKill} and {@link #onRenderFrame} are all invoked on the
 * Minecraft client/render thread (client tick and render run on the same thread), so OpenGL
 * frame grabs are safe. Encoding to disk happens on a short-lived daemon thread.</p>
 *
 * <p>This class deliberately avoids importing any {@code net.minecraft.*} types so the
 * identical source compiles unchanged in every build variant; it relies only on
 * {@link ScreenCapture} (same package) and {@link RecordableMod#sendClientMessage(Object, String, boolean)}.</p>
 */
public final class KillClipBuffer {

    private static final KillClipBuffer INSTANCE = new KillClipBuffer();

    /** Hard cap on stored frame height to keep montage memory bounded on high-res displays. */
    private static final int MAX_CAPTURE_HEIGHT = 1080;
    /** Frame-rate cap for montage capture (keeps memory/CPU light). User-selectable up to this. */
    private static final int MONTAGE_FPS_CAP = 60;

    public static KillClipBuffer getInstance() { return INSTANCE; }

    private record TimedFrame(byte[] rgb, int width, int height, long tsMs) {
        long bytes() { return rgb.length; }
    }

    private final Deque<TimedFrame> frames = new ArrayDeque<>();
    private final AtomicBoolean saving = new AtomicBoolean(false);

    private ScreenCapture capture;
    private int capWidth;
    private int capHeight;
    private int fpsCap = MONTAGE_FPS_CAP;
    private long frameIntervalMs = 1000L / MONTAGE_FPS_CAP;
    private long lastFrameMs = 0L;

    private long preMs = 1000L;
    private long postMs = 1000L;
    private long memoryBudgetBytes = 512L * 1024L * 1024L;
    private long currentBytes = 0L;

    private volatile boolean armed = false;     // capturing rolling pre-roll
    private volatile boolean finishing = false; // capturing post-roll after a kill
    private long armIdleDeadlineMs = 0L;         // disarm if no kill by this time
    private long killTimeMs = 0L;                // wall-clock time the kill happened
    private long postDeadlineMs = 0L;            // stop capturing at this time
    private String pendingPrefix = "on-kill";
    private String pendingReason = "Kill";
    private Object pendingClient = null;
    private volatile boolean audioEnabled = false;

    private KillClipBuffer() {}

    /**
     * Begin or refresh rolling pre-roll capture. Safe (and intended) to be called on every
     * attack the player lands, so the rolling window always covers the most recent combat.
     *
     * @param client      platform client object (for chat messages); may be null
     * @param width       desired capture width in pixels (typically the recording width)
     * @param height      desired capture height in pixels
     * @param fps         desired fps (capped to {@link #MONTAGE_FPS_CAP})
     * @param preSeconds  seconds of footage to retain before the finishing blow
     * @param postSeconds seconds of footage to retain after the kill
     */
    public synchronized void arm(Object client, int width, int height, int fps,
                                 int preSeconds, int postSeconds) {
        if (finishing) return; // a clip is already being finalized; let it complete first

        // Bound capture resolution so memory stays sane on 1440p/4K displays.
        int w = Math.max(2, width);
        int h = Math.max(2, height);
        if (h > MAX_CAPTURE_HEIGHT) {
            double scale = MAX_CAPTURE_HEIGHT / (double) h;
            w = Math.max(2, (int) Math.round(w * scale));
            h = MAX_CAPTURE_HEIGHT;
        }
        w = (w / 2) * 2;
        h = (h / 2) * 2;

        this.preMs = Math.max(0L, preSeconds * 1000L);
        this.postMs = Math.max(0L, postSeconds * 1000L);
        this.fpsCap = Math.max(10, Math.min(fps > 0 ? fps : MONTAGE_FPS_CAP, MONTAGE_FPS_CAP));
        this.frameIntervalMs = 1000L / this.fpsCap;
        this.memoryBudgetBytes = Math.min(PlatformUtils.getReplayBufferMemoryBudgetMB(), 768L) * 1024L * 1024L;
        this.pendingClient = client;

        if (capture == null || w != capWidth || h != capHeight) {
            closeCapture();
            capture = new ScreenCapture(w, h);
            capWidth = w;
            capHeight = h;
        }

        // Idle window = pre-roll + a generous combat window so the buffer survives a normal
        // fight (kill detection has its own ~10s tracking window).
        this.armIdleDeadlineMs = System.currentTimeMillis() + this.preMs + 12_000L;
        this.armed = true;

        // If audio montages are enabled, start the rolling PCM buffer so we already have the
        // pre-roll audio captured when the kill lands. Retention must cover the whole clip
        // (pre + post) plus a margin for capture/timestamp jitter.
        this.audioEnabled = RecordableConfig.get().autoClipAudio;
        if (this.audioEnabled) {
            try {
                long retentionMs = this.preMs + this.postMs + 3000L;
                OpenALLoopbackCapture.getInstance().enableRollingBuffer(retentionMs);
            } catch (Throwable t) {
                RecordableMod.LOGGER.debug("Kill-montage audio buffer enable failed.", t);
                this.audioEnabled = false;
            }
        }
    }

    /**
     * Called every render frame (from {@code RecordingManager.onFrame()}). Captures and
     * buffers frames while armed/finishing, and self-disarms when combat goes idle. Cheap
     * no-op when not in combat.
     */
    public synchronized void onRenderFrame() {
        if (!armed && !finishing) return;
        long now = System.currentTimeMillis();

        // Auto-disarm if combat ended without a kill.
        if (armed && !finishing && now > armIdleDeadlineMs) {
            reset();
            return;
        }

        boolean dueForFrame = frameIntervalMs <= 0 || lastFrameMs == 0L
                || (now - lastFrameMs) >= (frameIntervalMs - 1);

        if (dueForFrame && capture != null) {
            try {
                ScreenCapture.CapturedFrame f = capture.captureFrame();
                if (f != null && f.rgbPixels() != null) {
                    frames.addLast(new TimedFrame(f.rgbPixels(), f.width(), f.height(), now));
                    currentBytes += f.rgbPixels().length;
                    lastFrameMs = now;
                    trim(now);
                }
            } catch (Throwable t) {
                RecordableMod.LOGGER.debug("Kill-clip capture frame failed.", t);
            }
        }

        if (finishing && now >= postDeadlineMs) {
            finalizeClip();
        }
    }

    /**
     * Called when a kill is detected. Switches to post-roll capture; the montage clip is
     * encoded once {@code postSeconds} have elapsed.
     *
     * @param client      platform client object (for chat messages); may be null
     * @param reason      human-readable trigger reason (e.g. "Kill: Horse")
     * @param filePrefix  auto-clip file prefix (e.g. "on-kill" / "on-player-kill")
     */
    public synchronized void triggerKill(Object client, String reason, String filePrefix,
                                         int width, int height, int fps,
                                         int preSeconds, int postSeconds) {
        if (finishing || saving.get()) {
            // A montage is already capturing/saving; it will cover this kill too.
            return;
        }
        // Ensure we have a capture context even if no attack frame was buffered yet
        // (e.g. a one-shot kill before the first pre-roll frame was grabbed).
        if (capture == null || !armed) {
            arm(client, width, height, fps, preSeconds, postSeconds);
        }

        long now = System.currentTimeMillis();
        this.killTimeMs = now;
        this.preMs = Math.max(0L, preSeconds * 1000L);
        this.postMs = Math.max(0L, postSeconds * 1000L);
        this.postDeadlineMs = now + this.postMs;
        this.pendingReason = reason != null ? reason : "Kill";
        this.pendingPrefix = filePrefix != null ? filePrefix : "on-kill";
        if (client != null) this.pendingClient = client;
        this.finishing = true;
        this.armed = true;

        // Trim pre-roll to the configured seconds before the kill; keep everything after.
        trimPreRollToKill();

        // If there is no post-roll requested, finalize immediately on the next frame check.
        if (this.postMs == 0L) {
            finalizeClip();
        }
    }

    private void trim(long now) {
        if (!finishing) {
            // Rolling pre-roll: keep only the last preMs of frames.
            long cutoff = now - preMs;
            while (!frames.isEmpty()) {
                TimedFrame oldest = frames.peekFirst();
                if (oldest != null && oldest.tsMs() < cutoff) {
                    TimedFrame removed = frames.pollFirst();
                    if (removed != null) currentBytes -= removed.bytes();
                } else {
                    break;
                }
            }
        }
        // Memory guard (always): never let the buffer grow past the budget.
        while (currentBytes > memoryBudgetBytes && frames.size() > 1) {
            TimedFrame removed = frames.pollFirst();
            if (removed != null) currentBytes -= removed.bytes();
        }
    }

    private void trimPreRollToKill() {
        long cutoff = killTimeMs - preMs;
        while (!frames.isEmpty()) {
            TimedFrame oldest = frames.peekFirst();
            if (oldest != null && oldest.tsMs() < cutoff) {
                TimedFrame removed = frames.pollFirst();
                if (removed != null) currentBytes -= removed.bytes();
            } else {
                break;
            }
        }
    }

    private void finalizeClip() {
        if (frames.size() < 2) {
            reset();
            return;
        }
        final TimedFrame[] snapshot = frames.toArray(new TimedFrame[0]);
        final String reason = pendingReason;
        final String prefix = pendingPrefix;
        final Object client = pendingClient;

        // Snapshot the matching audio segment BEFORE reset() disables/clears the rolling
        // buffer. The window spans the first to the last buffered video frame so audio and
        // video line up.
        byte[] audioSnapshot = null;
        if (audioEnabled && snapshot.length >= 2) {
            try {
                long startMs = snapshot[0].tsMs();
                long endMs = snapshot[snapshot.length - 1].tsMs();
                audioSnapshot = OpenALLoopbackCapture.getInstance().extractAudio(startMs, endMs);
            } catch (Throwable t) {
                RecordableMod.LOGGER.debug("Kill-montage audio extract failed.", t);
            }
        }
        final byte[] audio = audioSnapshot;
        reset();

        if (saving.getAndSet(true)) {
            return;
        }
        Thread t = new Thread(() -> {
            try {
                encode(client, snapshot, prefix, reason, audio);
            } catch (Throwable e) {
                RecordableMod.LOGGER.warn("Failed to save kill montage clip.", e);
            } finally {
                saving.set(false);
            }
        }, "Record-able Kill Montage Save");
        t.setDaemon(true);
        // Run below normal priority so the montage save never competes with gameplay or the
        // main recording encoder for scheduling.
        try { t.setPriority(Thread.MIN_PRIORITY); } catch (Throwable ignored) {}
        t.start();
    }

    private void encode(Object client, TimedFrame[] f, String prefix, String reason, byte[] audioPcm) throws Exception {
        if (f.length < 2) return;

        RecordableConfig config = RecordableConfig.get();
        FFmpegEncoder.FfmpegStatus ff = FFmpegEncoder.detectFfmpeg();
        if (!ff.found()) {
            RecordableMod.LOGGER.warn("Kill montage skipped (FFmpeg not found): {}", reason);
            return;
        }

        Path outputDir = config.getOutputDirectory();
        Path triggerDir = outputDir.resolve("clips").resolve(RecordableConfig.clipSubfolderForPrefix(prefix));
        Files.createDirectories(triggerDir);
        String ext = config.getFormat();
        String base = RecordableConfig.resolveFilenamePattern(
                RecordableConfig.defaultClipFilenamePatternForPrefix(prefix));
        Path outputFile = triggerDir.resolve(base + "." + ext);
        int dupIndex = 1;
        while (Files.exists(outputFile)) {
            outputFile = triggerDir.resolve(base + "-" + dupIndex + "." + ext);
            dupIndex++;
        }

        int outW = f[0].width();
        int outH = f[0].height();
        long durationMs = f[f.length - 1].tsMs() - f[0].tsMs();
        double effFps = durationMs > 0 ? (f.length * 1000.0 / durationMs) : fpsCap;
        int fps = Math.max(1, Math.min(120, (int) Math.round(effFps)));

        // If we captured a matching audio segment, write it to a temp PCM file so FFmpeg can
        // mux it in as a second input. Raw s16le 48kHz stereo matches the loopback capture.
        Path audioFile = null;
        boolean hasAudio = audioPcm != null && audioPcm.length > 0;
        if (hasAudio) {
            try {
                audioFile = Files.createTempFile("recordable-montage-", ".pcm");
                Files.write(audioFile, audioPcm);
            } catch (Throwable t) {
                RecordableMod.LOGGER.debug("Kill-montage audio temp write failed; encoding video-only.", t);
                hasAudio = false;
                audioFile = null;
            }
        }

        try {
            java.util.List<String> cmd = new java.util.ArrayList<>();
            cmd.add(ff.executable());
            cmd.add("-y");
            cmd.add("-loglevel"); cmd.add("error");
            cmd.add("-nostats");
            // Input 0: raw video frames piped over stdin.
            cmd.add("-f"); cmd.add("rawvideo");
            cmd.add("-pixel_format"); cmd.add("rgb24");
            cmd.add("-video_size"); cmd.add(outW + "x" + outH);
            cmd.add("-framerate"); cmd.add(String.valueOf(fps));
            cmd.add("-i"); cmd.add("pipe:0");
            // Input 1 (optional): raw PCM audio from the rolling loopback buffer.
            if (hasAudio) {
                cmd.add("-f"); cmd.add("s16le");
                cmd.add("-ar"); cmd.add(String.valueOf(OpenALLoopbackCapture.SAMPLE_RATE));
                cmd.add("-ac"); cmd.add(String.valueOf(OpenALLoopbackCapture.CHANNELS));
                cmd.add("-i"); cmd.add(audioFile.toString());
            }
            cmd.add("-c:v"); cmd.add("libx264");
            // Keep the montage encode deliberately lightweight: it runs as a burst at the
            // exact moment of a kill, concurrently with the (much heavier) main recording
            // encoder. "ultrafast" plus a small thread cap keeps the montage from starving
            // the main encoder's CPU cores and spiking its frame queue. The clip is only a
            // second or two long, so the quality trade-off is negligible.
            cmd.add("-preset"); cmd.add("ultrafast");
            cmd.add("-threads"); cmd.add("2");
            cmd.add("-crf"); cmd.add("23");
            cmd.add("-pix_fmt"); cmd.add("yuv420p");
            if (hasAudio) {
                cmd.add("-map"); cmd.add("0:v:0");
                cmd.add("-map"); cmd.add("1:a:0");
                cmd.add("-c:a"); cmd.add("aac");
                cmd.add("-b:a"); cmd.add("160k");
                // Stop at whichever stream ends first so a slightly longer audio/video tail
                // doesn't desync the clip.
                cmd.add("-shortest");
            }
            cmd.add(outputFile.toString());

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);

            Process process = pb.start();

        // Drain FFmpeg's merged stdout/stderr on a separate thread. If this pipe is never
        // read, FFmpeg blocks once the OS pipe buffer (~64KB) fills, which in turn blocks the
        // frame writes below and deadlocks the encode until the JVM exits. That deadlock is
        // why the montage previously only finalized after the game was closed.
        final StringBuilder ffmpegLog = new StringBuilder();
        Thread drain = new Thread(() -> {
            try (java.io.BufferedReader r = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    if (ffmpegLog.length() < 8000) {
                        ffmpegLog.append(line).append('\n');
                    }
                }
            } catch (Exception ignored) {
                // best effort
            }
        }, "Record-able Kill Montage FFmpeg Log");
        drain.setDaemon(true);
        drain.start();

        try (OutputStream stdin = process.getOutputStream()) {
            for (TimedFrame tf : f) {
                stdin.write(tf.rgb());
            }
            stdin.flush();
        }

        int exitCode = process.waitFor();
        try {
            drain.join(2000);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
        if (exitCode == 0 && Files.exists(outputFile)) {
                long size = Files.size(outputFile);
                RecordableMod.LOGGER.info("Kill montage saved: {} ({} frames, {}, {}s @ {} FPS, audio: {})",
                        outputFile, f.length, RecordingManager.formatBytes(size),
                        String.format("%.1f", durationMs / 1000.0), fps, hasAudio ? "yes" : "no");
                RecordableMod.sendClientMessage(ChatCategory.CLIPS, client,
                        "§a🎬 Kill montage saved: §f" + reason
                                + " §7(" + String.format("%.1fs", durationMs / 1000.0)
                                + (hasAudio ? ", audio" : "") + ")", false);
            } else {
                RecordableMod.LOGGER.warn("Kill montage encode failed (exit code {}): {}\nFFmpeg output:\n{}", exitCode, outputFile, ffmpegLog.toString().trim());
            }
        } finally {
            if (audioFile != null) {
                try {
                    Files.deleteIfExists(audioFile);
                } catch (Throwable ignored) {
                    // best effort temp cleanup
                }
            }
        }
    }

    private void reset() {
        armed = false;
        finishing = false;
        frames.clear();
        currentBytes = 0L;
        lastFrameMs = 0L;
        pendingClient = null;
        if (audioEnabled) {
            try {
                OpenALLoopbackCapture.getInstance().disableRollingBuffer();
            } catch (Throwable ignored) {
                // best effort
            }
        }
        audioEnabled = false;
    }

    private void closeCapture() {
        if (capture != null) {
            try {
                capture.close();
            } catch (Throwable ignored) {
                // best effort
            }
            capture = null;
        }
    }

    /** Whether a montage is currently capturing (pre-roll armed or post-roll finishing). */
    public boolean isActive() { return armed || finishing; }
}
