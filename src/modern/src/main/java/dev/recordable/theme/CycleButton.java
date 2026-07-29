package dev.recordable.theme;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * V1-0.08: a button that cycles through multiple options.
 *
 * <p>Left-click advances to the next option (the normal press action). Right-click
 * reverts to the previous option. This gives every multi-mode control in the mod a
 * consistent "forward / backward" feel without extra UI.</p>
 *
 * <p>Both actions are plain {@link Button.OnPress} callbacks, so existing cycle
 * buttons can be converted by supplying a "previous" callback alongside the existing
 * "next" one. If {@code onSecondary} is {@code null} the button behaves like an
 * ordinary {@link Button}.</p>
 */
public final class CycleButton extends Button {

    private final OnPress onSecondary;

    private CycleButton(int x, int y, int w, int h, Component msg,
                        OnPress onPrimary, OnPress onSecondary) {
        super(x, y, w, h, msg, onPrimary, DEFAULT_NARRATION);
        this.onSecondary = onSecondary;
    }

    /**
     * Creates a cycle button. {@code onLeft} runs on left-click (next), {@code onRight}
     * runs on right-click (previous).
     */
    public static CycleButton create(int x, int y, int w, int h, Component msg,
                                     OnPress onLeft, OnPress onRight) {
        return new CycleButton(x, y, w, h, msg, onLeft, onRight);
    }

    @Override
    protected void extractContents(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float a) {
        this.extractDefaultSprite(graphics);
        this.extractDefaultLabel(graphics.textRendererForWidget(this, GuiGraphicsExtractor.HoveredTextEffects.NONE));
    }

    @Override
    public boolean mouseClicked(MouseButtonEvent click, boolean doubleClick) {
        if (this.active && this.visible && click.button() == 1 && this.onSecondary != null
                && this.isMouseOver(click.x(), click.y())) {
            Minecraft mc = Minecraft.getInstance();
            if (mc != null) this.playDownSound(mc.getSoundManager());
            this.onSecondary.onPress(this);
            return true;
        }
        return super.mouseClicked(click, doubleClick);
    }
}
