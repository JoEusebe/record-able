package dev.recordable;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared runtime state for microphone capture and the Push-to-Talk (PTT) feature.
 *
 * <p>This holder lives in the common source set so all three build variants
 * (legacy / sandwich / modern) and the per-build {@code FFmpegEncoder},
 * {@code RecordableModInit} (keybind tick) and {@code RecordingOverlay} read and
 * write the <em>same</em> state.</p>
 *
 * <h3>How Push-to-Talk works</h3>
 * <p>The microphone is recorded to its own WAV for the whole recording (a second
 * FFmpeg process). When PTT is enabled we record the wall-clock intervals during
 * which the PTT key was held. At mux time {@code FFmpegEncoder} converts those
 * intervals (relative to when the mic stream started) into an FFmpeg
 * {@code volume} timeline expression so the mic track is only audible while the
 * key was held. This is fully deterministic and avoids start/stop latency from
 * trying to toggle the live FFmpeg mic process.</p>
 */
public final class MicrophoneState {

    private static final Object LOCK = new Object();

    /** True while a mic capture stream is active for the current recording. */
    private static volatile boolean micCapturing = false;
    /** Snapshot of {@code config.microphonePushToTalk} for the current recording. */
    private static volatile boolean pushToTalkMode = false;
    /** True while the PTT key is currently held down. */
    private static volatile boolean pushToTalkHeld = false;
    /** nanoTime at which the mic stream started (reference for interval math). */
    private static volatile long micStartNanos = 0L;

    /** Held intervals as {startNanos, endNanos}; endNanos == 0 means still open. */
    private static final List<long[]> heldIntervals = new ArrayList<>();

    private MicrophoneState() {}

    /**
     * Marks the start of a recording's microphone capture.
     *
     * @param capturing whether a mic stream is actually being captured
     * @param pttMode   whether Push-to-Talk gating is enabled for this recording
     * @param startNanos {@code System.nanoTime()} when the mic stream started
     */
    public static void beginRecording(boolean capturing, boolean pttMode, long startNanos) {
        synchronized (LOCK) {
            micCapturing = capturing;
            pushToTalkMode = pttMode;
            micStartNanos = startNanos;
            pushToTalkHeld = false;
            heldIntervals.clear();
        }
    }

    /** Marks the end of mic capture, closing any open held interval. */
    public static void endRecording() {
        synchronized (LOCK) {
            closeOpenIntervalLocked(System.nanoTime());
            micCapturing = false;
            pushToTalkHeld = false;
        }
    }

    /**
     * Feeds the current held state of the PTT key. Called every client tick by
     * the keybinding handler. Records press/release transitions as intervals
     * while a PTT-mode recording is active.
     */
    public static void setPushToTalkHeld(boolean held) {
        synchronized (LOCK) {
            if (micCapturing && pushToTalkMode) {
                long now = System.nanoTime();
                if (held && !pushToTalkHeld) {
                    heldIntervals.add(new long[]{now, 0L});
                } else if (!held && pushToTalkHeld) {
                    closeOpenIntervalLocked(now);
                }
            }
            pushToTalkHeld = held;
        }
    }

    private static void closeOpenIntervalLocked(long endNanos) {
        if (!heldIntervals.isEmpty()) {
            long[] last = heldIntervals.get(heldIntervals.size() - 1);
            if (last[1] == 0L) {
                last[1] = endNanos;
            }
        }
    }

    /** Whether Push-to-Talk gating is active for the current recording. */
    public static boolean isPushToTalkMode() {
        return pushToTalkMode;
    }

    /** Whether the PTT key is currently held. */
    public static boolean isPushToTalkHeld() {
        return pushToTalkHeld;
    }

    /**
     * Whether the mic should be shown as "live" in the HUD overlay right now.
     * Always-on mode: live whenever a mic stream is capturing. PTT mode: live
     * only while the key is held.
     */
    public static boolean isMicActiveForDisplay() {
        if (!micCapturing) {
            return false;
        }
        return !pushToTalkMode || pushToTalkHeld;
    }

    /** Whether a mic stream is currently being captured for the recording. */
    public static boolean isMicCapturing() {
        return micCapturing;
    }

    /**
     * Returns the PTT held intervals in seconds, relative to the supplied mic
     * stream start time. Returns {@code null} when not in PTT mode (mic is always
     * on). Returns an empty list when PTT mode is on but the key was never held
     * (mic should be fully muted).
     *
     * @param micStreamStartNanos the authoritative mic stream start, supplied by
     *                            the encoder (so it matches the actual WAV t=0)
     */
    public static List<double[]> getPushToTalkIntervalsSeconds(long micStreamStartNanos) {
        if (!pushToTalkMode) {
            return null;
        }
        long ref = micStreamStartNanos > 0L ? micStreamStartNanos : micStartNanos;
        List<double[]> out = new ArrayList<>();
        synchronized (LOCK) {
            long now = System.nanoTime();
            for (long[] iv : heldIntervals) {
                long s = iv[0];
                long e = iv[1] == 0L ? now : iv[1];
                double ss = (s - ref) / 1_000_000_000.0;
                double ee = (e - ref) / 1_000_000_000.0;
                if (ee <= 0) {
                    continue;
                }
                if (ss < 0) {
                    ss = 0;
                }
                if (ee > ss) {
                    out.add(new double[]{ss, ee});
                }
            }
        }
        return out;
    }
}
