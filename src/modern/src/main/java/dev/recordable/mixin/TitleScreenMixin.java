package dev.recordable.mixin;

import dev.recordable.EasterEgg;
import dev.recordable.RecordableConfig;
import dev.recordable.VersionHelper;
import dev.recordable.screen.HomeButtonWidget;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Adds Record-able home button to title screen - <b>Modern (26.x+)</b>.
 *
 * <p>Mojang names: {@code TitleScreen} (same), {@code Screen} at
 * {@code net.minecraft.client.gui.screens.Screen}. Rendering uses the 26.x
 * {@code extractRenderState(GuiGraphicsExtractor, ...)} model.</p>
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenMixin extends Screen {
    protected TitleScreenMixin(Component title) {
        super(title);
    }

    @Unique
    private Button recordable$homeButton;

    /**
     * Reads the currently logged-in account's UUID from the client session.
     * Works on the title screen where {@code minecraft.player} is null.
     */
    @Unique
    private java.util.UUID recordable$sessionUuid() {
        try {
            if (this.minecraft != null && this.minecraft.getUser() != null) {
                return this.minecraft.getUser().getProfileId();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    @Inject(method = "init", at = @At("TAIL"))
    private void recordable$addHomeButton(CallbackInfo ci) {
        RecordableConfig config;
        try { config = RecordableConfig.get(); } catch (Throwable ignored) { return; }
        if (config == null || !config.showHomeButton) return;
        try {
            Button button = HomeButtonWidget.create((TitleScreen) (Object) this);
            this.addRenderableWidget(button);
            this.recordable$homeButton = button;
            // Hidden easter egg: flash the screen white once on open.
            // On the title screen minecraft.player is null, so read the UUID
            // from the logged-in account session instead.
            if (EasterEgg.enabled(recordable$sessionUuid())) {
                EasterEgg.triggerFlash();
            }
        } catch (Throwable ignored) {}
    }

    @Inject(method = "extractRenderState", at = @At("TAIL"))
    private void recordable$eggOverlay(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
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
        } catch (Throwable ignored) {}
    }

    @Unique
    private void recordable$drawArrows(GuiGraphicsExtractor context, int bx, int by, int bw, int bh) {
        final int a = 32; // arrow texture size
        int bob = (int) (Math.sin(System.currentTimeMillis() / 300.0) * 3.0) + 3;
        int cxOff = bx + bw / 2 - a / 2;
        int cyOff = by + bh / 2 - a / 2;

        Identifier right = VersionHelper.modId("textures/gui/red_arrow_right.png");
        Identifier left = VersionHelper.modId("textures/gui/red_arrow_left.png");
        Identifier down = VersionHelper.modId("textures/gui/red_arrow_down.png");
        Identifier up = VersionHelper.modId("textures/gui/red_arrow_up.png");

        recordable$blit(context, right, bx - a - 2 - bob, cyOff);
        recordable$blit(context, left, bx + bw + 2 + bob, cyOff);
        recordable$blit(context, down, cxOff, by - a - 2 - bob);
        recordable$blit(context, up, cxOff, by + bh + 2 + bob);
    }

    @Unique
    private void recordable$blit(GuiGraphicsExtractor context, Identifier tex, int x, int y) {
        context.blit(RenderPipelines.GUI_TEXTURED, tex, x, y, 0.0F, 0.0F, 32, 32, 32, 32);
    }

}
