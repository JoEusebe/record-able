package dev.recordable;

import java.util.ArrayList;
import java.util.List;

/**
 * V1-0.08 Streamer Mode.
 *
 * <p>Applies privacy "censor" rectangles to every recorded frame so streamers can
 * keep sensitive on-screen information out of the saved video. All pixel work is
 * pure Java operating directly on the top-down RGB24 buffer produced by
 * {@code ScreenCapture}, so the same code path runs identically on every variant
 * (legacy / sandwich / modern) and on Android.</p>
 *
 * <p>Regions are stored in the config as fractions of the frame, so they stay
 * correct at any recording resolution.</p>
 *
 * <p><b>V1-0.08 censor upgrade:</b> every region is a fully solid, stretchable
 * overlay. Fills are 100% opaque (no transparency, no pixelation, no blur), so a
 * viewer cannot recover the hidden content by averaging frames or boosting levels.
 * Each region is either a single custom colour or a two-colour gradient, and can
 * have a custom text label painted directly onto it via {@link CensorFont}.</p>
 */
public final class StreamerModeManager {

    private static final StreamerModeManager INSTANCE = new StreamerModeManager();

    private StreamerModeManager() {}

    public static StreamerModeManager getInstance() {
        return INSTANCE;
    }

    /**
     * Whether streamer-mode censoring should run for the current frame. True only
     * when the master toggle is on and at least one enabled region exists.
     */
    public boolean isActive() {
        RecordableConfig config = RecordableConfig.get();
        if (config == null || !config.streamerModeEnabled) {
            return false;
        }
        List<CensorRegion> regions = config.censorRegions;
        if (regions == null || regions.isEmpty()) {
            return false;
        }
        for (CensorRegion r : regions) {
            if (r != null && r.enabled) {
                return true;
            }
        }
        return false;
    }

    /** Returns a defensive snapshot of the currently enabled censor regions. */
    public List<CensorRegion> getActiveRegions() {
        List<CensorRegion> out = new ArrayList<>();
        RecordableConfig config = RecordableConfig.get();
        if (config == null || config.censorRegions == null) {
            return out;
        }
        for (CensorRegion r : config.censorRegions) {
            if (r != null && r.enabled) {
                out.add(r);
            }
        }
        return out;
    }

    /**
     * Applies every enabled censor region to the given top-down RGB24 frame, in place.
     *
     * @param rgb    the frame pixels (3 bytes per pixel, row-major, top-down)
     * @param width  frame width in pixels
     * @param height frame height in pixels
     */
    public void applyCensoring(byte[] rgb, int width, int height) {
        if (rgb == null || width <= 0 || height <= 0) {
            return;
        }
        if (rgb.length < (long) width * height * 3) {
            return;
        }
        RecordableConfig config = RecordableConfig.get();
        if (config == null || !config.streamerModeEnabled || config.censorRegions == null) {
            return;
        }
        for (CensorRegion region : config.censorRegions) {
            if (region == null || !region.enabled) {
                continue;
            }
            int px = (int) Math.round(region.x * width);
            int py = (int) Math.round(region.y * height);
            int pw = (int) Math.round(region.width * width);
            int ph = (int) Math.round(region.height * height);

            // Clamp to frame bounds.
            if (px < 0) { pw += px; px = 0; }
            if (py < 0) { ph += py; py = 0; }
            if (px + pw > width) pw = width - px;
            if (py + ph > height) ph = height - py;
            if (pw <= 0 || ph <= 0) {
                continue;
            }

            CensorRegion.Style style = region.style != null ? region.style : CensorRegion.Style.SOLID;
            if (style == CensorRegion.Style.GRADIENT) {
                fillGradient(rgb, width, px, py, pw, ph,
                        region.color & 0xFFFFFF, region.colorEnd & 0xFFFFFF,
                        region.gradientDirection != null
                                ? region.gradientDirection
                                : CensorRegion.GradientDirection.HORIZONTAL);
            } else {
                fillSolid(rgb, width, px, py, pw, ph, region.color & 0xFFFFFF);
            }

            // Paint the label onto the censor block, fully opaque.
            if (region.showLabel && region.label != null && !region.label.isBlank()) {
                drawLabel(rgb, width, height, px, py, pw, ph,
                        region.label.trim(), region.textColor & 0xFFFFFF);
            }
        }
    }

