package dev.recordable;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Version-agnostic bridge between the optional Simple Voice Chat mod and the
 * Record-able recording pipeline.
 *
 * <p>Simple Voice Chat (by Max Henkel) exposes a client-side plugin API. When that
 * mod is installed, {@link RecordableVoicechatPlugin} registers listeners for the
 * decoded audio of other players ({@code ClientReceiveSoundEvent}) and forwards the
 * raw PCM here. All Simple Voice Chat audio is signed 16-bit, 48 kHz, mono.</p>
 *
 * <p>This class mixes every incoming voice source (multiple players can talk at the
 * same time) onto a single wall-clock timeline and streams the result to a small
 * sidecar WAV file next to the recording. A bounded sliding window keeps memory use
 * constant regardless of recording length. {@code FFmpegEncoder} then decides whether
 * to overlay that sidecar onto the finished recording.</p>
 *
 * <p>Because Simple Voice Chat plays incoming voices through Minecraft's OpenAL sound
 * engine, they are already captured when Record-able's OpenAL loopback device is
 * active. The sidecar produced here is therefore only overlaid as a fallback when the
 * loopback path is unavailable, which avoids doubled/echoed voices.</p>
 *
 * <p>All methods are safe to call whether or not Simple Voice Chat is present; when it
 * is absent no plugin is ever loaded and this class simply stays idle.</p>
 */
public final class VoiceChatIntegration {

    /** Simple Voice Chat audio format: 48 kHz, mono, signed 16-bit little-endian PCM. */
    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNELS = 1;
    public static final int BITS_PER_SAMPLE = 16;

    // Sliding-window mixer sizing (in mono samples). The window bounds memory use;
    // samples older than the keep-behind margin are flushed to the WAV as they age out.
    private static final int WINDOW_SAMPLES = SAMPLE_RATE * 2;        // 2 seconds
    private static final int FLUSH_TRIGGER_SAMPLES = SAMPLE_RATE * 3 / 2; // 1.5 seconds
    private static final int KEEP_BEHIND_SAMPLES = SAMPLE_RATE / 2;   // 0.5 seconds

    // Per-sender de-jitter: how far the wall-clock arrival position may run ahead of a
    // sender's contiguous write cursor before we treat it as a genuine pause (silence
    // gap) and resync the cursor forward. Below this threshold, consecutive frames from
    // the same speaker are laid down back-to-back so network jitter and packet bursts
    // cannot make them overlap (which sums into clipping) or leave micro-gaps (clicks).
    private static final long RESYNC_THRESHOLD_SAMPLES = SAMPLE_RATE / 5; // 200 ms

    private static final AtomicBoolean AVAILABLE = new AtomicBoolean(false);
    private static final AtomicLong RECEIVED_FRAMES = new AtomicLong(0L);
    private static final AtomicLong MIC_FRAMES = new AtomicLong(0L);

    // Live Simple Voice Chat microphone state, pushed here by
    // RecordableVoicechatPlugin (the only class that touches SVC types) whenever a
    // client event fires or the mute / disable toggles change. Defaults assume the
    // mic is listening, which matches Simple Voice Chat's out-of-the-box state.
    private static volatile boolean svcMicMuted = false;
    private static volatile boolean svcDisabled = false;

    // Optional callback, installed by RecordableVoicechatPlugin, that pulls the
    // LIVE mute / disable state straight from the Simple Voice Chat client API.
    // It is invoked right before a mic-gate decision so the gate reflects the
    // real current state even when no mute / disable event has fired this session
    // (event-pushed flags alone can be stale, e.g. the player was already muted at
    // login). Stored as a plain Runnable so no Simple Voice Chat types leak into
    // this common class.
    private static volatile Runnable stateRefresher;

    // --- Capture state (guarded by LOCK) ---
    private static final Object LOCK = new Object();
    private static volatile boolean capturing = false;
    private static OutputStream sidecarStream;
    private static Path sidecarFile;
    private static long captureStartNanos;
    private static long samplesFlushed;        // mono samples already written to the WAV body
    private static long totalSamplesWritten;    // for the WAV header patch on stop
    private static int[] accumulator;           // additive mix window [samplesFlushed, +WINDOW)
    private static int accHighWater;            // highest touched index in the accumulator
    private static boolean capturedAny;
    // Next absolute write position (in mono samples since capture start) for each speaker,
    // so each sender's frames are placed contiguously instead of at jittery arrival times.
    private static final Map<UUID, Long> senderCursors = new HashMap<>();
    // Fallback key for frames that arrive without a sender id.
    private static final UUID ZERO_UUID = new UUID(0L, 0L);

