package dev.recordable.theme;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * A themed toggle implemented as a Button that cycles ON/OFF.
 * Draws a custom sliding indicator when rendered via the static helper.
 */
public final class ThemedToggle {

    private ThemedToggle() {}

    /**
     * Creates a Button that acts as a toggle.
     * The onPress callback toggles the boolean and the consumer is called.
     */
    public static Button create(int x, int y, int w, int h,
                                       String label, boolean initial,
                                       Consumer<Boolean> onChange) {
        final boolean[] state = {initial};
        Component msg = Component.literal(label + ": " + (initial ? "ON" : "OFF"));
        Button btn = Button.builder(msg, b -> {
            state[0] = !state[0];
            b.setMessage(Component.literal(label + ": " + (state[0] ? "ON" : "OFF")));
            onChange.accept(state[0]);
        }).bounds(x, y, w, h).build();
        return btn;
    }

    /**
     * Draws a themed toggle track and thumb indicator.
     * Call this in custom render code for a toggle-style display.
     */
    public static void drawToggleTrack(GuiGraphicsExtractor context, int x, int y, int trackW, int trackH,
                                        float animProgress) {
        ThemeColors colors = ThemeEngine.get().colors();
        float t = Math.max(0.0f, Math.min(1.0f, animProgress));
        int trackColor = ThemeEngine.lerpColor(colors.textMuted & 0x55FFFFFF, colors.accent & 0x99FFFFFF, t);
        context.fill(x - 1, y - 1, x + trackW + 1, y + trackH + 1, 0x22000000);
        context.fill(x, y, x + trackW, y + trackH, trackColor);
        context.fill(x + 1, y + 1, x + trackW - 1, y + 2, 0x2AFFFFFF);

        int thumbW = Math.max(8, trackH - 2);
        int thumbX = x + 2 + (int) ((trackW - thumbW - 4) * t);
        int thumbColor = ThemeEngine.lerpColor(colors.textMuted, colors.accent, t);
        context.fill(thumbX, y + 1, thumbX + thumbW, y + trackH - 1, thumbColor);
        context.fill(thumbX + 1, y + 2, thumbX + thumbW - 1, y + trackH - 2, colors.panelBackgroundAlt);
    }
}
