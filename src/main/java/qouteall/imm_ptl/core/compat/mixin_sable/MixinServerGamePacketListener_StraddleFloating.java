/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut;

/**
 * A player standing on the far half of a ship straddling a portal into their dimension is not "floating" (vanilla
 * kicks floating players after 80 ticks where flight is not allowed): the server does not track them on that ship
 * (see {@link MixinSableEntityCollision_StraddleCut}), so it would see them hover. Nor is one standing, through a portal
 * they touch, on a ship behind it (feet below a dimension stack's ceiling, eyes above).
 */
@Mixin(ServerGamePacketListenerImpl.class)
public class MixinServerGamePacketListener_StraddleFloating {
    @Inject(method = "noBlocksAround", at = @At("RETURN"), cancellable = true, require = 0)
    private void ip_farHalfIsGround(final Entity entity, final CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()) {
            return;
        }
        for (final IPStraddleCut.Projection projection : IPStraddleCut.projectionsInto(entity.level())) {
            if (projection.mappedBounds().intersects(entity.getBoundingBox().inflate(1.0))) {
                cir.setReturnValue(false);
                return;
            }
        }
        final qouteall.imm_ptl.core.collision.PortalCollisionHandler portalCollision =
            ((qouteall.imm_ptl.core.ducks.IEEntity) entity).ip_getPortalCollisionHandler();
        if (portalCollision != null && portalCollision.hasCollisionEntry()
                && !IPStraddleCut.behindPortals(entity.level(), portalCollision.getCollidingPortals(), entity.getBoundingBox().inflate(1.0)).isEmpty()) {
            cir.setReturnValue(false);
        }
    }
}
