/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.mixin.common;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.ducks.IERayTraceContext;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

@Mixin(ClipContext.class)
public abstract class MixinClipContext implements IERayTraceContext {
    @Shadow
    @Final
    @Mutable
    private Vec3 from;
    
    @Shadow
    @Final
    @Mutable
    private Vec3 to;
    
    @Shadow
    @Final
    private ClipContext.Block block;
    
    @Shadow
    @Final
    private CollisionContext collisionContext;
    
    @Override
    public IERayTraceContext ip_setStart(Vec3 newStart) {
        from = newStart;
        return this;
    }
    
    @Override
    public IERayTraceContext ip_setEnd(Vec3 newEnd) {
        to = newEnd;
        return this;
    }
    
    // portal placeholder does not have outline if colliding with portal
    // placeholder blocks entity view
    @Inject(
        at = @At("HEAD"),
        method = "Lnet/minecraft/world/level/ClipContext;getBlockShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        cancellable = true
    )
    private void onGetBlockShape(
        BlockState blockState,
        BlockGetter blockView,
        BlockPos blockPos,
        CallbackInfoReturnable<VoxelShape> cir
    ) {
        if (blockState.getBlock() == PortalPlaceholderBlock.instance) {
            if (block == ClipContext.Block.OUTLINE) {
                if (blockView instanceof Level world) {
                    boolean intersectingWithPortal = McHelper.traverseEntitiesByBox(
                        Portal.class,
                        world,
                        new AABB(blockPos),
                        IPGlobal.maxNormalPortalRadius,
                        p -> McHelper.placeholder
                    ) != null;
                    
                    // Immersive Aeronautics: a portal block on a Sable ship is in plot space, its portal where the ship is
                    // (a ship-mounted portal): without this it took clicks meant for what is behind it, and broke
                    if (intersectingWithPortal
                        || qouteall.imm_ptl.core.compat.sable_integration.IPShipBlockInteraction.isShipPortalBlock(world, blockPos)) {
                        cir.setReturnValue(Shapes.empty());
                    }
                }
            }
            
            // TODO disable non-player entity view through placeholder block?
        }
    }
}
