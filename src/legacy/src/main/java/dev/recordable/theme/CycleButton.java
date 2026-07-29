package dev.recordable.theme;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/**
 * V1-0.08: a button that cycles through multiple options.
 *
 * <p>Left-click advances to the next option (the normal press action). Right-click
 * reverts to the previous option. This gives every multi-mode control in the mod a
 * consistent "forward / backward" feel without extra UI.</p>
 *
 * <p>Both actions are plain {@link ButtonWidget.PressAction} callbacks, so existing
 * cycle buttons can be converted by supplying a "previous" callback alongside the
 * existing "next" one. If {@code onSecondary} is {@code null} the button behaves like
 * an ordinary {@link ButtonWidget}.</p>
 */
public final class CycleButton extends ButtonWidget {

    private final PressAction onSecondary;

    private CycleButton(int x, int y, int w, int h, Text msg,
                        PressAction onPrimary, PressAction onSecondary) {
        super(x, y, w, h, msg, onPrimary, DEFAULT_NARRATION_SUPPLIER);
        this.onSecondary = onSecondary;
    }

    /**
     * Creates a cycle button. {@code onLeft} runs on left-click (next), {@code onRight}
     * runs on right-click (previous).
     */
    public static CycleButton create(int x, int y, int w, int h, Text msg,
                                     PressAction onLeft, PressAction onRight) {
        return new CycleButton(x, y, w, h, msg, onLeft, onRight);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.active && this.visible && button == 1 && this.onSecondary != null
                && this.isMouseOver(mouseX, mouseY)) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc != null) this.playDownSound(mc.getSoundManager());
            this.onSecondary.onPress(this);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }
}
