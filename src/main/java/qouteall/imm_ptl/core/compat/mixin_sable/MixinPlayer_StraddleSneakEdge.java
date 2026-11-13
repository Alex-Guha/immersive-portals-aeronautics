/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.Sable;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut;

/**
 * Sneaking along the edge of a straddling ship's far half: vanilla backs the movement off the edge along the world's
 * X and Z axes, which stops a player walking along an edge turned against them. On a ship of the player's own level
 * Sable runs the edge check in the ship's frame; on a far half (never "tracked") vanilla's runs, so here it runs in the
 * far half's frame (its yaw through the portal): the movement is turned into that frame, each ground probe turned back
 * to the world, and the result turned back.
 */
@Mixin(Player.class)
public abstract class MixinPlayer_StraddleSneakEdge {
    /** The far half's yaw frame for the edge check in progress, or null. */
    @Unique
    private Quaterniond ip_edgeFrame;

    @ModifyVariable(method = "maybeBackOffFromEdge", at = @At("HEAD"), argsOnly = true, require = 0)
    private Vec3 ip_toFarHalfFrame(final Vec3 movement, final Vec3 original, final MoverType moverType) {
        this.ip_edgeFrame = null;
        final Player self = (Player) (Object) this;
        if (Sable.HELPER.getTrackingSubLevel(self) != null) {
            return movement;
        }
        final IPStraddleCut.Projection projection = IPStraddleCut.farHalfNear(self);
        if (projection == null) {
            return movement;
        }
        final double yaw = projection.map().mapOrientation(projection.ship().lastPose().orientation())
            .getEulerAnglesYXZ(new Vector3d()).y;
        this.ip_edgeFrame = new Quaterniond().rotateY(yaw);
        final Vector3d local = this.ip_edgeFrame.transformInverse(new Vector3d(movement.x, 0.0, movement.z));
        return new Vec3(local.x, movement.y, local.z);
    }

    @WrapOperation(
        method = "maybeBackOffFromEdge",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/player/Player;canFallAtLeast(DDF)Z"),
        require = 0
    )
    private boolean ip_probeInWorld(final Player player, final double x, final double z, final float distance,
                                    final Operation<Boolean> original) {
        if (this.ip_edgeFrame == null) {
            return original.call(player, x, z, distance);
        }
        final Vector3d world = this.ip_edgeFrame.transform(new Vector3d(x, 0.0, z));
        return original.call(player, world.x, world.z, distance);
    }

    @ModifyReturnValue(method = "maybeBackOffFromEdge", at = @At("RETURN"), require = 0)
    private Vec3 ip_backToWorld(final Vec3 movement) {
        if (this.ip_edgeFrame == null) {
            return movement;
        }
        final Vector3d world = this.ip_edgeFrame.transform(new Vector3d(movement.x, 0.0, movement.z));
        this.ip_edgeFrame = null;
        return new Vec3(world.x, movement.y, world.z);
    }
}