    private VoiceChatIntegration() {
    }

    /** Marks the Simple Voice Chat plugin API as present and initialised. */
    public static void markAvailable() {
        boolean first = AVAILABLE.compareAndSet(false, true);
        if (first) {
            RecordableMod.LOGGER.info("[Record-able] Simple Voice Chat detected; voice chat audio can be included in recordings.");
        }
    }

    /** Whether the Simple Voice Chat plugin API has been loaded this session. */
    public static boolean isAvailable() {
        return AVAILABLE.get();
    }

    /**
     * Pushes the current Simple Voice Chat microphone state into this holder.
     * Called only from {@link RecordableVoicechatPlugin} (which owns all SVC type
     * references) when a client event fires or the mute / disable state toggles.
     *
     * @param muted    whether the local Simple Voice Chat microphone is muted
     * @param disabled whether Simple Voice Chat is disabled entirely on the client
     */
    public static void updateMicrophoneState(boolean muted, boolean disabled) {
        svcMicMuted = muted;
        svcDisabled = disabled;
    }

    /**
     * Installs a callback that refreshes the cached mute / disable flags from the
     * live Simple Voice Chat client API. Called once by
     * {@link RecordableVoicechatPlugin} when the plugin loads. Passing {@code null}
     * clears it.
     */
    public static void setStateRefresher(Runnable refresher) {
        stateRefresher = refresher;
    }

    /** Pulls the freshest mute / disable state from Simple Voice Chat, if available. */
    private static void refreshState() {
        Runnable r = stateRefresher;
        if (r != null) {
            try {
                r.run();
            } catch (Throwable ignored) {
            }
        }
    }

    /**
     * Whether Simple Voice Chat is present and actively listening to the local
     * microphone right now: the mod is loaded, voice chat is not disabled, and the
     * microphone is not muted. Used to gate Record-able's own microphone capture so
     * the mic is only recorded when Simple Voice Chat is genuinely listening to it.
     */
    public static boolean isMicrophoneListening() {
        if (!AVAILABLE.get()) return false;
        // Pull the live mute / disable state before deciding, so a mic that was
        // already muted / disabled at login (with no event fired yet) is still
        // reported as not listening.
        refreshState();
        return !svcDisabled && !svcMicMuted;
    }

    /**
     * Called from the recording pipeline when a recording starts/stops. Opens (or
     * finalises) the sidecar WAV. No-op unless Simple Voice Chat is present and the
     * {@code includeVoiceChat} config option is enabled.
     */
    public static void setRecording(boolean recording, Path outputDirectory) {
        if (recording) {
            startCapture(outputDirectory);
        } else {
            stopCapture();
        }
    }

    private static void startCapture(Path outputDirectory) {
        if (!AVAILABLE.get()) return;
        try {
            if (!RecordableConfig.get().includeVoiceChat) return;
        } catch (Throwable ignored) {
            return;
        }
        if (outputDirectory == null) return;
        synchronized (LOCK) {
            if (capturing) return;
            try {
                Files.createDirectories(outputDirectory);
                Path file = outputDirectory.resolve("recordable-voicechat-" + System.currentTimeMillis() + ".wav");
                OutputStream out = new BufferedOutputStream(Files.newOutputStream(file,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE));
                writeWavHeader(out, 0);
                sidecarStream = out;
                sidecarFile = file;
                captureStartNanos = System.nanoTime();
                samplesFlushed = 0L;
                totalSamplesWritten = 0L;
                accumulator = new int[WINDOW_SAMPLES];
                accHighWater = 0;
                capturedAny = false;
                senderCursors.clear();
                capturing = true;
                RecordableMod.LOGGER.info("[Record-able] Voice chat capture armed: {}", file);
            } catch (Throwable t) {
                closeQuietly();
                RecordableMod.LOGGER.warn("[Record-able] Failed to arm voice chat capture: {}", t.toString());
            }
        }
    }

    private static void stopCapture() {
        synchronized (LOCK) {
            if (!capturing) return;
            capturing = false;
            try {
                // Flush everything still held in the mixing window.
                flushSamples(accHighWater);
                sidecarStream.flush();
                sidecarStream.close();
                patchWavHeader(sidecarFile, totalSamplesWritten);
                RecordableMod.LOGGER.info("[Record-able] Voice chat capture finished: {} samples ({} bytes) -> {}",
                        totalSamplesWritten, totalSamplesWritten * 2L, sidecarFile);
            } catch (Throwable t) {
                RecordableMod.LOGGER.warn("[Record-able] Failed to finalise voice chat capture: {}", t.toString());
            } finally {
                sidecarStream = null;
                accumulator = null;
                senderCursors.clear();
            }
        }
    }

