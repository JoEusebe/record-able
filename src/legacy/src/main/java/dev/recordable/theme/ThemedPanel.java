package dev.recordable.theme;

import dev.recordable.compat.RenderHelper;
import io.wispforest.owo.ui.core.Color;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;

/**
 * Utility class for drawing themed panels, headers, section dividers, and decorative elements.
 * Intended to be called from Screen render methods.
 */
public final class ThemedPanel {
    private ThemedPanel() {}

    /** Draw a themed panel background with border and accent top bar. */
    public static void drawPanel(DrawContext ctx, int left, int top, int right, int bottom) {
        ThemeColors colors = ThemeEngine.get().colors();
        int panelW = Math.max(8, right - left);
        int panelH = Math.max(8, bottom - top);
        int cornerRunX = Math.max(10, Math.min(24, panelW / 6));
        int cornerRunY = Math.max(10, Math.min(24, panelH / 6));
        int cornerThickness = 2;
        int topSheen = Color.ofArgb(colors.panelBackgroundAlt).interpolate(Color.ofArgb(colors.headerText), 0.18f).argb();
        int bottomShade = Color.ofArgb(colors.panelBackgroundAlt).interpolate(Color.ofArgb(0xFF000000), 0.28f).argb();
        // Multi-layer shadow for depth.
        ctx.fill(left - 3, top - 3, right + 3, bottom + 3, 0x18000000);
        ctx.fill(left - 2, top - 2, right + 2, bottom + 2, 0x26000000);
        ctx.fill(left - 1, top - 1, right + 1, bottom + 1, 0x44000000);

        // Main body.
        ctx.fill(left, top, right, bottom, colors.panelBackground);
        ctx.fill(left + 1, top + 1, right - 1, bottom - 1, colors.panelBackgroundAlt);
        ctx.fill(left + 1, top + 1, right - 1, top + 2, topSheen);
        ctx.fill(left + 1, bottom - 2, right - 1, bottom - 1, bottomShade);

        // Base frame.
        ctx.fill(left, top, right, top + 1, colors.panelBorder);
        ctx.fill(left, bottom - 1, right, bottom, colors.panelBorder);
        ctx.fill(left, top, left + 1, bottom, colors.panelBorder);
        ctx.fill(right - 1, top, right, bottom, colors.panelBorder);
        ctx.fill(left + 1, top + 1, right - 1, top + 2, colors.accentDim);

        // Clean corner brackets inspired by the reference mockup.
        int bracket = colors.accent;
        ctx.fill(left, top, left + cornerRunX, top + cornerThickness, bracket);
        ctx.fill(left, top, left + cornerThickness, top + cornerRunY, bracket);
        ctx.fill(right - cornerRunX, top, right, top + cornerThickness, bracket);
        ctx.fill(right - cornerThickness, top, right, top + cornerRunY, bracket);
        ctx.fill(left, bottom - cornerThickness, left + cornerRunX, bottom, bracket);
        ctx.fill(left, bottom - cornerRunY, left + cornerThickness, bottom, bracket);
        ctx.fill(right - cornerRunX, bottom - cornerThickness, right, bottom, bracket);
        ctx.fill(right - cornerThickness, bottom - cornerRunY, right, bottom, bracket);

        // VHS effects
        VhsEffectsRenderer.renderOverPanel(ctx, left + 1, top + 4, right - 1, bottom - 1);
    }

    /** Draw a themed panel with film-strip borders (Cinema theme). */
    public static void drawFilmPanel(DrawContext ctx, int left, int top, int right, int bottom) {
        ThemeColors colors = ThemeEngine.get().colors();
        int stripW = 12;
        // Main background
        ctx.fill(left + stripW, top, right - stripW, bottom, colors.panelBackground);
        // Film strip borders
        VhsEffectsRenderer.renderFilmStripBorders(ctx, left, top, right, bottom, stripW);
        // Effects on main area
        VhsEffectsRenderer.renderOverPanel(ctx, left + stripW, top, right - stripW, bottom);
    }

    /** Section header with themed underline decoration. */
    public static void drawSectionHeader(DrawContext ctx, TextRenderer textRenderer, String text, int x, int y, int maxWidth) {
        ThemeColors colors = ThemeEngine.get().colors();
        ThemePreset preset = ThemeEngine.get().preset();
        int textWidth = textRenderer.getWidth(text);
        int chipBottom = y + 12;
        int chipRight = Math.min(x + maxWidth, x + textWidth + 20);

        // Header chip with subtle depth.
        ctx.fill(x - 5, y - 3, chipRight + 1, chipBottom + 1, 0x22000000);
        ctx.fill(x - 4, y - 2, chipRight, chipBottom, colors.sectionBackground);
        ctx.fill(x - 4, y - 2, chipRight, y - 1, 0x1FFFFFFF);
        ctx.fill(x - 4, chipBottom - 1, chipRight, chipBottom, colors.panelBorder);

        // Header text
        RenderHelper.drawText(ctx, textRenderer, text, x, y, colors.headerText);

        // Decorative underline.
        int lineY = y + 10;
        ctx.fill(x, lineY, x + textWidth + 1, lineY + 1, colors.headerUnderline);

        // Fade-out extension line.
        int extEnd = Math.min(x + maxWidth, x + textWidth + 40);
        if (extEnd > x + textWidth + 4) {
            int fadeColor = colors.headerUnderline & 0x40FFFFFF;
            ctx.fill(x + textWidth + 2, lineY, extEnd, lineY + 1, fadeColor);
        }

        // VHS theme: small "▌" marker before header
        if (preset == ThemePreset.VHS || preset == ThemePreset.NEON) {
            RenderHelper.drawText(ctx, textRenderer, "▌", x - 8, y, colors.accent);
        }
        if (preset == ThemePreset.GALAXY) {
            RenderHelper.drawText(ctx, textRenderer, "✶", x - 10, y, colors.accentHover);
        }
        // Cinema theme: small "★" before header
        if (preset == ThemePreset.CINEMA) {
            RenderHelper.drawText(ctx, textRenderer, "★", x - 10, y, colors.accent);
        }
    }

