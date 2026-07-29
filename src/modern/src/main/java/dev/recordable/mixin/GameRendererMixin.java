package dev.recordable.mixin;

import dev.recordable.RecordableMod;
import dev.recordable.RecordingManager;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Captures the rendered game frame for recording - <b>Modern (26.x+)</b>.
 *
 * <p>In Minecraft 26.x, {@code GameRenderer.render()} uses
 * {@code DeltaTracker} (Mojang official name for what Yarn calls
 * {@code RenderTickCounter}).</p>
 *
 * <p>26.x uses a fully deferred GUI pipeline:
 * <ol>
 *   <li>{@code renderLevel(...)} - world is drawn to the main render target</li>
 *   <li>{@code Gui.extractRenderState(...)} - HUD/overlay draw commands are recorded</li>
 *   <li>{@code GuiRenderer.render()} - the recorded GUI (vanilla HUD + every
 *       HudElementRegistry overlay, including our RecordingOverlay) is drawn onto
 *       the main render target</li>
 * </ol>
 *
 * <p>The frame is captured right after the GUI has been drawn (just before
 * {@code GuiRenderer.endFrame()}), so the recording always includes the in-game
 * HUD and the mod's own overlay, exactly as the player sees them on screen.</p>
 *
 * <p>Streamer-mode censor blocks are handled separately in
 * {@link RecordingManager} (baked into every recorded frame in software) and in
 * {@code RecordingOverlay} (the live on-screen watermark), independent of this
 * capture point.</p>
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    /**
     * Capture the framebuffer right after the GUI (vanilla HUD + mod overlay) has
     * been drawn, so the recording includes the GUI/HUD.
     */
    // Cross-version note: GuiRenderer.render changed signature across 26.x
    // (26.1.x uses render(GpuBufferSlice), 26.2 uses render()), so pinning to that
    // call fails on 26.1.x ("Scanned 0 target(s)"). GuiRenderer.endFrame() keeps a
    // stable ()V descriptor across 26.1.x and 26.2 and is invoked exactly once,
    // immediately after render(...), so injecting right before it captures at the
    // same point on every 26.x version.
    @Inject(
            method = "render(Lnet/minecraft/client/DeltaTracker;Z)V",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/gui/render/GuiRenderer;endFrame()V",
                    shift = At.Shift.BEFORE
            ),
            require = 1
    )
    private void recordable$captureAfterHud(DeltaTracker deltaTracker, boolean tick, CallbackInfo ci) {
        try {
            RecordingManager.getInstance().onFrame();
        } catch (Throwable t) {
            RecordableMod.LOGGER.warn("Record-able capture hook failed; skipping this frame.", t);
        }
    }
}
