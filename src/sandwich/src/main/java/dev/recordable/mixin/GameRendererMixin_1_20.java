package dev.recordable.mixin;

import dev.recordable.RecordableMod;
import dev.recordable.RecordingManager;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the rendered game frame for recording - <b>Minecraft 1.20.x variant</b>.
 *
 * <p>In 1.20.x, {@code GameRenderer.render()} uses the signature
 * {@code render(float tickDelta, long startTime, boolean tick)}, and
 * {@code InGameHud.render()} takes {@code (DrawContext, float)}.</p>
 *
 * <p>This mixin is <b>only loaded on 1.20.x</b> via {@link RecordableMixinPlugin}.
 * On 1.21+, {@link GameRendererMixin} is loaded instead.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin_1_20 {

    /**
     * Capture the framebuffer right after the in-game HUD (and the mod's HUD overlay) have rendered, so the recording includes the GUI/HUD.
     * 1.20.x signature: render(float tickDelta, long startTime, boolean tick)
     * InGameHud.render target: (DrawContext, float)
     */
    @Inject(
            method = "render(FJZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/hud/InGameHud;render(Lnet/minecraft/client/gui/DrawContext;F)V",
                    shift = At.Shift.AFTER
            )
    )
    private void recordable$captureAfterHud(float tickDelta, long startTime, boolean tick, CallbackInfo ci) {
        try {
            RecordingManager.getInstance().onFrame();
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("Record-able capture hook failed; skipping this frame.", t);
        }
    }
}
