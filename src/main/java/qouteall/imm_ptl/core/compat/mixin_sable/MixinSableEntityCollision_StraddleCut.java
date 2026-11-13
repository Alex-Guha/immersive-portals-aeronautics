/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The far-half pose substitution follows IPSable 0.5.0's IplStraddleCollisionPoseMixin by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.math.LevelReusedVectors;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.mixinterface.voxel_shape_iteration.FastVoxelShapeIterable;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.mixinterface.entity.entity_sublevel_collision.EntityMovementExtension;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.entity_collision.SubLevelEntityCollision;
import dev.ryanhcode.sable.util.LevelAccelerator;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut;

import java.util.ArrayList;
import java.util.List;

/**
 * Entity collision with straddling ships (docs/lessons/straddle-entities.md), inside Sable's
 * {@link SubLevelEntityCollision#collide}:
 * <ul>
 *     <li>in a ship's own world, blocks wholly past its portal plane read as air (not drawn there, not solid);</li>
 *     <li>in the destination world, the ship is a candidate when its bounds mapped through the portal meet the
 *     entity, its poses read as mapped, and its blocks come from its own level (plot coordinates repeat between
 *     levels), only those with a part past the destination plane;</li>
 *     <li>for the part of an entity past a portal it touches, the destination's ships behind that portal are candidates
 *     too, mapped back through it, only their blocks behind its plane and in its opening (as IP's collision takes the
 *     destination's blocks there);</li>
 *     <li>an entity never keeps "tracking" (standing on, being networked relative to) a ship of another level: Sable's
 *     plot-local position packets, {@code getOnPos} and carry all assume the ship is in the entity's level. On the far
 *     half the entity collides and stands, and is moved along within the tick, but is not carried across ticks.</li>
 * </ul>
 * The ship whose blocks are being read is the one whose {@code logicalPose()} Sable read last, at the head of its
 * per-ship loop iteration (step-up, sweep and SAT run inside it).
 */
@Mixin(value = SubLevelEntityCollision.class, remap = false)
public class MixinSableEntityCollision_StraddleCut {
    @Unique
    private static final ThreadLocal<FarHalf> ip_current = new ThreadLocal<>();

    /**
     * @param behind the touched portal the ship is seen through (its part behind that portal collides), or null for
     *               a far half
     */
    @Unique
    private record FarHalf(IPStraddleCut.Projection projection, Pose3d mappedPose, LevelAccelerator accelerator,
                           List<double[]> localPlanes, qouteall.imm_ptl.core.portal.@org.jetbrains.annotations.Nullable Portal behind) {
    }

    @Unique
    private record Behind(IPStraddleCut.Projection projection, qouteall.imm_ptl.core.portal.Portal portal) {
    }

    /** Ships behind the portals the colliding entity touches, per collide call ({@link IPStraddleCut#behindPortals}). */
    @Unique
    private static final ThreadLocal<java.util.IdentityHashMap<SubLevel, Behind>> ip_behind =
        ThreadLocal.withInitial(java.util.IdentityHashMap::new);

    /** A block read last near a cut plane: its collision boxes are trimmed to the kept side. */
    @Unique
    private record Crossing(BlockPos pos, List<double[]> planes) {
    }

    @Unique
    private static final ThreadLocal<Crossing> ip_crossing = new ThreadLocal<>();
    /** The IP portals the colliding entity touches (ship blocks beyond them, in their opening, do not collide). */
    @Unique
    private static final ThreadLocal<List<qouteall.imm_ptl.core.portal.Portal>> ip_portals = ThreadLocal.withInitial(List::of);
    /** Keep planes (plot space) of the straddling ships of the entity's own level, per collide call. */
    @Unique
    private static final ThreadLocal<java.util.IdentityHashMap<SubLevel, List<double[]>>> ip_sourcePlanes =
        ThreadLocal.withInitial(java.util.IdentityHashMap::new);

    @Inject(method = "collide", at = @At("HEAD"), require = 0)
    private static void ip_dropForeignTracking(final Entity entity, final Vec3 collisionMotion, final Vec3 velocityMotion,
                                               final LevelReusedVectors sink, final CallbackInfoReturnable<SubLevelEntityCollision.CollisionInfo> cir) {
        ip_current.remove();
        ip_crossing.remove();
        ip_sourcePlanes.get().clear();
        ip_behind.get().clear();
        final qouteall.imm_ptl.core.collision.PortalCollisionHandler portalCollision =
            ((qouteall.imm_ptl.core.ducks.IEEntity) entity).ip_getPortalCollisionHandler();
        ip_portals.set(portalCollision != null && portalCollision.hasCollisionEntry() ? List.copyOf(portalCollision.getCollidingPortals()) : List.of());
        final SubLevel tracking = Sable.HELPER.getTrackingSubLevel(entity);
        if (tracking != null && tracking.getLevel() != entity.level()) {
            // e.g. carried through a portal by IP while standing on a ship
            ((EntityMovementExtension) entity).sable$setTrackingSubLevel(null);
        }
    }

