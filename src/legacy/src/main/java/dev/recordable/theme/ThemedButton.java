package dev.recordable.theme;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * Utility class that provides themed button creation and custom rendering helpers.
 * Uses standard ButtonWidget internally for compatibility with Minecraft 1.21.1 API.
 */
public final class ThemedButton {

    private ThemedButton() {}

    /**
     * Creates a standard ButtonWidget using the builder API.
     */
    public static ButtonWidget create(int x, int y, int w, int h, Text msg, ButtonWidget.PressAction onPress) {
        return ButtonWidget.builder(msg, onPress)
                .dimensions(x, y, w, h)
                .build();
    }

    /**
     * Draws a themed button-style rectangle at the given position.
     * Can be used for custom render overlays or manual button drawing.
     */
    public static void drawThemedButtonRect(DrawContext context, int x, int y, int w, int h,
                                             boolean hovered, float hoverProgress) {
        ThemeColors colors = ThemeEngine.get().colors();
        float t = hovered ? Math.max(hoverProgress, 0.15f) : hoverProgress * 0.5f;
        int bgColor = ThemeEngine.lerpColor(colors.buttonBackground, colors.buttonBackgroundHover, t);
        int borderColor = ThemeEngine.lerpColor(colors.buttonBorder, colors.accent, t);
        int topHighlight = ThemeEngine.lerpColor(0x22FFFFFF, 0x40FFFFFF, hoverProgress);
        int bottomShade = ThemeEngine.lerpColor(0x33000000, 0x55000000, hoverProgress);

        // Frame + layered shadow.
        context.fill(x - 2, y - 2, x + w + 2, y + h + 2, 0x18000000);
        context.fill(x - 1, y - 1, x + w + 1, y + h + 1, 0x33000000);
        context.fill(x, y, x + w, y + h, bgColor);
        context.fill(x + 1, y + 1, x + w - 1, y + h - 1, colors.panelBackgroundAlt & 0x22FFFFFF);

        // Border and bevel.
        context.fill(x, y, x + w, y + 1, borderColor);
        context.fill(x, y + h - 1, x + w, y + h, borderColor);
        context.fill(x, y, x + 1, y + h, borderColor);
        context.fill(x + w - 1, y, x + w, y + h, borderColor);
        // Pixel-style bevel lighting
        context.fill(x + 1, y + 1, x + w - 1, y + 2, topHighlight);
        context.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, bottomShade);

        // Scan effect on hover (VHS/Neon themes)
        if (hoverProgress > 0.1f && ThemeEngine.get().scanlineEnabled()) {
            long tick = System.currentTimeMillis();
            int scanY = y + (int) ((tick / 30) % h);
            int scanAlpha = (int) (20 * hoverProgress);
            context.fill(x + 1, scanY, x + w - 1, Math.min(scanY + 2, y + h - 1), (scanAlpha << 24) | 0x00FFFFFF);
        }

        // Accent bars on hover/focus.
        if (hoverProgress > 0.05f) {
            int barAlpha = (int) (255 * hoverProgress);
            int barColor = (barAlpha << 24) | (colors.accent & 0x00FFFFFF);
            context.fill(x, y + 1, x + 2, y + h - 1, barColor);
            context.fill(x + w - 2, y + 1, x + w, y + h - 1, barColor & 0x66FFFFFF);
        }
    }
}