    /**
     * Receives one decoded 20 ms mono frame of another player's voice and mixes it onto
     * the shared timeline at its arrival position. Never throws to the caller.
     */
    public static void onReceiveAudio(UUID sender, short[] frame) {
        RECEIVED_FRAMES.incrementAndGet();
        if (frame == null || frame.length == 0) return;
        synchronized (LOCK) {
            if (!capturing || accumulator == null) return;
            try {
                mixFrameLocked(sender, frame);
            } catch (Throwable t) {
                RecordableMod.LOGGER.debug("[Record-able] voice chat mix error: {}", t.toString());
            }
        }
    }

    /**
     * Records that the local microphone produced a voice chat frame. Used for status
     * only: the local player's own voice is captured through Record-able's existing
     * microphone feature, so it is not written into the sidecar (which would double it).
     */
    public static void onMicAudio(short[] frame) {
        MIC_FRAMES.incrementAndGet();
    }

    // Must be called while holding LOCK.
    private static void mixFrameLocked(UUID sender, short[] frame) throws IOException {
        // Wall-clock arrival position of this frame, in absolute mono samples since the
        // capture started. This is jittery (network + scheduling), so we only use it to
        // seed or resync a sender's cursor, never to place every frame directly.
        long wallClock = (System.nanoTime() - captureStartNanos) / 1_000_000L * SAMPLE_RATE / 1000L;

        // Per-sender contiguous cursor: place each speaker's frames back-to-back. Only
        // jump to wall-clock when the speaker is new or resumes after a real pause. This
        // removes the jitter-induced overlaps (clipping) and micro-gaps (clicks) that
        // caused the distortion, while packet bursts get laid down sequentially instead
        // of piling onto the same samples.
        UUID key = sender != null ? sender : ZERO_UUID;
        Long cursorObj = senderCursors.get(key);
        long startSample;
        if (cursorObj == null || wallClock - cursorObj > RESYNC_THRESHOLD_SAMPLES) {
            startSample = wallClock;
        } else {
            startSample = cursorObj;
        }

        // Clamp into the live window: never before what we already flushed, and keep the
        // whole frame inside the accumulator bounds so a runaway cursor cannot escape it.
        long minStart = samplesFlushed;
        long maxStart = samplesFlushed + WINDOW_SAMPLES - frame.length;
        if (maxStart < minStart) return; // frame longer than window; should never happen
        if (startSample < minStart) startSample = minStart;
        if (startSample > maxStart) startSample = maxStart;

        // Advance this sender's cursor to sit immediately after the frame we just placed.
        senderCursors.put(key, startSample + frame.length);

        int base = (int) (startSample - samplesFlushed);
        for (int i = 0; i < frame.length; i++) {
            int idx = base + i;
            if (idx < 0 || idx >= WINDOW_SAMPLES) continue;
            accumulator[idx] += frame[i];
            if (idx + 1 > accHighWater) accHighWater = idx + 1;
        }
        capturedAny = true;

        if (accHighWater >= FLUSH_TRIGGER_SAMPLES) {
            int flushCount = accHighWater - KEEP_BEHIND_SAMPLES;
            if (flushCount > 0) flushSamples(flushCount);
        }
    }

    // Writes the first `count` accumulator samples to the WAV body (clamped to 16-bit),
    // then shifts the remaining window down. Must be called while holding LOCK.
    private static void flushSamples(int count) throws IOException {
        if (count <= 0 || accumulator == null || sidecarStream == null) return;
        if (count > accHighWater) count = accHighWater;
        byte[] out = new byte[count * 2];
        for (int i = 0; i < count; i++) {
            int s = softLimit(accumulator[i]);
            out[i * 2] = (byte) (s & 0xFF);
            out[i * 2 + 1] = (byte) ((s >> 8) & 0xFF);
        }
        sidecarStream.write(out);
        totalSamplesWritten += count;
        samplesFlushed += count;
        // Shift the retained tail of the window down to index 0.
        int remaining = accHighWater - count;
        if (remaining > 0) {
            System.arraycopy(accumulator, count, accumulator, 0, remaining);
        }
        for (int i = Math.max(0, remaining); i < accHighWater; i++) {
            accumulator[i] = 0;
        }
        accHighWater = Math.max(0, remaining);
    }

