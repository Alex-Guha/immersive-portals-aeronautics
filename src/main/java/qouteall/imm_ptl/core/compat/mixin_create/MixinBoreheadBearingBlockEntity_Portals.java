/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.offroad.content.blocks.borehead_bearing.BoreheadBearingBlockEntity;
import dev.ryanhcode.offroad.handlers.server.MultiMiningSupplier;
import dev.ryanhcode.sable.util.LevelAccelerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPBoreheadPortals;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddle;

import java.util.List;

/**
 * Offroad's borehead grinds through a portal its ship straddles ({@link IPBoreheadPortals}): of the blocks its search
 * around the rock cutting wheels looks at, those past the plane are read in the portal's destination and handed to that
 * level's mining manager at their mapped positions. A bearing on a ship that straddles nothing runs unchanged.
 */
@Mixin(value = BoreheadBearingBlockEntity.class, remap = false)
public abstract class MixinBoreheadBearingBlockEntity_Portals {
    @Unique
    private List<IPStraddle.FarSide> ip_farSides = List.of();
    /** The block of the search's current position when it is past a plane, else null. */
    @Unique
    @Nullable
    private IPBoreheadPortals.Far ip_far;

    @Inject(method = "updateMiningBlocks", at = @At("HEAD"), require = 0)
    private void ip_findFarSides(final CallbackInfo ci) {
        this.ip_far = null;
        this.ip_farSides = IPBoreheadPortals.farSides((BlockEntity) (Object) this);
    }

    @Inject(method = "updateMiningBlocks", at = @At("RETURN"), require = 0)
    private void ip_forgetFarSides(final CallbackInfo ci) {
        this.ip_far = null;
        this.ip_farSides = List.of();
    }

    @WrapOperation(method = "updateMiningBlocks", require = 0, at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/util/LevelAccelerator;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState ip_readFarBlock(final LevelAccelerator accelerator, final BlockPos pos, final Operation<BlockState> original) {
        this.ip_far = this.ip_farSides.isEmpty() ? null : IPBoreheadPortals.far(this.ip_farSides, pos);
        return this.ip_far != null ? this.ip_far.state() : original.call(accelerator, pos);
    }

    @WrapOperation(method = "updateMiningBlocks", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getDestroySpeed(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)F"))
    private float ip_farDestroySpeed(final BlockState state, final BlockGetter level, final BlockPos pos, final Operation<Float> original) {
        final IPBoreheadPortals.Far far = this.ip_far;
        return far != null ? original.call(state, far.level(), far.pos()) : original.call(state, level, pos);
    }

    @WrapOperation(method = "updateMiningBlocks", require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"))
    private VoxelShape ip_farCollisionShape(final BlockState state, final BlockGetter level, final BlockPos pos, final Operation<VoxelShape> original) {
        final IPBoreheadPortals.Far far = this.ip_far;
        return far != null ? original.call(state, far.level(), far.pos()) : original.call(state, level, pos);
    }

    @WrapOperation(method = "updateMiningBlocks", require = 0, at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/offroad/handlers/server/MultiMiningServerManager;addOrRefreshPos(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/BlockPos;Ldev/ryanhcode/offroad/handlers/server/MultiMiningSupplier;)Z"))
    private boolean ip_mineInFarLevel(final Level level, final BlockPos pos, final MultiMiningSupplier supplier, final Operation<Boolean> original) {
        final IPBoreheadPortals.Far far = this.ip_far;
        if (far == null) {
            return original.call(level, pos, supplier);
        }
        if (far.level() != level) {
            IPBoreheadPortals.markFarSupplier(far.level(), ((BlockEntity) (Object) this).getBlockPos());
        }
        return original.call(far.level(), far.pos(), supplier);
    }
}
