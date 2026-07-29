package dev.recordable.theme;

import dev.recordable.RecordableConfig;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import java.util.Random;

/**
 * Renders retro VHS / film visual effects over UI panels.
 * Effects include: scanlines, film grain, chromatic aberration hint,
 * VHS glitch bars, vignette, and animated tape-loading bars.
 *
 * <p>All effects read their intensity from {@link ThemeEngine}.</p>
 */
public final class VhsEffectsRenderer {
    private static final Random RNG = new Random();
    private static long lastGlitchTick = 0;
    private static int glitchY = 0;
    private static int glitchHeight = 0;
    private static boolean glitchActive = false;

    private VhsEffectsRenderer() {}

    /**
     * Render all enabled effects over the given rectangular area.
     * Call AFTER drawing your panel background, BEFORE drawing text/widgets.
     */
    public static void renderOverPanel(GuiGraphicsExtractor ctx, int left, int top, int right, int bottom) {
        ThemeEngine engine = ThemeEngine.get();
        ThemeColors colors = engine.colors();
        ThemePreset preset = engine.preset();
        boolean vhsStyle = preset == ThemePreset.VHS;
        boolean cinemaStyle = preset == ThemePreset.CINEMA;
        boolean neonStyle = preset == ThemePreset.NEON;

        if ((vhsStyle || neonStyle) && engine.scanlineEnabled()) {
            renderScanlines(ctx, left, top, right, bottom, colors.scanlineColor);
        }
        if ((vhsStyle || cinemaStyle || neonStyle) && engine.grainEnabled()) {
            renderFilmGrain(ctx, left, top, right, bottom, colors.grainColor);
        }
        if (preset == ThemePreset.GALAXY) {
            renderGalaxyStars(ctx, left, top, right, bottom, colors);
        }
        if ((vhsStyle || neonStyle) && engine.glitchEnabled()) {
            renderGlitchBars(ctx, left, top, right, bottom, colors.glitchColor);
        }
        if (engine.vignetteEnabled()) {
            renderVignette(ctx, left, top, right, bottom, colors.vignetteColor);
        }
    }

    /** Horizontal scanlines every 2 pixels. */
    public static void renderScanlines(GuiGraphicsExtractor ctx, int left, int top, int right, int bottom, int color) {
        if ((color & 0xFF000000) == 0) return;
        for (int y = top; y < bottom; y += 2) {
            ctx.fill(left, y, right, y + 1, color);
        }
    }

    /** Random single-pixel noise dots. */
    public static void renderFilmGrain(GuiGraphicsExtractor ctx, int left, int top, int right, int bottom, int color) {
        if ((color & 0xFF000000) == 0) return;
        int w = right - left;
        int h = bottom - top;
        int count = Math.max(20, (w * h) / 400);
        int baseAlpha = (color >> 24) & 0xFF;
        for (int i = 0; i < count; i++) {
            int x = left + RNG.nextInt(w);
            int y = top + RNG.nextInt(h);
            int alpha = Math.max(1, baseAlpha / 2 + RNG.nextInt(Math.max(1, baseAlpha / 2)));
            int grainColor = (alpha << 24) | (color & 0x00FFFFFF);
            ctx.fill(x, y, x + 1, y + 1, grainColor);
        }
    }

    /** Random horizontal glitch bars that appear briefly. */
    public static void renderGlitchBars(GuiGraphicsExtractor ctx, int left, int top, int right, int bottom, int color) {
        if ((color & 0xFF000000) == 0) return;
        long now = System.currentTimeMillis();
        // Glitch every 3-8 seconds for 100-300ms
        if (!glitchActive && now - lastGlitchTick > 3000 + RNG.nextInt(5000)) {
            glitchActive = true;
            lastGlitchTick = now;
            glitchY = top + RNG.nextInt(Math.max(1, bottom - top - 10));
            glitchHeight = 2 + RNG.nextInt(6);
        }
        if (glitchActive) {
            if (now - lastGlitchTick > 100 + RNG.nextInt(200)) {
                glitchActive = false;
            } else {
                int shift = -3 + RNG.nextInt(7);
                ctx.fill(left + shift, glitchY, right + shift, Math.min(bottom, glitchY + glitchHeight), color);
                // Secondary thinner glitch line
                int y2 = glitchY + 8 + RNG.nextInt(20);
                if (y2 < bottom) {
                    ctx.fill(left - shift, y2, right - shift, Math.min(bottom, y2 + 2), color & 0x80FFFFFF);
                }
            }
        }
    }

