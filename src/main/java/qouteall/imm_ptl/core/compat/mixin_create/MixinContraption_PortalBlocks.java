/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.Contraption;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

/**
 * A contraption placing its blocks never replaces a portal's blocks: Create counts them as unbreakable there and drops
 * the block that would have gone in their place as an item, as it does in front of bedrock. Replacing one breaks the
 * portal. This covers an elevator car stopped inside a portal's blocks, which nothing stops on the way in.
 */
@Mixin(value = Contraption.class, remap = false)
public class MixinContraption_PortalBlocks {
    @WrapOperation(
        method = "addBlocksToWorld",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getDestroySpeed(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"
        )
    )
    private float ip_portalBlocksStayInPlace(BlockState state, BlockGetter level, BlockPos pos, Operation<Float> original) {
        if (state.getBlock() == PortalPlaceholderBlock.instance) {
            return -1.0f;
        }
        return original.call(state, level, pos);
    }
}
