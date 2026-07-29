package dev.recordable.mixin;

import dev.recordable.RecordableMod;
import net.minecraft.client.gui.render.state.GuiRenderState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes screen background blur tolerant of modded screen render wrappers.
 *
 * <p>Minecraft 1.21.11 stores one blur split point in {@link GuiRenderState} and
 * throws if a second screen/background renderer calls {@code applyBlur()} during
 * the same frame. Some screen wrapper stacks can legitimately render a modded
 * screen through multiple hooks, which turns that guard into a hard client
 * crash. Treating later blur requests as a no-op preserves the first blur layer
 * while preventing the duplicate-call crash from propagating out of rendering.</p>
 */
@Mixin(GuiRenderState.class)
public abstract class GuiRenderStateMixin {
    private static final int NO_BLUR_LAYER = Integer.MAX_VALUE;

    @Shadow
    private int blurLayer;

    @Inject(method = "applyBlur()V", at = @At("HEAD"), cancellable = true)
    private void recordable$skipDuplicateBlur(CallbackInfo callbackInfo) {
        if (this.blurLayer != NO_BLUR_LAYER) {
            RecordableMod.LOGGER.debug("Skipped duplicate GUI blur request in the same frame.");
            callbackInfo.cancel();
        }
    }
}
