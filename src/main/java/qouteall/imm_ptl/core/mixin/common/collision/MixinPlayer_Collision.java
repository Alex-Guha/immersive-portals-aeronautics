/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 * Follows IPSable 0.5.0's MixinPlayer_Collision (its priority) by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.mixin.common.collision;

import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.collision.PortalCollisionHandler;
import qouteall.imm_ptl.core.ducks.IEEntity;

// Immersive Aeronautics: priority 900, as in IPSable 0.5.0 (r2smith141). Sable's player_standup.PlayerMixin wraps the
// Level.noCollision call inside this method; an overwrite at the default priority makes that wrap fail at boot (the
// fork dropped this mixin for it, and players crouched against blocks behind portals they flew into). Applied first,
// the overwrite keeps IP's portal-trimmed box and Sable's wrap adds the ship blocks to it.
@SuppressWarnings("resource")
@Mixin(value = Player.class, priority = 900)
public abstract class MixinPlayer_Collision {
    
    /**
     * @author qouteall
     * @reason mixin does not allow cancel in redirect
     */
    @Overwrite
    public boolean canPlayerFitWithinBlocksAndEntitiesWhen(Pose pose) {
        LivingEntity this_ = (LivingEntity) (Object) this;
        
        AABB box = this_.getDimensions(pose).makeBoundingBox(this_.position());
        AABB activeCollisionBox = ((IEEntity) this_).ip_getActiveCollisionBox(box);
        if (activeCollisionBox == null) {
            return true;
        }
        return this_.level().noCollision(
            this_, activeCollisionBox.deflate(1.0E-7)
            // TODO check the issue of wrongly crouch after going through a scaling portal
            //  when head is touching ceiling because of floating point error
        );
    }

    // Immersive Aeronautics: the edge check of a sneaking player looks for ground in the player's own world only. With
    // the eyes in the overworld and the feet on a stack's stairs in the nether it found none, and the player could not
    // take a step.
    @Inject(method = "canFallAtLeast", at = @At("RETURN"), cancellable = true)
    private void ip_onCanFallAtLeast(double x, double z, float distance, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }

        LivingEntity this_ = (LivingEntity) (Object) this;
        PortalCollisionHandler collisionHandler = ((IEEntity) this_).ip_getPortalCollisionHandler();
        if (collisionHandler == null) {
            return;
        }

        AABB box = this_.getBoundingBox();
        AABB ground = new AABB(
            box.minX + x, box.minY - distance - 1.0E-5F, box.minZ + z,
            box.maxX + x, box.minY, box.maxZ + z
        );
        if (collisionHandler.hasBlocksBehindPortal(this_, ground)) {
            cir.setReturnValue(false);
        }
    }
}