    /** Dark corners vignette effect. */
    public static void renderVignette(GuiGraphicsExtractor ctx, int left, int top, int right, int bottom, int color) {
        if ((color & 0xFF000000) == 0) return;
        int w = right - left;
        int h = bottom - top;
        int borderW = Math.max(6, w / 12);
        int borderH = Math.max(6, h / 12);
        int alpha = (color >> 24) & 0xFF;
        // Gradient approximation with 4 layers of decreasing alpha
        for (int layer = 0; layer < 4; layer++) {
            int layerAlpha = alpha * (4 - layer) / 6;
            int c = (layerAlpha << 24);
            int inset = layer * (borderW / 4);
            // Top
            ctx.fill(left + inset, top + inset, right - inset, top + borderH - layer * (borderH / 4), c);
            // Bottom
            ctx.fill(left + inset, bottom - borderH + layer * (borderH / 4), right - inset, bottom - inset, c);
            // Left
            ctx.fill(left + inset, top + borderH - layer * (borderH / 4), left + borderW - layer * (borderW / 4), bottom - borderH + layer * (borderH / 4), c);
            // Right
            ctx.fill(right - borderW + layer * (borderW / 4), top + borderH - layer * (borderH / 4), right - inset, bottom - borderH + layer * (borderH / 4), c);
        }
    }

    // ── Decorative elements ──

    /** Render VHS-style tracking lines at the top of a panel. */
    public static void renderTrackingNoise(GuiGraphicsExtractor ctx, int left, int top, int right, int lineCount) {
        ThemeColors colors = ThemeEngine.get().colors();
        int alpha = Math.max(10, ((colors.scanlineColor >> 24) & 0xFF) / 2);
        for (int i = 0; i < lineCount; i++) {
            int y = top + i * 2;
            int xOff = RNG.nextInt(5) - 2;
            ctx.fill(left + xOff, y, right + xOff, y + 1, (alpha << 24) | 0x00FFFFFF);
        }
    }

    /** Film sprocket holes along a vertical edge. */
    public static void renderSprocketHoles(GuiGraphicsExtractor ctx, int x, int top, int bottom, int color) {
        int spacing = 18;
        int holeW = 6;
        int holeH = 4;
        for (int y = top + 6; y < bottom - 6; y += spacing) {
            ctx.fill(x, y, x + holeW, y + holeH, color);
            ctx.fill(x + 1, y + 1, x + holeW - 1, y + holeH - 1, 0xFF000000);
        }
    }

    /** Render a film-strip border on left and right edges. */
    public static void renderFilmStripBorders(GuiGraphicsExtractor ctx, int left, int top, int right, int bottom, int borderWidth) {
        ThemeColors colors = ThemeEngine.get().colors();
        int stripColor = colors.panelBorder;
        // Left strip
        ctx.fill(left, top, left + borderWidth, bottom, stripColor);
        renderSprocketHoles(ctx, left + 2, top, bottom, colors.accent);
        // Right strip
        ctx.fill(right - borderWidth, top, right, bottom, stripColor);
        renderSprocketHoles(ctx, right - borderWidth + 2, top, bottom, colors.accent);
    }

    /** Animated tape loading bar (progress 0.0-1.0). */
    public static void renderTapeLoadingBar(GuiGraphicsExtractor ctx, int left, int y, int right, float progress) {
        ThemeColors colors = ThemeEngine.get().colors();
        int h = 3;
        ctx.fill(left, y, right, y + h, colors.panelBorder);
        int filled = (int) ((right - left) * Math.max(0, Math.min(1, progress)));
        ctx.fill(left, y, left + filled, y + h, colors.accent);
        // Shimmer
        if (progress < 1.0f) {
            int shimmerX = left + filled;
            ctx.fill(shimmerX, y, Math.min(shimmerX + 4, right), y + h, colors.accentHover);
        }
    }

    /** VCR-style "PLAY ▶" indicator. */
    public static void renderVcrPlayBadge(GuiGraphicsExtractor ctx, net.minecraft.client.gui.Font font, int x, int y) {
        ThemeColors colors = ThemeEngine.get().colors();
        float pulse = ThemeEngine.pulse(System.currentTimeMillis(), 2000);
        int alpha = (int) (180 + 75 * pulse);
        int textColor = (alpha << 24) | (colors.textPrimary & 0x00FFFFFF);
        ctx.text(font, "▶ PLAY", x, y, textColor, true);
    }

    /** REC blinking dot. */
    public static void renderRecDot(GuiGraphicsExtractor ctx, int x, int y, int radius) {
        ThemeColors colors = ThemeEngine.get().colors();
        float pulse = ThemeEngine.pulse(System.currentTimeMillis(), 1000);
        int alpha = (int) (100 + 155 * pulse);
        int dotColor = (alpha << 24) | (colors.accent & 0x00FFFFFF);
        ctx.fill(x - radius, y - radius, x + radius, y + radius, dotColor);
    }

