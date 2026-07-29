package dev.recordable.theme;

import dev.recordable.compat.RenderHelper;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.font.TextRenderer;

/**
 * Animated typewriter text effect - reveals characters one at a time,
 * with optional cursor blink and VHS flicker.
 */
public final class TypewriterText {
    private final String fullText;
    private final long startTimeMs;
    private final int charsPerSecond;
    private boolean completed = false;

    public TypewriterText(String text, int charsPerSecond) {
        this.fullText = text;
        this.startTimeMs = System.currentTimeMillis();
        this.charsPerSecond = charsPerSecond;
    }

    /** Render the typewriter text at the given position. Returns the number of visible characters. */
    public int render(DrawContext ctx, TextRenderer textRenderer, int x, int y, int color) {
        long elapsed = System.currentTimeMillis() - startTimeMs;
        int visibleChars = (int) (elapsed * charsPerSecond / 1000.0);
        visibleChars = Math.min(visibleChars, fullText.length());

        if (visibleChars >= fullText.length()) {
            completed = true;
        }

        String visible = fullText.substring(0, visibleChars);
        RenderHelper.drawText(ctx, textRenderer, visible, x, y, color);

        // Cursor blink
        if (!completed || (System.currentTimeMillis() / 500) % 2 == 0) {
            int cursorX = x + textRenderer.getWidth(visible);
            ThemeColors colors = ThemeEngine.get().colors();
            ctx.fill(cursorX, y, cursorX + 1, y + 9, colors.accent);
        }

        // VHS flicker: occasionally dim the whole text
        if (ThemeEngine.get().preset() == ThemePreset.VHS && !completed) {
            if (elapsed % 400 < 30) {
                // Draw a semi-transparent overlay to simulate flicker
                int flickerAlpha = 0x30;
                ctx.fill(x, y - 1, x + textRenderer.getWidth(visible) + 2, y + 10, flickerAlpha << 24);
            }
        }

        return visibleChars;
    }

    public boolean isCompleted() { return completed; }
    public String getFullText() { return fullText; }

    /** Skip to end (show all text immediately). */
    public void complete() { this.completed = true; }

    /** Render a simple flickering text (no typewriter, just subtle alpha variation). */
    public static void renderFlickerText(DrawContext ctx, TextRenderer textRenderer, String text, int x, int y, int baseColor) {
        float pulse = ThemeEngine.pulse(System.currentTimeMillis(), 3000);
        int alpha = (int) (200 + 55 * pulse);
        int color = (alpha << 24) | (baseColor & 0x00FFFFFF);
        RenderHelper.drawText(ctx, textRenderer, text, x, y, color);
    }
}
