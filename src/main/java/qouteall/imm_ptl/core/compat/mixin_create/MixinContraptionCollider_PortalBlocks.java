/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.contraptions.ContraptionCollider;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

/**
 * A pulley, piston or gantry contraption stops at a portal's blocks as at a solid block. Kept in its own level
 * ({@link MixinControlledContraptionEntity_PortalGuard}), it would otherwise move into the portal blocks (they have no
 * collision) and on into whatever lies behind the plane, and when it stopped Create would place its blocks there, over
 * the portal blocks, which breaks the portal. Elevators never stop on blocks and are not affected.
 */
@Mixin(value = ContraptionCollider.class, remap = false)
public class MixinContraptionCollider_PortalBlocks {
    @WrapOperation(
        method = "isCollidingWithWorld",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"
        )
    )
    private static VoxelShape ip_portalBlocksStopContraptions(
        BlockState state, BlockGetter level, BlockPos pos, Operation<VoxelShape> original
    ) {
        if (state.getBlock() == PortalPlaceholderBlock.instance) {
            return Shapes.block();
        }
        return original.call(state, level, pos);
    }
}
