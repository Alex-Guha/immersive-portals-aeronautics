/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalTracks;

/**
 * Removing one end of a portal track pair linked by {@link IPCreatePortalTracks} leaves the other end the player laid as
 * a bridge into the portal, waiting for a track at this end again, instead of Create destroying it.
 */
@Mixin(value = TrackBlockEntity.class, remap = false)
public class MixinTrackBlockEntity_PortalTracks {
    @WrapOperation(
        method = "remove",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;destroyBlock(Lnet/minecraft/core/BlockPos;Z)Z")
    )
    private boolean ip_keepLaidTrack(final ServerLevel level, final BlockPos pos, final boolean drop, final Operation<Boolean> original) {
        return IPCreatePortalTracks.onPartnerRemoved(level, pos, ((TrackBlockEntity) (Object) this).getBlockPos()) || original.call(level, pos, drop);
    }
}