    @Inject(method = "collide", at = @At("RETURN"), require = 0)
    private static void ip_noForeignTracking(final Entity entity, final Vec3 collisionMotion, final Vec3 velocityMotion,
                                             final LevelReusedVectors sink, final CallbackInfoReturnable<SubLevelEntityCollision.CollisionInfo> cir) {
        ip_current.remove();
        ip_crossing.remove();
        ip_sourcePlanes.get().clear();
        ip_behind.get().clear();
        ip_portals.remove();
        final SubLevelEntityCollision.CollisionInfo info = cir.getReturnValue();
        if (info != null && info.trackingSubLevel != null && info.trackingSubLevel.getLevel() != entity.level()) {
            info.trackingSubLevel = null;
        }
    }

    @WrapOperation(
        method = "collide",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/ActiveSableCompanion;getAllIntersecting(Lnet/minecraft/world/level/Level;Ldev/ryanhcode/sable/companion/math/BoundingBox3dc;)Ljava/lang/Iterable;"),
        require = 0
    )
    private static Iterable<SubLevel> ip_addFarHalves(final ActiveSableCompanion helper, final Level level, final BoundingBox3dc bounds,
                                                      final Operation<Iterable<SubLevel>> original) {
        final Iterable<SubLevel> own = original.call(helper, level, bounds);
        final List<IPStraddleCut.Projection> projections = IPStraddleCut.projectionsInto(level);
        final List<qouteall.imm_ptl.core.portal.Portal> portals = ip_portals.get();
        if (projections.isEmpty() && portals.isEmpty()) {
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
        if (!portals.isEmpty()) {
            final java.util.IdentityHashMap<SubLevel, Behind> behind = ip_behind.get();
            for (final IPStraddleCut.Projection projection : IPStraddleCut.behindPortals(level, portals, box)) {
                if (behind.containsKey(projection.ship())) {
                    continue;
                }
                for (final qouteall.imm_ptl.core.portal.Portal portal : portals) {
                    if (portal.getUUID().equals(projection.portal())) {
                        behind.put(projection.ship(), new Behind(projection, portal));
                        break;
                    }
                }
                if (!all.contains(projection.ship())) {
                    all.add(projection.ship());
                }
            }
        }
        return all;
    }

    @WrapOperation(
        method = "collide",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/SubLevel;logicalPose()Ldev/ryanhcode/sable/companion/math/Pose3d;"),
        require = 0
    )
    private static Pose3d ip_mapLogicalPose(final SubLevel ship, final Operation<Pose3d> original,
                                            @Local(argsOnly = true) final Entity entity) {
        final Pose3d pose = original.call(ship);
        final IPStraddleCut.Projection projection = ship.getLevel() == entity.level() ? null : IPStraddleCut.projectionOf(entity.level(), ship);
        final Behind behind = ship.getLevel() == entity.level() ? null : ip_behind.get().get(ship);
        if (behind != null) {
            final Pose3d mapped = behind.projection().map().mapPose(pose);
            final List<double[]> planes;
            if (projection != null && ip_samePlane(projection, behind.projection())) {
                // straddling into this world through that plane: its far half in front of it, the rest behind
                planes = List.of();
            } else {
                planes = new ArrayList<>(IPStraddleCut.sourceLocalPlanes(ship.getLevel(), ship, pose));
                planes.add(behind.projection().localPlane(mapped));
            }
            ip_current.set(new FarHalf(behind.projection(), mapped, new LevelAccelerator(ship.getLevel()), planes, behind.portal()));
            return mapped;
        }
        if (projection == null) {
            ip_current.remove();
            return pose;
        }
        final Pose3d mapped = projection.map().mapPose(pose);
        ip_current.set(new FarHalf(projection, mapped, new LevelAccelerator(ship.getLevel()), List.of(projection.localPlane(mapped)), null));
        return mapped;
    }

    /**
     * @return whether a far half's keep plane and a ship behind a portal's keep plane are one plane, kept on opposite
     * sides
     */
    @Unique
    private static boolean ip_samePlane(final IPStraddleCut.Projection farHalf, final IPStraddleCut.Projection behind) {
        return farHalf.keepNormal().dot(behind.keepNormal()) < -0.99999
                && Math.abs(behind.keepNormal().dot(farHalf.keepPoint().subtract(behind.keepPoint()))) < 1.0e-3;
    }

    @WrapOperation(
        method = "collide",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/SubLevel;lastPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;"),
        require = 0
    )
    private static Pose3dc ip_mapLastPose(final SubLevel ship, final Operation<Pose3dc> original) {
        final Pose3dc pose = original.call(ship);
        final FarHalf far = ip_current.get();
        return far != null && far.projection().ship() == ship ? far.projection().map().mapPose(pose) : pose;
    }

    @WrapOperation(
        method = "*",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/util/LevelAccelerator;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
        require = 0
    )
    private static BlockState ip_straddleBlocks(final LevelAccelerator accelerator, final BlockPos pos, final Operation<BlockState> original) {
        ip_crossing.remove();
        final FarHalf far = ip_current.get();
        final BlockState state;
        List<double[]> planes;
        if (far != null) {
            state = far.accelerator().getBlockState(pos);
            planes = far.localPlanes();
        } else {
            state = original.call(accelerator, pos);
            planes = ip_sourcePlanes(((IPLevelAcceleratorAccessor) accelerator).ip_getLevel(), pos);
        }
        if (state.isAir()) {
            return state;
        }
        if (far != null && far.behind() != null && ip_besideOpening(far.behind(), far.mappedPose(), pos)) {
            return Blocks.AIR.defaultBlockState();
        }
        final List<qouteall.imm_ptl.core.portal.Portal> portals = ip_portals.get();
        if (!portals.isEmpty()) {
            final Pose3dc pose = far != null ? far.mappedPose() : ip_shipPose(((IPLevelAcceleratorAccessor) accelerator).ip_getLevel(), pos);
            if (pose != null) {
                planes = ip_withPortalPlanes(planes, portals, pose, pos, far != null ? far.behind() : null);
            }
        }
        if (planes.isEmpty()) {
            return state;
        }
        // Every block near a cut plane keeps its state and has its boxes trimmed at the planes, whether it crosses them,
        // lies wholly past them or wholly on the kept side: Pym's indexed collision (SubLevelIndexedCollision, 0.5.1)
        // serves boxes merged over several blocks under the first one's position. A block past the plane made air took
        // the whole merged box along (the far half of a 6x4 platform was one box under a cut corner: nothing stood on
        // it), and a kept block's box could reach past the plane (a 2x far half stood one block above it). The trim
        // drops a box wholly past the planes and leaves a wholly kept one as it is.
        final List<double[]> crossing = IPStraddleCut.classify(planes, pos);
        ip_crossing.set(new Crossing(pos.immutable(), crossing != null && crossing != IPStraddleCut.CUT ? crossing : planes));
        return state;
    }

    /**
     * @return whether the block at plot position {@code pos} of a ship seen through {@code portal} lies behind the
     * portal's plane but beside its opening (more than half a block out): IP's collision takes the destination's blocks
     * only through the opening
     */
    @Unique
    private static boolean ip_besideOpening(final qouteall.imm_ptl.core.portal.Portal portal, final Pose3dc pose, final BlockPos pos) {
        final Vec3 rel = pose.transformPosition(Vec3.atCenterOf(pos)).subtract(portal.getOriginPos());
        return rel.dot(portal.getNormal()) < 0.0
                && (Math.abs(rel.dot(portal.getAxisW())) > portal.getWidth() * 0.5 + 0.5
                || Math.abs(rel.dot(portal.getAxisH())) > portal.getHeight() * 0.5 + 0.5);
    }

    /**
     * @return the keep planes (plot space) of the straddling ship of {@code level} owning plot position {@code pos},
     * empty when there is none
     */
    @Unique
    private static List<double[]> ip_sourcePlanes(final Level level, final BlockPos pos) {
        if (!IPStraddleCut.anyFrom(level)) {
            return List.of();
        }
        final dev.ryanhcode.sable.api.sublevel.SubLevelContainer container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        final dev.ryanhcode.sable.sublevel.plot.LevelPlot plot = container == null ? null : container.getPlot(new net.minecraft.world.level.ChunkPos(pos));
        final SubLevel ship = plot == null ? null : plot.getSubLevel();
        if (ship == null) {
            return List.of();
        }
        return ip_sourcePlanes.get().computeIfAbsent(ship, s -> IPStraddleCut.sourceLocalPlanes(level, s, s.logicalPose()));
    }

    /**
     * @return the pose of the ship of {@code level} owning plot position {@code pos}, or null
     */
    @Unique
    @org.jetbrains.annotations.Nullable
    private static Pose3dc ip_shipPose(final Level level, final BlockPos pos) {
        final dev.ryanhcode.sable.api.sublevel.SubLevelContainer container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        final dev.ryanhcode.sable.sublevel.plot.LevelPlot plot = container == null ? null : container.getPlot(new net.minecraft.world.level.ChunkPos(pos));
        final SubLevel ship = plot == null ? null : plot.getSubLevel();
        return ship == null ? null : ship.logicalPose();
    }

    /**
     * Like IP's own collision of an entity touching a portal (which leaves out this world's blocks behind the portal,
     * in its opening: that space belongs to the other side), ship blocks behind a touched portal and in its opening
     * (plus half a block, at most 10 blocks deep) do not collide: an entity walking into a portal is not stopped by a
     * ship sitting in the opening on the other side of the plane (one straddling it from the other face, say). A ship
     * seen through one of those portals ({@code seenThrough}) is not cut at its plane: its part behind it is what collides.
     */
    @Unique
    private static List<double[]> ip_withPortalPlanes(final List<double[]> planes, final List<qouteall.imm_ptl.core.portal.Portal> portals,
                                                      final Pose3dc pose, final BlockPos pos,
                                                      final qouteall.imm_ptl.core.portal.@org.jetbrains.annotations.Nullable Portal seenThrough) {
        List<double[]> result = planes;
        final Vec3 center = pose.transformPosition(Vec3.atCenterOf(pos));
        for (final qouteall.imm_ptl.core.portal.Portal portal : portals) {
            if (portal == seenThrough) {
                continue;
            }
            final Vec3 rel = center.subtract(portal.getOriginPos());
            final double depth = rel.dot(portal.getNormal());
            if (depth > 1.0 || depth < -10.0
                    || Math.abs(rel.dot(portal.getAxisW())) > portal.getWidth() * 0.5 + 0.5
                    || Math.abs(rel.dot(portal.getAxisH())) > portal.getHeight() * 0.5 + 0.5) {
                continue;
            }
            if (result == planes) {
                result = new ArrayList<>(planes);
            }
            result.add(IPStraddleCut.toLocal(pose, portal.getOriginPos(), portal.getNormal()));
        }
        return result;
    }

    /**
     * The collision boxes of a block that crosses a cut plane end at the plane: exactly when the plane is axis-aligned
     * in the ship's frame, otherwise a box is dropped only when it is wholly past the plane.
     */
    @WrapOperation(
        method = "*",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/mixinterface/voxel_shape_iteration/FastVoxelShapeIterable;sable$allBoxes()Ljava/util/Iterator;"),
        require = 0
    )
    private static java.util.Iterator<BoundingBox3dc> ip_trimAtPlane(final FastVoxelShapeIterable shape,
                                                                     final Operation<java.util.Iterator<BoundingBox3dc>> original) {
        final java.util.Iterator<BoundingBox3dc> boxes = original.call(shape);
        final Crossing crossing = ip_crossing.get();
        if (crossing == null) {
            return boxes;
        }
        ip_crossing.remove();
        final List<BoundingBox3dc> trimmed = new ArrayList<>();
        while (boxes.hasNext()) {
            final BoundingBox3dc box = boxes.next();
            final BoundingBox3d kept = ip_trim(box, crossing);
            if (kept != null) {
                trimmed.add(kept);
            }
        }
        return trimmed.iterator();
    }

    @Unique
    @org.jetbrains.annotations.Nullable
    private static BoundingBox3d ip_trim(final BoundingBox3dc box, final Crossing crossing) {
        final double[] min = {box.minX(), box.minY(), box.minZ()};
        final double[] max = {box.maxX(), box.maxY(), box.maxZ()};
        if (!IPStraddleCut.trimBox(min, max, crossing.pos(), crossing.planes())) {
            return null;
        }
        return new BoundingBox3d(min[0], min[1], min[2], max[0], max[1], max[2]);
    }

    /**
     * Block shapes of the far half look at their neighbours in the ship's own level (fences, walls, panes).
     */
    @org.spongepowered.asm.mixin.injection.ModifyArg(
        method = "*",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/sublevel/entity_collision/SubLevelEntityCollision;getSubLevelEntityCollisionShape(Lnet/minecraft/world/entity/Entity;Lorg/joml/Vector3dc;Ldev/ryanhcode/sable/companion/math/Pose3dc;Lnet/minecraft/world/level/block/state/BlockState;Ldev/ryanhcode/sable/util/LevelAccelerator;Lnet/minecraft/core/BlockPos;Ldev/ryanhcode/sable/api/math/LevelReusedVectors;)Lnet/minecraft/world/phys/shapes/VoxelShape;"),
        index = 4,
        require = 0
    )
    private static LevelAccelerator ip_shapeLevel(final LevelAccelerator accelerator) {
        final FarHalf far = ip_current.get();
        return far != null ? far.accelerator() : accelerator;
    }
}
