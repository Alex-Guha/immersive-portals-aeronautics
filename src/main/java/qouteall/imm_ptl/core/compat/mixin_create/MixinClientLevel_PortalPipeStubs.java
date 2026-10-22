/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalPipeStubs;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalShaftHalves;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalTrackStubs;

/**
 * A block change in any client world may add or remove the far half of a pipe at a portal ({@link IPCreatePortalPipeStubs})
 * or a track's bridge ({@link IPCreatePortalTrackStubs}), or a half shaft in a portal block ({@link IPCreatePortalShaftHalves}).
 */
@Mixin(ClientLevel.class)
public abstract class MixinClientLevel_PortalPipeStubs {
    @Inject(method = "sendBlockUpdated", at = @At("HEAD"))
    private void ip$pipeStubs(BlockPos pos, BlockState oldState, BlockState newState, int flags, CallbackInfo ci) {
        IPCreatePortalPipeStubs.onBlockChanged(oldState, newState, flags);
        IPCreatePortalShaftHalves.onBlockChanged(oldState, newState);
        IPCreatePortalTrackStubs.onBlockChanged(oldState, newState, flags);
    }
}
