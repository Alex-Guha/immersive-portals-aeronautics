/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Follows IPSable 0.5.0's IplStraddleCanFallMixin by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinhelpers.CanFallAtleastHelper;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut;

import java.util.ArrayList;
import java.util.List;

/**
 * Sneaking at a ship's edge (Sable's "can the player fall here" test):
 * <ul>
 *     <li>the part of a straddling ship that is through its portal gives no support in the ship's own world;</li>
 *     <li>the far half of a ship straddling into the entity's world does: it joins the ships tested, posed through the
 *     portal, with its blocks read from its own level and only those with a part past the portal plane (without
 *     this, a player sneaking on a far half finds no ground anywhere and cannot move).</li>
 * </ul>
 * Same treatment as {@code MixinSableEntityCollision_StraddleCut}: the ship being read is the one whose
 * {@code lastPose()} was read last.
 */
@Mixin(value = CanFallAtleastHelper.class, remap = false)
public class MixinSableCanFall_StraddleCut {
    @Unique
    private record FarHalf(IPStraddleCut.Projection projection, Pose3d mappedPose) {
    }

    @Unique
    private static final ThreadLocal<FarHalf> ip_current = new ThreadLocal<>();

    @WrapOperation(
        method = "canFallAtleastWithSubLevels",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getAllIntersecting(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/math/BoundingBox3dc;)Ljava/lang/Iterable;"),
        require = 0
    )
    private static Iterable<SubLevel> ip_addFarHalves(final ActiveSableCompanion helper, final Level level, final BoundingBox3dc bounds,
                                                      final Operation<Iterable<SubLevel>> original) {
        ip_current.remove();
        final Iterable<SubLevel> own = original.call(helper, level, bounds);
        final List<IPStraddleCut.Projection> projections = IPStraddleCut.projectionsInto(level);
        if (projections.isEmpty()) {
            return own;
        }
        final AABB box = new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
        final List<SubLevel> all = new ArrayList<>();
        own.forEach(all::add);
        for (final IPStraddleCut.Projection projection : projections) {
            if (!all.contains(projection.ship()) && projection.mappedBounds().intersects(box)) {
                all.add(projection.ship());
            }
        }
        return all;
    }

    @WrapOperation(
        method = "canFallAtleastWithSubLevels",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/SubLevel;lastPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;"),
        require = 0
    )
    private static Pose3dc ip_mapLastPose(final SubLevel ship, final Operation<Pose3dc> original, final Level level, final AABB aabb) {
        final Pose3dc pose = original.call(ship);
        final IPStraddleCut.Projection projection = ship.getLevel() == level ? null : IPStraddleCut.projectionOf(level, ship);
        if (projection == null) {
            ip_current.remove();
            return pose;
        }
        final Pose3d mapped = projection.map().mapPose(pose);
        ip_current.set(new FarHalf(projection, mapped));
        return mapped;
    }

    @WrapOperation(
        method = "canFallAtleastWithSubLevels",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
        require = 0
    )
    private static BlockState ip_straddleBlocks(final Level level, final BlockPos pos, final Operation<BlockState> original) {
        final FarHalf far = ip_current.get();
        if (far != null) {
            if (!far.projection().keepsBlock(far.mappedPose(), pos)) {
                return Blocks.AIR.defaultBlockState();
            }
            return far.projection().ship().getLevel().getBlockState(pos);
        }
        if (IPStraddleCut.isCutAway(level, pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        return original.call(level, pos);
    }

    /** Far-half block shapes look at their neighbours in the ship's own level. */
    @WrapOperation(
        method = "canFallAtleastWithSubLevels",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/block/state/BlockState;getCollisionShape(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/phys/shapes/VoxelShape;"),
        require = 0
    )
    private static VoxelShape ip_farHalfShape(final BlockState state, final BlockGetter getter, final BlockPos pos,
                                              final Operation<VoxelShape> original) {
        final FarHalf far = ip_current.get();
        return original.call(state, far != null ? far.projection().ship().getLevel() : getter, pos);
    }
}
