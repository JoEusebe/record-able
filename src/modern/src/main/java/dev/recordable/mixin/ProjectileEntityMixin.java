package dev.recordable.mixin;

import dev.recordable.AutoClipManager;
import dev.recordable.RecordableMod;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Event-based projectile-kill detection for the auto-clip system - <b>Modern (26.x+)</b>.
 *
 * <p>Fabric's attack callbacks only fire for melee attacks, so kills made with a thrown
 * trident ("spear"), arrow, or other projectile were never detected. The previous workaround
 * scanned all world entities every tick for a player-owned projectile whose bounding box
 * overlapped a damaged entity - which was unreliable because projectile collision is resolved
 * within a single tick (the trident bounces away with Loyalty, embeds in the ground, or the
 * fast projectile never appears in the client entity list).</p>
 *
 * <p>This mixin injects at the head of {@link Projectile#onHitEntity} - the exact moment any
 * projectile strikes an entity - and forwards the owner and victim to
 * {@link AutoClipManager#onProjectileHitEntity}, giving guaranteed single-tick capture. The
 * handler is fully defensive: a recording hook must never disrupt vanilla projectile collision.</p>
 *
 * <p>Uses Mojang official names. {@code onHitEntity(EntityHitResult)} has a stable signature
 * across the entire supported range (Minecraft 26.0 - 26.2+), so no version-specific variant
 * is required.</p>
 */
@Mixin(Projectile.class)
public class ProjectileEntityMixin {

    @Inject(method = "onHitEntity", at = @At("HEAD"))
    private void recordable$onHitEntity(EntityHitResult result, CallbackInfo ci) {
        try {
            Projectile self = (Projectile) (Object) this;
            Entity owner = self.getOwner();
            if (owner == null) {
                return;
            }
            Entity victim = result.getEntity();
            AutoClipManager manager = RecordableMod.getAutoClipManager();
            if (manager != null) {
                manager.onProjectileHitEntity(owner, victim);
            }
        } catch (Throwable t) {
            // Never let the recording hook interfere with projectile collision handling.
        }
    }
}