    /** Fills the region with a single flat, fully opaque colour. */
    private static void fillSolid(byte[] rgb, int frameWidth, int x, int y, int w, int h, int color) {
        byte cr = (byte) ((color >> 16) & 0xFF);
        byte cg = (byte) ((color >> 8) & 0xFF);
        byte cb = (byte) (color & 0xFF);
        int rowStride = frameWidth * 3;
        for (int row = y; row < y + h; row++) {
            int base = row * rowStride + x * 3;
            int end = base + w * 3;
            for (int i = base; i < end; i += 3) {
                rgb[i] = cr;
                rgb[i + 1] = cg;
                rgb[i + 2] = cb;
            }
        }
    }

    /**
     * Fills the region with a fully opaque two-colour gradient. The interpolation
     * factor sweeps along the chosen direction from {@code colorStart} to
     * {@code colorEnd}.
     */
    private static void fillGradient(byte[] rgb, int frameWidth, int x, int y, int w, int h,
                                     int colorStart, int colorEnd,
                                     CensorRegion.GradientDirection direction) {
        int sr = (colorStart >> 16) & 0xFF;
        int sg = (colorStart >> 8) & 0xFF;
        int sb = colorStart & 0xFF;
        int er = (colorEnd >> 16) & 0xFF;
        int eg = (colorEnd >> 8) & 0xFF;
        int eb = colorEnd & 0xFF;
        int rowStride = frameWidth * 3;

        int denomX = Math.max(1, w - 1);
        int denomY = Math.max(1, h - 1);
        int denomD = Math.max(1, (w - 1) + (h - 1));

        for (int row = 0; row < h; row++) {
            int base = (y + row) * rowStride + x * 3;
            for (int col = 0; col < w; col++) {
                double t;
                switch (direction) {
                    case VERTICAL -> t = (double) row / denomY;
                    case DIAGONAL -> t = (double) (col + row) / denomD;
                    default -> t = (double) col / denomX;
                }
                if (t < 0.0) t = 0.0;
                else if (t > 1.0) t = 1.0;
                int idx = base + col * 3;
                rgb[idx] = (byte) Math.round(sr + (er - sr) * t);
                rgb[idx + 1] = (byte) Math.round(sg + (eg - sg) * t);
                rgb[idx + 2] = (byte) Math.round(sb + (eb - sb) * t);
            }
        }
    }

    /**
     * Paints the region label centered inside the censor block. The text scale is
     * chosen to fit the block with a small margin; if the block is too small for
     * even a single-pixel-scale glyph, the label is skipped.
     */
    private static void drawLabel(byte[] rgb, int frameWidth, int frameHeight,
                                  int x, int y, int w, int h, String label, int textColor) {
        int baseWidth = CensorFont.textWidth(label);
        if (baseWidth <= 0) {
            return;
        }
        // Fit within ~85% of width and ~60% of height.
        int maxByWidth = (int) Math.floor((w * 0.85) / baseWidth);
        int maxByHeight = (int) Math.floor((h * 0.60) / CensorFont.GLYPH_H);
        int scale = Math.min(maxByWidth, maxByHeight);
        if (scale < 1) {
            return;
        }
        int textW = baseWidth * scale;
        int textH = CensorFont.GLYPH_H * scale;
        int startX = x + (w - textW) / 2;
        int startY = y + (h - textH) / 2;
        CensorFont.drawText(rgb, frameWidth, frameHeight, startX, startY, label, textColor, scale);
    }
}
