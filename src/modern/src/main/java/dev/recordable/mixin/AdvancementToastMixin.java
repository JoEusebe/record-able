package dev.recordable.mixin;

import dev.recordable.AutoClipManager;
import dev.recordable.RecordableMod;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.advancements.DisplayInfo;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.AdvancementToast;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Detects advancement toasts for auto-clip triggers - <b>Modern (26.x+)</b>.
 *
 * <p>Uses Mojang official names: {@code AdvancementHolder} (Yarn: AdvancementEntry),
 * {@code DisplayInfo} (Yarn: AdvancementDisplay).</p>
 */
@Mixin(AdvancementToast.class)
public abstract class AdvancementToastMixin {

    @Shadow @Final
    private AdvancementHolder advancement;

    @Inject(method = "<init>", at = @At("RETURN"))
    private void recordable$onAdvancementToast(AdvancementHolder advancement, CallbackInfo ci) {
        try {
            AutoClipManager manager = RecordableMod.getAutoClipManager();
            if (manager == null) return;

            String title = "Advancement";
            if (advancement != null && advancement.value() != null) {
                DisplayInfo display = advancement.value().display().orElse(null);
                if (display != null && display.getTitle() != null) {
                    title = display.getTitle().getString();
                }
            }

            Minecraft client = Minecraft.getInstance();
            manager.onAdvancementEarned(client, title);
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("Failed to process advancement for auto-clip.", t);
        }
    }
}