    /** Thin horizontal divider line. */
    public static void drawDivider(DrawContext ctx, int x, int y, int width) {
        ThemeColors colors = ThemeEngine.get().colors();
        ctx.fill(x, y, x + width, y + 1, colors.panelBorder);
        ctx.fill(x, y + 1, x + width, y + 2, 0x22000000);
    }

    /** Themed scrollbar. */
    public static void drawScrollbar(DrawContext ctx, int barLeft, int barTop, int barBottom, int thumbTop, int thumbHeight) {
        ThemeColors colors = ThemeEngine.get().colors();
        int barRight = barLeft + 5;
        ctx.fill(barLeft, barTop, barRight, barBottom, colors.scrollTrack);
        ctx.fill(barLeft + 1, barTop + 1, barRight - 1, barBottom - 1, colors.panelBackgroundAlt & 0x55FFFFFF);

        ctx.fill(barLeft + 1, thumbTop, barRight - 1, thumbTop + thumbHeight, colors.scrollThumb);
        ctx.fill(barLeft + 1, thumbTop, barRight - 1, thumbTop + 1, 0x33FFFFFF);
        ctx.fill(barLeft + 1, thumbTop + thumbHeight - 1, barRight - 1, thumbTop + thumbHeight, 0x33000000);
    }

    /** Draw a "tape reel" loading indicator. Animated circles. */
    public static void drawReelLoading(DrawContext ctx, int centerX, int centerY, int radius) {
        ThemeColors colors = ThemeEngine.get().colors();
        long tick = System.currentTimeMillis();
        double angle = (tick % 2000) / 2000.0 * Math.PI * 2;

        // Outer ring
        for (int i = 0; i < 8; i++) {
            double a = angle + i * Math.PI / 4;
            int dx = (int) (Math.cos(a) * radius);
            int dy = (int) (Math.sin(a) * radius);
            int alpha = 80 + (int) (175 * ((i + (tick / 125) % 8) % 8) / 8.0);
            alpha = Math.min(255, alpha);
            int dotColor = (alpha << 24) | (colors.accent & 0x00FFFFFF);
            ctx.fill(centerX + dx - 1, centerY + dy - 1, centerX + dx + 2, centerY + dy + 2, dotColor);
        }
    }

    /** VHS-style status bar (e.g., "PLAY ▶", "REC ●") with blinking. */
    public static void drawVhsStatusBadge(DrawContext ctx, TextRenderer textRenderer, String text, int x, int y, boolean blink) {
        ThemeColors colors = ThemeEngine.get().colors();
        int bgColor = colors.panelBackground & 0xCC000000 | (colors.panelBackground & 0x00FFFFFF);
        int textWidth = textRenderer.getWidth(text);
        ctx.fill(x - 2, y - 1, x + textWidth + 2, y + 10, bgColor);

        if (blink) {
            float pulse = ThemeEngine.pulse(System.currentTimeMillis(), 1200);
            int alpha = (int) (130 + 125 * pulse);
            int textColor = (alpha << 24) | (colors.textPrimary & 0x00FFFFFF);
            RenderHelper.drawText(ctx, textRenderer, text, x, y, textColor);
        } else {
            RenderHelper.drawText(ctx, textRenderer, text, x, y, colors.textPrimary);
        }
    }

    /** Draw a category tab-style row for settings sections. */
    public static void drawCategoryTab(DrawContext ctx, TextRenderer textRenderer, String label, int x, int y, int width, boolean selected) {
        ThemeColors colors = ThemeEngine.get().colors();
        int bgColor = selected ? colors.sectionHover : colors.sectionBackground;
        ctx.fill(x - 1, y - 1, x + width + 1, y + 17, 0x33000000);
        ctx.fill(x, y, x + width, y + 16, bgColor);
        ctx.fill(x + 1, y + 1, x + width - 1, y + 2, 0x22FFFFFF);
        ctx.fill(x + 1, y + 14, x + width - 1, y + 15, 0x33000000);
        if (selected) {
            ctx.fill(x, y, x + 3, y + 16, colors.accent);
            ctx.fill(x + width - 1, y, x + width, y + 16, colors.accentDim);
        }
        RenderHelper.drawText(ctx, textRenderer, label, x + 6, y + 4, selected ? colors.textPrimary : colors.textSecondary);
    }
}
