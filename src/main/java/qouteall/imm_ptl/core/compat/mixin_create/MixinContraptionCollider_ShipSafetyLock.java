/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorFrames;

/**
 * Client: Create's guard that keeps a player standing on a moving pulley or elevator from sinking into it
 * ({@code savePlayerFromClipping}) casts rays down onto the contraption and puts the player on the highest hit. On a
 * ship, Sable traces the rays in the ship's plot but Create turns the hit into a "global" position with
 * {@code toGlobalVector}, which is still in the plot, and compares it with the player's height in the world: the player
 * was put as far above as the plot lies above the world (about 190 blocks) as the car started to move. The hit is
 * mapped into the world with the ship's pose, the one Sable's ray used.
 */
@Mixin(value = ContraptionCollider.class, remap = false)
public class MixinContraptionCollider_ShipSafetyLock {
    @WrapOperation(
        method = "savePlayerFromClipping",
        at = @At(value = "INVOKE",
            target = "Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;toGlobalVector(Lnet/minecraft/world/phys/Vec3;F)Lnet/minecraft/world/phys/Vec3;"),
        require = 1
    )
    private static Vec3 ip_hitInWorld(AbstractContraptionEntity contraption, Vec3 local, float partialTicks, Operation<Vec3> original) {
        final Vec3 inFrame = original.call(contraption, local, partialTicks);
        return IPElevatorFrames.SABLE ? IPElevatorFrames.toWorldOf(contraption, inFrame) : inFrame;
    }
}
