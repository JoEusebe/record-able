package dev.recordable.mixin;

import dev.recordable.RecordableMod;
import dev.recordable.RecordingManager;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the rendered game frame for recording - <b>Minecraft 1.21.6 - 1.21.11</b>.
 *
 * <p>Starting in 1.21.6, Minecraft switched to a fully deferred GUI pipeline:
 * <ol>
 *   <li>{@code GameRenderer.render(...)} draws the world to the main render target</li>
 *   <li>{@code InGameHud.render(...)} only <em>records</em> HUD/overlay draw commands
 *       into a {@code GuiRenderState} - nothing is drawn to the framebuffer yet</li>
 *   <li>{@code GuiRenderer.render(GpuBufferSlice)} actually draws the recorded GUI
 *       (vanilla HUD + the mod's overlay via HudRenderCallback) onto the framebuffer</li>
 * </ol>
 *
 * <p>The older {@link GameRendererMixin} hooks right after {@code InGameHud.render},
 * which on 1.21.6+ runs <em>before</em> step 3, so the captured frame contains only
 * the world (held items/hand included) but no HUD. That is the "GUI-less recording"
 * bug. Capturing after {@code GuiRenderer.render(GpuBufferSlice)} guarantees the HUD
 * pixels are already on the main render target, matching the modern (26.x) variant.</p>
 *
 * <p>{@code ScreenCapture} reads via {@code glReadPixels} from the currently bound draw
 * FBO (the main render target), and {@code glReadPixels} forces a GL sync point, so all
 * GUI draw commands submitted by {@code GuiRenderer.render} are guaranteed present.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin_1216 {

    @Inject(
            method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;render(Lcom/mojang/blaze3d/buffers/GpuBufferSlice;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void recordable$captureAfterGuiFlush(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        try {
            RecordingManager.getInstance().onFrame();
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("Record-able capture hook failed; skipping this frame.", t);
        }
    }
}
