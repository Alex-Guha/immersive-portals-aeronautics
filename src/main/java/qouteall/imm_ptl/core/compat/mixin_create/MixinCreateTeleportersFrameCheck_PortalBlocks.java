/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

/**
 * Create Teleporters' frame check takes only air or its own blocks inside the frame; IP's portal blocks, which
 * {@link qouteall.imm_ptl.core.compat.IPFramedPortalBlocks} puts in its immersive portal's opening, count as air.
 * Without this the frame turned invalid, the portal was removed with its portal blocks, and the frame was valid again:
 * the portal was rebuilt every second.
 */
@Mixin(targets = "net.createteleporters.procedures.ScalablePortalCheckerProcedure", remap = false)
public class MixinCreateTeleportersFrameCheck_PortalBlocks {
    @WrapOperation(
        method = "findValidPortalDimensions",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;isAir()Z"),
        require = 0
    )
    private static boolean ip_portalBlockIsAir(final BlockState state, final Operation<Boolean> original) {
        return state.getBlock() == PortalPlaceholderBlock.instance || original.call(state);
    }
}
