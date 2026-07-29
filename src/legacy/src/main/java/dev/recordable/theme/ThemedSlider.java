package dev.recordable.theme;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/**
 * A slider widget with retro theme styling.
 * Features: themed track/thumb colors, VHS-style track marks.
 */
public class ThemedSlider extends SliderWidget {
    private final Consumer<Double> onChange;
    private final String labelFormat;
    private final double minVal;
    private final double maxVal;

    public ThemedSlider(int x, int y, int w, int h, String labelFormat,
                        double minVal, double maxVal, double currentVal,
                        Consumer<Double> onChange) {
        super(x, y, w, h, Text.literal(String.format(labelFormat, (int) currentVal)),
                (currentVal - minVal) / (maxVal - minVal));
        this.onChange = onChange;
        this.labelFormat = labelFormat;
        this.minVal = minVal;
        this.maxVal = maxVal;
    }

    @Override
    protected void updateMessage() {
        double val = minVal + value * (maxVal - minVal);
        this.setMessage(Text.literal(String.format(labelFormat, (int) val)));
    }

    @Override
    protected void applyValue() {
        double val = minVal + value * (maxVal - minVal);
        onChange.accept(val);
    }

    public double getActualValue() {
        return minVal + value * (maxVal - minVal);
    }

    /**
     * Draws a themed slider-style bar at the given position.
     * Can be used for custom slider visuals without requiring a widget.
     */
    public static void drawThemedBar(DrawContext context, int x, int y, int w, int h,
                                      double progress, boolean hovered) {
        ThemeColors colors = ThemeEngine.get().colors();
        double clamped = Math.max(0.0D, Math.min(1.0D, progress));

        // Track background
        context.fill(x, y, x + w, y + h, colors.buttonBackground);

        // Track border
        context.fill(x, y, x + w, y + 1, colors.buttonBorder);
        context.fill(x, y + h - 1, x + w, y + h, colors.buttonBorder);
        context.fill(x, y, x + 1, y + h, colors.buttonBorder);
        context.fill(x + w - 1, y, x + w, y + h, colors.buttonBorder);

        // Filled portion
        int filledWidth = (int) Math.round(clamped * (w - 4));
        int fillColor = hovered ? colors.accentHover : colors.accent;
        context.fill(x + 2, y + 2, x + 2 + filledWidth, y + h - 2, fillColor);
        if (filledWidth > 0) {
            context.fill(x + 2, y + 2, x + 2 + filledWidth, y + 3, 0x33FFFFFF);
        }

        // Track tick marks (retro VHS style)
        ThemePreset preset = ThemeEngine.get().preset();
        if (preset == ThemePreset.VHS || preset == ThemePreset.CINEMA) {
            int tickCount = 10;
            for (int i = 0; i <= tickCount; i++) {
                int tx = x + 2 + (int) ((w - 4) * (i / (float) tickCount));
                context.fill(tx, y + h - 4, tx + 1, y + h - 1, colors.textMuted & 0x60FFFFFF);
            }
        }

        // Thumb
        int thumbX = x + 2 + filledWidth - 3;
        context.fill(thumbX, y + 1, thumbX + 6, y + h - 1, colors.textPrimary);
        context.fill(thumbX + 1, y + 2, thumbX + 5, y + h - 2, colors.panelBackgroundAlt);
        if (hovered) {
            context.fill(thumbX - 1, y, thumbX + 7, y + h, colors.accentHover & 0x40FFFFFF);
        }
    }
}
