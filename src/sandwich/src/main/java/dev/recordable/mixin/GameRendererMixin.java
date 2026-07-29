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
 * Captures the rendered game frame for recording - <b>Minecraft 1.21.0 - 1.21.5</b>.
 *
 * <p>The frame is captured right after {@code InGameHud.render}, so the recording
 * always includes the in-game HUD (hotbar, health, crosshair, chat) and the mod's
 * own overlay, exactly as the player sees them on screen.</p>
 *
 * <p>Streamer-mode censor blocks are handled separately in
 * {@link RecordingManager} (baked into every recorded frame in software) and in
 * {@code RecordingOverlay} (the live on-screen watermark), independent of this
 * capture point.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    /**
     * Capture the framebuffer right after the in-game HUD (and the mod's HUD
     * overlay) have rendered, so the recording includes the GUI/HUD.
     */
    @Inject(
            method = "render(Lnet/minecraft/client/render/RenderTickCounter;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/hud/InGameHud;render(Lnet/minecraft/client/gui/DrawContext;Lnet/minecraft/client/render/RenderTickCounter;)V",
                    shift = At.Shift.AFTER
            )
    )
    private void recordable$captureAfterHud(RenderTickCounter tickCounter, boolean tick, CallbackInfo ci) {
        try {
            RecordingManager.getInstance().onFrame();
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("Record-able capture hook failed; skipping this frame.", t);
        }
    }
}
