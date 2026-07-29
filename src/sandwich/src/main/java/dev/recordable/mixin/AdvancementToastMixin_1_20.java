package dev.recordable.mixin;

import dev.recordable.AutoClipManager;
import dev.recordable.RecordableMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.toast.AdvancementToast;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Mixin into AdvancementToast for <b>Minecraft 1.20.x</b>.
 *
 * <p>In 1.20.x, the AdvancementToast constructor takes an {@code Advancement}
 * object rather than {@code AdvancementEntry} (introduced in 1.21+).
 * The advancement display info is accessed differently:</p>
 * <ul>
 *   <li>1.20.x: {@code advancement.getDisplay()} returns nullable AdvancementDisplay</li>
 *   <li>1.21+:  {@code advancementEntry.value().display()} returns Optional&lt;AdvancementDisplay&gt;</li>
 * </ul>
 *
 * <p>This mixin uses reflection (via MultiVersion R.java) to safely access
 * the advancement display across both API patterns.</p>
 *
 * <p>Only loaded on 1.20.x via {@link RecordableMixinPlugin}.</p>
 */
@Mixin(AdvancementToast.class)
public abstract class AdvancementToastMixin_1_20 {

    /**
     * In 1.20.x the constructor accepts an Advancement object.
     * We use reflection to extract the title safely since the exact API differs.
     */
    @Inject(method = "<init>", at = @At("RETURN"))
    private void recordable$onAdvancementToast(CallbackInfo ci) {
        try {
            AutoClipManager manager = RecordableMod.getAutoClipManager();
            if (manager == null) return;

            String title = "Advancement";

            // Use reflection to get the advancement field and extract display info.
            // In 1.20.x: AdvancementToast has a field of type Advancement (not AdvancementEntry)
            try {
                Object toastInstance = (Object) this;
                // Try to find the advancement field via reflection
                java.lang.reflect.Field[] fields = toastInstance.getClass().getDeclaredFields();
                for (java.lang.reflect.Field field : fields) {
                    field.setAccessible(true);
                    Object value = field.get(toastInstance);
                    if (value != null) {
                        // Try to get display info from the advancement
                        try {
                            // 1.20.x: Advancement.getDisplay() -> AdvancementDisplay
                            java.lang.reflect.Method getDisplay = value.getClass().getMethod("getDisplay");
                            Object display = getDisplay.invoke(value);
                            if (display != null) {
                                java.lang.reflect.Method getTitle = display.getClass().getMethod("getTitle");
                                Object titleText = getTitle.invoke(display);
                                if (titleText != null) {
                                    title = titleText.toString();
                                }
                            }
                        } catch (NoSuchMethodException ignored) {
                            // Not the advancement field, try next
                        }
                        break;
                    }
                }
            } catch (Throwable ignored) {
                // Fall through with default title
            }

            MinecraftClient client = MinecraftClient.getInstance();
            manager.onAdvancementEarned(client, title);
        } catch (Throwable t) {
            RecordableMod.LOGGER.debug("Failed to process advancement for auto-clip (1.20.x).", t);
        }
    }
}
