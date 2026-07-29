package dev.recordable.mixin;

import dev.recordable.AutoClipManager;
import dev.recordable.RecordableMod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.game.ClientboundEntityEventPacket;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Reliable local-player totem-pop detection via entity event packet.
 */
@Mixin(ClientPacketListener.class)
public final class ClientPacketListenerTotemMixin {
    private static final byte TOTEM_STATUS_ID = 35;

    @Inject(method = "handleEntityEvent", at = @At("HEAD"))
    private void recordable$onEntityEvent(ClientboundEntityEventPacket packet, CallbackInfo ci) {
        try {
            if (packet.getEventId() != TOTEM_STATUS_ID) {
                return;
            }
            Minecraft client = Minecraft.getInstance();
            if (client == null || client.level == null || client.player == null) {
                return;
            }
            Entity entity = packet.getEntity(client.level);
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
