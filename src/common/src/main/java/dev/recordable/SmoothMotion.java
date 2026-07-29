package dev.recordable;

/**
 * V1-0.08 "Lunar rewind" style recording smoothness.
 *
 * <p>The capture pipeline feeds FFmpeg a constant-frame-rate stream and duplicates
 * the most recent frame whenever the game renders slower than the target FPS. Pure
 * duplication keeps the duration correct but looks juddery on playback. Lunar
 * Client's recordings feel smooth because motion between frames is blended /
 * interpolated rather than hard-stepped.</p>
 *
 * <p>This helper builds the FFmpeg {@code -vf} filter expression that recreates that
 * effect. It is pure Java (shared by every variant) so the per-variant
 * {@code FFmpegEncoder} and the {@code ReplayBuffer} can both reuse the exact same
 * logic. When smoothing is disabled it returns {@code null} and the encoder adds no
 * filter at all (zero overhead).</p>
 */
public final class SmoothMotion {

    /** Smoothing modes, cheapest to heaviest. */
    public static final String MODE_BLEND = "blend";
    public static final String MODE_MOTION = "motion";
    public static final String[] MODES = {MODE_BLEND, MODE_MOTION};

    private SmoothMotion() {}

    /**
     * Builds the {@code -vf} filter string for the given config and output FPS, or
     * {@code null} if smooth motion is disabled.
     *
     * @param config the active configuration
     * @param outputFps the recording's constant output frame rate
     * @return an FFmpeg filtergraph string (e.g. {@code minterpolate=fps=60:mi_mode=blend})
     *         or {@code null} when no smoothing should be applied
     */
    public static String buildFilter(RecordableConfig config, int outputFps) {
        if (config == null || !config.smoothMotionEnabled) {
            return null;
        }
        int fps = Math.max(1, outputFps);
        String mode = sanitizeMode(config.smoothMotionMode);

        if (MODE_MOTION.equals(mode)) {
            // Motion-compensated interpolation - true Lunar-style smoothness. Heavier
            // on CPU but produces genuinely interpolated intermediate motion, which
            // smooths out the duplicated frames inserted during render hitches.
            // aobmc + vsbmc reduce the blocky artefacts that plain mci can produce.
            return "minterpolate=fps=" + fps
                    + ":mi_mode=mci:mc_mode=aobmc:me_mode=bidir:vsbmc=1";
        }

        // Default: frame blending. Light on CPU, removes most of the stutter by
        // cross-fading between successive frames.
        return "minterpolate=fps=" + fps + ":mi_mode=blend";
    }

    /** Returns a human-friendly label for a smoothing mode (UI + logs). */
    public static String describe(String mode) {
        return switch (sanitizeMode(mode)) {
            case MODE_MOTION -> "Motion (smoothest, heavier CPU)";
            default -> "Blend (light, balanced)";
        };
    }

    /** Normalizes an arbitrary string to one of {@link #MODES}. */
    public static String sanitizeMode(String mode) {
        if (mode == null) return MODE_BLEND;
        String m = mode.trim().toLowerCase();
        for (String valid : MODES) {
            if (valid.equals(m)) return valid;
        }
        return MODE_BLEND;
    }
}
