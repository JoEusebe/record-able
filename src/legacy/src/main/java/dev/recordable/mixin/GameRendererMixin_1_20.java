package dev.recordable.mixin;

import dev.recordable.RecordableMod;
import dev.recordable.RecordingManager;
import net.minecraft.client.gui.DrawContext;
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
     * Capture the framebuffer right after the GUI buffers are flushed.
     *
     * <p>In 1.20.x, {@code GameRenderer.render(FJZ)V} builds a single
     * {@link DrawContext} (backed by a {@code VertexConsumerProvider.Immediate})
     * and renders the in-game HUD, any active overlay/screen, and toasts into it.
     * Those draws are <b>batched on the CPU</b> and are only uploaded to the
     * framebuffer when {@code DrawContext.draw()} is called at the very end of the
     * method.</p>
     *
     * <p>Capturing right after the {@code InGameHud.render} <i>invocation</i> (as a
     * previous version did) reads the framebuffer <b>before</b> that flush, so the
     * HUD/GUI was missing from recordings. Injecting after {@code DrawContext.draw()}
     * guarantees the hotbar, chat, health/hunger, and the mod's overlays are baked
     * into the captured pixels.</p>
     */
    @Inject(
            method = "render(FJZ)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/DrawContext;draw()V",
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
