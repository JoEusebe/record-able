package dev.recordable.mixin;

import dev.recordable.EasterEgg;
import dev.recordable.RecordableConfig;
import dev.recordable.VersionHelper;
import dev.recordable.screen.HomeButtonWidget;
import net.minecraft.client.gl.RenderPipelines;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.util.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(net.minecraft.text.Text title) {
        super(title);
    }

    @Unique
    private ButtonWidget recordable$homeButton;

    /**
     * Reads the currently logged-in account's UUID from the client session.
     * Works on the title screen where {@code client.player} is null.
     */
    @Unique
    private java.util.UUID recordable$sessionUuid() {
        try {
            if (this.client != null && this.client.getSession() != null) {
                return this.client.getSession().getUuidOrNull();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void recordable$addHomeButton(CallbackInfo ci) {
        RecordableConfig config;
        try {
            config = RecordableConfig.get();
        } catch (Throwable ignored) {
            return;
        }
        if (config == null || !config.showHomeButton) {
            return;
        }

        try {
            ButtonWidget button = HomeButtonWidget.create((TitleScreen) (Object) this);
            this.addDrawableChild(button);
            this.recordable$homeButton = button;
            // Hidden easter egg: flash the screen white once on open.
            // On the title screen client.player is null, so read the UUID from
            // the logged-in account session instead.
            if (EasterEgg.enabled(recordable$sessionUuid())) {
                EasterEgg.triggerFlash();
            }
        } catch (Throwable ignored) {
        }
    }

    @Inject(method = "render", at = @At("TAIL"))
    private void recordable$eggOverlay(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        if (!EasterEgg.enabled(recordable$sessionUuid())) {
            return;
        }
        try {
            // Order matters: the white flash plays FIRST (on open). Only once it has
            // fully faded do the red guide arrows appear, and only for the first two
            // times the thank-you screen has been opened.
            int flash = EasterEgg.whiteFlashColor();
            if ((flash >>> 24) != 0) {
                context.fill(0, 0, this.width, this.height, flash);
            } else if (this.recordable$homeButton != null
                    && EasterEgg.shouldShowArrows(recordable$sessionUuid())) {
                recordable$drawArrows(context, this.recordable$homeButton.getX(),
                        this.recordable$homeButton.getY(),
                        this.recordable$homeButton.getWidth(),
                        this.recordable$homeButton.getHeight());
            }
        } catch (Throwable ignored) {
        }
    }

    @Unique
    private void recordable$drawArrows(DrawContext context, int bx, int by, int bw, int bh) {
        final int a = 32; // arrow texture size
        // Gentle bob toward the button.
        int bob = (int) (Math.sin(System.currentTimeMillis() / 300.0) * 3.0) + 3;
        int cxOff = bx + bw / 2 - a / 2;
        int cyOff = by + bh / 2 - a / 2;

        Identifier right = VersionHelper.modId("textures/gui/red_arrow_right.png");
        Identifier left = VersionHelper.modId("textures/gui/red_arrow_left.png");
        Identifier down = VersionHelper.modId("textures/gui/red_arrow_down.png");
        Identifier up = VersionHelper.modId("textures/gui/red_arrow_up.png");

        // Left of button, pointing right.
        recordable$blit(context, right, bx - a - 2 - bob, cyOff);
        // Right of button, pointing left.
        recordable$blit(context, left, bx + bw + 2 + bob, cyOff);
        // Above button, pointing down.
        recordable$blit(context, down, cxOff, by - a - 2 - bob);
        // Below button, pointing up.
        recordable$blit(context, up, cxOff, by + bh + 2 + bob);
    }

    @Unique
    private void recordable$blit(DrawContext context, Identifier tex, int x, int y) {
        context.drawTexture(RenderPipelines.GUI_TEXTURED, tex, x, y, 0.0F, 0.0F, 32, 32, 32, 32);
    }

}