    private static void renderGalaxyStars(GuiGraphicsExtractor ctx, int left, int top, int right, int bottom, ThemeColors colors) {
        int w = right - left;
        int h = bottom - top;
        if (w <= 0 || h <= 0) {
            return;
        }
        int glowIntensity = 1;
        try {
            glowIntensity = Math.max(0, Math.min(2, RecordableConfig.get().uiGalaxyGlowIntensity));
        } catch (Throwable ignored) {
        }
        float densityScale = glowIntensity == 0 ? 1.0f : (glowIntensity == 2 ? 1.65f : 1.3f);
        int glowChanceMask = glowIntensity == 0 ? 127 : (glowIntensity == 2 ? 31 : 63);
        int glowBoost = glowIntensity == 0 ? 18 : (glowIntensity == 2 ? 58 : 38);
        int glowCap = glowIntensity == 0 ? 88 : (glowIntensity == 2 ? 164 : 128);
        long tick = System.currentTimeMillis() / 95L;
        int innerW = Math.max(1, w - 4);
        int innerH = Math.max(1, h - 4);
        int starCount = Math.max(78, (int) (((w * h) / 1320.0f) * densityScale));
        for (int i = 0; i < starCount; i++) {
            int seed = (i * 1103515245 + left * 92821 + top * 68917) ^ 0x5F3759DF;
            int px = left + 2 + Math.floorMod(seed, innerW);
            int py = top + 2 + Math.floorMod(seed >>> 8, innerH);
            int phase = (int) ((tick + (seed & 63)) % 30);
            int alpha = 22 + (phase < 15 ? phase * 4 : (30 - phase) * 4);
            int color = (alpha << 24) | (colors.accent & 0x00FFFFFF);
            ctx.fill(px, py, px + 1, py + 1, color);

            int dimSeed = seed ^ 0x6A09E667;
            if ((dimSeed & 1) == 0) {
                int px2 = left + 2 + Math.floorMod(dimSeed, innerW);
                int py2 = top + 2 + Math.floorMod(dimSeed >>> 10, innerH);
                int alpha2 = Math.max(12, alpha - 10);
                int dimColor = (alpha2 << 24) | (colors.textMuted & 0x00FFFFFF);
                ctx.fill(px2, py2, px2 + 1, py2 + 1, dimColor);
            }

            if ((seed & glowChanceMask) == 0) {
                int glowAlpha = Math.min(glowCap, alpha + glowBoost);
                int glow = (glowAlpha << 24) | (colors.accentHover & 0x00FFFFFF);
                ctx.fill(px - 1, py, px + 2, py + 1, glow);
                ctx.fill(px, py - 1, px + 1, py + 2, glow);
                if ((seed & 3) == 0) {
                    int haloAlpha = Math.max(24, glowAlpha / 2);
                    int halo = (haloAlpha << 24) | (colors.headerText & 0x00FFFFFF);
                    ctx.fill(px - 2, py, px + 3, py + 1, halo);
                    ctx.fill(px, py - 2, px + 1, py + 3, halo);
                }
            }
        }
        renderGalaxyShootingStar(ctx, left, top, right, bottom, colors, glowIntensity);
    }

    private static void renderGalaxyShootingStar(GuiGraphicsExtractor ctx, int left, int top, int right, int bottom, ThemeColors colors, int glowIntensity) {
        int w = right - left;
        int h = bottom - top;
        if (w < 40 || h < 28) {
            return;
        }

        long now = System.currentTimeMillis();
        long cycleMs = 18_000L;
        long travelMs = 4_200L;
        long seedOffset = Math.floorMod((left * 37L) + (top * 19L), cycleMs);
        long phase = Math.floorMod(now + seedOffset, cycleMs);
        if (phase >= travelMs) {
            return;
        }

        float t = phase / (float) travelMs;
        float startX = right - Math.max(8f, w * 0.14f);
        float startY = top + Math.max(6f, h * 0.16f);
        float endX = left + Math.max(8f, w * 0.18f);
        float endY = bottom - Math.max(8f, h * 0.20f);
        float x = startX + (endX - startX) * t;
        float y = startY + (endY - startY) * t;

        float moveX = endX - startX;
        float moveY = endY - startY;
        float moveLen = (float) Math.sqrt(moveX * moveX + moveY * moveY);
        if (moveLen < 0.001f) {
            return;
        }
        float backX = -moveX / moveLen;
        float backY = -moveY / moveLen;
        float tailLength = glowIntensity == 2 ? 42f : 34f;
        int samples = (int) (tailLength * 2f);
        for (int s = 0; s <= samples; s++) {
            float dist = (s / (float) samples) * tailLength;
            float fade = 1f - (dist / tailLength);
            int alpha = Math.max(8, Math.min(145, (int) (fade * (85 + glowIntensity * 22))));
            int tailColor = (alpha << 24) | (colors.accentHover & 0x00FFFFFF);
            int tx = Math.round(x + backX * dist);
            int ty = Math.round(y + backY * dist);
            ctx.fill(tx, ty, tx + 1, ty + 1, tailColor);
        }

        int headAlpha = 180 + glowIntensity * 20;
        int headColor = (Math.min(228, headAlpha) << 24) | (colors.headerText & 0x00FFFFFF);
        int hx = Math.round(x);
        int hy = Math.round(y);
        ctx.fill(hx - 1, hy - 1, hx + 2, hy + 2, headColor & 0x66FFFFFF);
        int core = 0xEE000000 | (colors.accentHover & 0x00FFFFFF);
        ctx.fill(hx, hy, hx + 1, hy + 1, core);
    }
}