    // Soft-knee limiter. Samples below the knee (normal single-speaker level) pass through
    // untouched; anything above is smoothly tapered into the remaining headroom with a tanh
    // curve, so several people talking at once compress gently instead of hard-clipping into
    // harsh distortion.
    private static int softLimit(int sample) {
        final int max = Short.MAX_VALUE;
        final int min = Short.MIN_VALUE;
        final double knee = 0.8; // 80% of full scale
        double x = sample / 32768.0;
        double ax = Math.abs(x);
        if (ax <= knee) {
            if (sample > max) return max;
            if (sample < min) return min;
            return sample;
        }
        double sign = x < 0 ? -1.0 : 1.0;
        double over = (ax - knee) / (1.0 - knee);
        double shaped = knee + (1.0 - knee) * Math.tanh(over);
        int result = (int) Math.round(sign * shaped * 32768.0);
        if (result > max) result = max;
        if (result < min) result = min;
        return result;
    }

    private static void closeQuietly() {
        try {
            if (sidecarStream != null) sidecarStream.close();
        } catch (Throwable ignored) {
        }
        sidecarStream = null;
        accumulator = null;
        capturing = false;
    }

    /** The finished sidecar WAV path, or {@code null} if none was produced. */
    public static Path getSidecarFile() {
        return sidecarFile;
    }

    /** Whether a finished sidecar WAV with real audio data exists. */
    public static boolean hasCapturedData() {
        Path f = sidecarFile;
        if (f == null || !capturedAny) return false;
        try {
            return Files.exists(f) && Files.size(f) > 44L;
        } catch (IOException e) {
            return false;
        }
    }

    /** Clears the reference to the sidecar file (after it has been consumed/deleted). */
    public static void clearSidecar() {
        sidecarFile = null;
        capturedAny = false;
    }

    /** Short human-readable status line for diagnostics / settings display. */
    public static String statusSummary() {
        if (!AVAILABLE.get()) return "Simple Voice Chat: not installed";
        long recv = RECEIVED_FRAMES.get();
        long mic = MIC_FRAMES.get();
        return "Simple Voice Chat: connected (received=" + recv + " frames, mic=" + mic + " frames)";
    }

    // --- Minimal WAV (PCM) writer ---

    private static void writeWavHeader(OutputStream out, int dataBytes) throws IOException {
        int byteRate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;
        int blockAlign = CHANNELS * BITS_PER_SAMPLE / 8;
        int chunkSize = 36 + dataBytes;
        out.write(new byte[]{'R', 'I', 'F', 'F'});
        writeIntLE(out, chunkSize);
        out.write(new byte[]{'W', 'A', 'V', 'E'});
        out.write(new byte[]{'f', 'm', 't', ' '});
        writeIntLE(out, 16);                 // PCM fmt chunk size
        writeShortLE(out, 1);                // audio format = PCM
        writeShortLE(out, CHANNELS);
        writeIntLE(out, SAMPLE_RATE);
        writeIntLE(out, byteRate);
        writeShortLE(out, blockAlign);
        writeShortLE(out, BITS_PER_SAMPLE);
        out.write(new byte[]{'d', 'a', 't', 'a'});
        writeIntLE(out, dataBytes);
    }

    private static void patchWavHeader(Path file, long totalSamples) {
        if (file == null) return;
        try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file.toFile(), "rw")) {
            long dataBytes = totalSamples * (BITS_PER_SAMPLE / 8) * CHANNELS;
            long chunkSize = 36 + dataBytes;
            raf.seek(4);
            raf.write(intLE((int) chunkSize));
            raf.seek(40);
            raf.write(intLE((int) dataBytes));
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("[Record-able] Failed to patch voice chat WAV header: {}", t.toString());
        }
    }

    private static void writeIntLE(OutputStream out, int v) throws IOException {
        out.write(intLE(v));
    }

    private static void writeShortLE(OutputStream out, int v) throws IOException {
        out.write(v & 0xFF);
        out.write((v >> 8) & 0xFF);
    }

    private static byte[] intLE(int v) {
        return new byte[]{
                (byte) (v & 0xFF),
                (byte) ((v >> 8) & 0xFF),
                (byte) ((v >> 16) & 0xFF),
                (byte) ((v >> 24) & 0xFF)
        };
    }
}
