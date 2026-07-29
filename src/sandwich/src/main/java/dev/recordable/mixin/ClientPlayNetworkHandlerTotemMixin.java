package dev.recordable.mixin;

import dev.recordable.AutoClipManager;
import dev.recordable.RecordableMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.entity.Entity;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reliable local-player totem-pop detection via entity status packet.
 */
@Mixin(ClientPlayNetworkHandler.class)
public final class ClientPlayNetworkHandlerTotemMixin {
    private static final byte TOTEM_STATUS_ID = 35;

    @Inject(method = "onEntityStatus", at = @At("HEAD"))
    private void recordable$onEntityStatus(EntityStatusS2CPacket packet, CallbackInfo ci) {
        try {
            if (packet.getStatus() != TOTEM_STATUS_ID) {
                return;
            }
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || client.world == null || client.player == null) {
                return;
            }
            Entity entity = packet.getEntity(client.world);
            if (entity != client.player) {
                return;
            }
            AutoClipManager manager = RecordableMod.getAutoClipManager();
            if (manager != null) {
                manager.onTotemPopObserved(client);
            }
        } catch (Throwable ignored) {
        }
    }
}
