package dev.recordable;

import dev.recordable.filter.FilterType;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/**
 * Renders a real-time visual approximation of a video filter on screen
 * using Minecraft's {@link GuiGraphicsExtractor} primitives.
 *
 * <p>This is an approximation. The original filters operated on raw RGB pixel data,
 * but here we use filled rectangles, color tints, and scanline overlays to give the
 * player a reasonable preview of what a recorded video would look like.</p>
 *
 * <p>All rendering uses simple {@code context.fill()} calls for minimal GPU overhead.
 * The preview is drawn as a full-screen underlay before HUD elements so overlay text
 * stays readable. Controlled by {@link RecordableConfig#showFiltersLive}.</p>
 */
public final class FilterPreviewRenderer {

    /** Tick counter for animated effects (scanline drift, blink, noise variation). */
    private static int tick;

    private FilterPreviewRenderer() {
    }

    /**
     * Render the filter preview overlay for a single filter type.
     *
     * @param context    Minecraft draw context
     * @param screenW    scaled screen width
     * @param screenH    scaled screen height
     * @param filterType active filter type (ignored when NONE/null)
     * @param intensity  filter intensity 0 to 100
     */
    public static void render(GuiGraphicsExtractor context, int screenW, int screenH,
                              FilterType filterType, int intensity) {
        if (filterType == null || filterType == FilterType.NONE) return;
        tick++;

        float t = Math.max(0f, Math.min(1f, intensity / 100f));

        switch (filterType) {
            case VHS -> renderVhs(context, screenW, screenH, t);
            case LCD_MOIRE -> renderLcdMoire(context, screenW, screenH, t);
            case CRT -> renderCrt(context, screenW, screenH, t);
            default -> {}
        }
    }

    // VHS preview: warm amber tint, scanlines, tracking band, noise, edge fringe.
    private static void renderVhs(GuiGraphicsExtractor context, int w, int h, float t) {
        int tintAlpha = (int) (30 * t);
        if (tintAlpha > 0) {
            context.fill(0, 0, w, h, argb(tintAlpha, 180, 120, 40));
        }

        int scanAlpha = (int) (25 * t);
        if (scanAlpha > 0) {
            int scanColor = argb(scanAlpha, 0, 0, 0);
            int offset = (tick / 2) % 4;
            for (int y = offset; y < h; y += 4) {
                context.fill(0, y, w, y + 1, scanColor);
            }
        }

        int trackingY = ((tick * 3) / 2) % (h + 60) - 30;
        int trackAlpha = (int) (18 * t);
        if (trackAlpha > 0 && trackingY > -10 && trackingY < h) {
            int bandH = 3 + (tick % 3);
            context.fill(0, trackingY, w, Math.min(h, trackingY + bandH),
                    argb(trackAlpha, 200, 200, 200));
        }

        if ((tick % 8) < 2 && t > 0.3f) {
            int noiseY = (tick * 7 + 41) % h;
            int noiseH = 1 + (tick % 2);
            int noiseAlpha = (int) (12 * t);
            context.fill(0, noiseY, w, Math.min(h, noiseY + noiseH),
                    argb(noiseAlpha, 255, 255, 255));
        }

        int fringeAlpha = (int) (15 * t);
        if (fringeAlpha > 0) {
            int fringeW = Math.max(1, (int) (3 * t));
            context.fill(0, 0, fringeW, h, argb(fringeAlpha, 255, 60, 60));
            context.fill(w - fringeW, 0, w, h, argb(fringeAlpha, 60, 60, 255));
        }
    }

    // LCD Moire preview: dimming, RGB subpixel stripes, animated interference bands.
    private static void renderLcdMoire(GuiGraphicsExtractor context, int w, int h, float t) {
        int dimAlpha = (int) (15 * t);
        if (dimAlpha > 0) {
            context.fill(0, 0, w, h, argb(dimAlpha, 0, 0, 0));
        }

        int subAlpha = (int) (12 * t);
        if (subAlpha > 0) {
            for (int x = 0; x < w; x += 3) {
                int phase = x % 3;
                int r = phase == 0 ? 255 : 0;
                int g = phase == 1 ? 255 : 0;
                int b = phase == 2 ? 255 : 0;
                context.fill(x, 0, x + 1, h, argb(subAlpha, r, g, b));
            }
        }

        int moireAlpha = (int) (10 * t);
        if (moireAlpha > 0) {
            float phase = tick * 0.05f;
            for (int y = 0; y < h; y += 6) {
                float sine = (float) Math.sin(y * 0.15 + phase);
                int bandAlpha = (int) (moireAlpha * Math.abs(sine));
                if (bandAlpha > 0) {
                    context.fill(0, y, w, y + 3, argb(bandAlpha, 128, 128, 128));
                }
            }
        }
    }

    // CRT preview: scanlines and phosphor glow.
    private static void renderCrt(GuiGraphicsExtractor context, int w, int h, float t) {
        int scanAlpha = (int) (30 * t);
        if (scanAlpha > 0) {
            int scanColor = argb(scanAlpha, 0, 0, 0);
            for (int y = 0; y < h; y += 3) {
                context.fill(0, y, w, y + 1, scanColor);
            }
        }

        int glowAlpha = (int) (8 * t);
        if (glowAlpha > 0) {
            context.fill(0, 0, w, h, argb(glowAlpha, 255, 240, 200));
        }
    }

    /** Build an ARGB color int from components. */
    private static int argb(int a, int r, int g, int b) {
        return ((a & 0xFF) << 24) | ((r & 0xFF) << 16) | ((g & 0xFF) << 8) | (b & 0xFF);
    }
}
