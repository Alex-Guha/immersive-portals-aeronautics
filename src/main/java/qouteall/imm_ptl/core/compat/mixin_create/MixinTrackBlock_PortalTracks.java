/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.track.TrackBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalTracks;

/**
 * Tracks at IP portals become a bridge into the portal and link to a track the player laid at the other end, instead of
 * Create placing one there; an end of ours whose portal goes away becomes an ordinary track again instead of vanishing
 * ({@link IPCreatePortalTracks}).
 */
@Mixin(value = TrackBlock.class, remap = false)
public class MixinTrackBlock_PortalTracks {
    @Inject(method = "connectToPortal", at = @At("HEAD"), cancellable = true)
    private void ip_linkLaidTracks(final ServerLevel level, final BlockPos pos, final BlockState state, final CallbackInfo ci) {
        if (IPCreatePortalTracks.connect(level, pos, state)) {
            ci.cancel();
        }
    }

    @Inject(method = "updateShape", at = @At("RETURN"), cancellable = true)
    private void ip_keepTrackWithoutPortal(
        final BlockState state, final Direction direction, final BlockState neighbour, final LevelAccessor level,
        final BlockPos pos, final BlockPos neighbourPos, final CallbackInfoReturnable<BlockState> cir
    ) {
        if (level instanceof final ServerLevel serverLevel && cir.getReturnValue().isAir()
            && state.getValue(TrackBlock.SHAPE).isPortal()) {
            final BlockState ordinary = IPCreatePortalTracks.onPortalLost(serverLevel, pos, state);
            if (ordinary != null) {
                cir.setReturnValue(ordinary);
            }
        }
    }
}
