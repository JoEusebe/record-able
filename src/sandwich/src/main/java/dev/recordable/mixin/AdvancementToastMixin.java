package dev.recordable.mixin;

import dev.recordable.AutoClipManager;
import dev.recordable.RecordableMod;
import net.minecraft.advancement.AdvancementDisplay;
import net.minecraft.advancement.AdvancementEntry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.AdvancementToast;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into AdvancementToast to detect when the player earns an advancement.
 * Fires the auto-clip trigger for the "Achievement unlocked" event.
 */
@Mixin(AdvancementToast.class)
public abstract class AdvancementToastMixin {

    @Shadow
    @Final
    private AdvancementEntry advancement;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void recordable$onAdvancementToast(AdvancementEntry advancement, CallbackInfo ci) {
        try {
            AutoClipManager manager = RecordableMod.getAutoClipManager();
            if (manager == null) return;

            String title = "Advancement";
            if (advancement != null && advancement.value() != null) {
                AdvancementDisplay display = advancement.value().display().orElse(null);
                if (display != null && display.getTitle() != null) {
                    title = display.getTitle().getString();
                }
            }

            MinecraftClient client = MinecraftClient.getInstance();
            manager.onAdvancementEarned(client, title);
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("Failed to process advancement for auto-clip.", t);
        }
    }
}
