package dev.recordable;

/**
 * Lightweight, allocation-free validation helpers for captured RGB frames.
 *
 * <p>Used to detect the "black screen recording" failure mode, where the
 * capture pipeline (wrong framebuffer bound, GPU driver issue, headless
 * context, etc.) produces frames that are entirely (or almost entirely)
 * black. Detecting this early lets the mod auto-recover (switch capture
 * source) and warn the user instead of silently producing an unusable file.</p>
 *
 * <p>All methods are pure functions over a packed {@code RGB24} top-down byte
 * array ({@code [r,g,b, r,g,b, ...]}). They sample a sparse grid of pixels so
 * the cost stays negligible even at 1080p/60.</p>
 */
public final class FrameValidator {

    /**
     * Per-channel value (0-255) at or below which a sampled pixel is considered
     * "black". A small non-zero threshold tolerates compression dithering and
     * very dark (but not truly empty) scenes such as caves or night.
     */
    public static final int BLACK_PIXEL_THRESHOLD = 8;

    /**
     * Fraction of sampled pixels that must be black for the whole frame to be
     * classified as black. A frame that is 99%+ black is treated as a failed
     * capture rather than a legitimately dark scene.
     */
    public static final double BLACK_FRAME_RATIO = 0.995;

    /** Number of samples taken along each axis (grid is SAMPLES x SAMPLES). */
    private static final int SAMPLES_PER_AXIS = 32;

    private FrameValidator() {
    }

    /**
     * Returns the mean per-pixel brightness (0-255) over a sparse grid of the
     * given RGB24 frame. Returns {@code -1} when the buffer is null/too small.
     */
    public static double averageBrightness(byte[] rgb, int width, int height) {
        if (rgb == null || width <= 0 || height <= 0) {
            return -1.0;
        }
        long required = (long) width * height * 3L;
        if (rgb.length < required) {
            return -1.0;
        }

        long sum = 0L;
        int count = 0;
        int stepX = Math.max(1, width / SAMPLES_PER_AXIS);
        int stepY = Math.max(1, height / SAMPLES_PER_AXIS);

        for (int y = 0; y < height; y += stepY) {
            int rowBase = y * width * 3;
            for (int x = 0; x < width; x += stepX) {
                int idx = rowBase + x * 3;
                int r = rgb[idx] & 0xFF;
                int g = rgb[idx + 1] & 0xFF;
                int b = rgb[idx + 2] & 0xFF;
                // Rec. 601 luma approximation (integer-friendly).
                sum += (r * 77 + g * 150 + b * 29) >> 8;
                count++;
            }
        }
        return count == 0 ? -1.0 : (double) sum / count;
    }

    /**
     * Returns {@code true} when the frame is (almost) entirely black, i.e. at
     * least {@link #BLACK_FRAME_RATIO} of the sampled pixels are at/below
     * {@link #BLACK_PIXEL_THRESHOLD} on every channel.
     */
    public static boolean isBlackFrame(byte[] rgb, int width, int height) {
        if (rgb == null || width <= 0 || height <= 0) {
            return false;
        }
        long required = (long) width * height * 3L;
        if (rgb.length < required) {
            return false;
        }

        int total = 0;
        int black = 0;
        int stepX = Math.max(1, width / SAMPLES_PER_AXIS);
        int stepY = Math.max(1, height / SAMPLES_PER_AXIS);

        for (int y = 0; y < height; y += stepY) {
            int rowBase = y * width * 3;
            for (int x = 0; x < width; x += stepX) {
                int idx = rowBase + x * 3;
                int r = rgb[idx] & 0xFF;
                int g = rgb[idx + 1] & 0xFF;
                int b = rgb[idx + 2] & 0xFF;
                total++;
                if (r <= BLACK_PIXEL_THRESHOLD && g <= BLACK_PIXEL_THRESHOLD && b <= BLACK_PIXEL_THRESHOLD) {
                    black++;
                }
            }
        }
        if (total == 0) {
            return false;
        }
        return (double) black / total >= BLACK_FRAME_RATIO;
    }
}
