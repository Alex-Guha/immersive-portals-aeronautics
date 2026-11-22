/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;

/**
 * The frames an elevator lives in (docs/plans/elevators.md, "through ship-mounted portals"): the world, or a ship's plot,
 * where Sable keeps Create contraptions, their blocks and their columns of contacts. Everything an elevator compares with
 * its shaft's plane is in its own frame; this maps between a frame and the world with the ship's tick pose. Sable's
 * classes are only touched with Sable present ({@link #SABLE}): without it every frame is the world.
 */
public final class IPElevatorFrames {
    public static final boolean SABLE = ModList.get().isLoaded("sable");

    private IPElevatorFrames() {
    }

    /** The ship whose plot holds {@code pos}, or null for the world. */
    public static @Nullable UUID shipAt(final Level level, final BlockPos pos) {
        if (!SABLE) {
            return null;
        }
        final SubLevel ship = Sable.HELPER.getContaining(level, pos);
        return ship == null ? null : ship.getUniqueId();
    }

    /**
     * Test hooks: the block at a world position, on a ship if a ship's block (not air) is there: its plot position;
     * else {@code world} itself.
     */
    public static BlockPos blockAt(final Level level, final BlockPos world) {
        if (!SABLE) {
            return world;
        }
        for (final SubLevel ship : Sable.HELPER.getAllIntersecting(level, new dev.ryanhcode.sable.companion.math.BoundingBox3d(world))) {
            final BlockPos plot = BlockPos.containing(ship.logicalPose().transformPositionInverse(Vec3.atCenterOf(world)));
            if (Sable.HELPER.getContaining(level, plot) == ship && !level.getBlockState(plot).isAir()) {
                return plot;
            }
        }
        return world;
    }

    /** A position in {@code entity}'s frame, in the world with its ship's tick pose ({@code framePos} if not on a ship). */
    public static Vec3 toWorldOf(final Entity entity, final Vec3 framePos) {
        final Pose3dc pose = SABLE ? poseOf(entity) : null;
        return pose == null ? framePos : pose.transformPosition(framePos);
    }

    /** Whether {@code entity} is in a ship's plot. */
    public static boolean isOnShip(final Entity entity) {
        return SABLE && Sable.HELPER.getContaining(entity) != null;
    }

    /**
     * Server: builds a contraption's physics collider again if Sable has one for it, so that a change of
     * {@link IPElevatorPortals#hasNoCollider} applies (Sable builds it once, when the contraption first ticks).
     */
    public static void refreshCollider(final Entity contraption) {
        if (!SABLE || !(contraption instanceof final dev.ryanhcode.sable.api.sublevel.KinematicContraption kinematic)
            || !(contraption.level() instanceof final net.minecraft.server.level.ServerLevel level)) {
            return;
        }
        final dev.ryanhcode.sable.api.physics.PhysicsPipeline pipeline =
            dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem.require(level).getPipeline();
        if (pipeline instanceof final qouteall.imm_ptl.core.compat.sable_integration.IPRapierPipelineContraptions tracked
            && tracked.ip_hasContraption(kinematic)) {
            pipeline.remove(kinematic);
            pipeline.add(kinematic);
        }
    }

    /** A position in the frame that holds {@code framePos}, in the world (this tick). */
    public static Vec3 toWorld(final Level level, final Vec3 framePos) {
        final Pose3dc pose = poseAt(level, framePos);
        return pose == null ? framePos : pose.transformPosition(framePos);
    }

    /** The world box around a box in the frame that holds {@code inFrame} (this tick). */
    public static AABB worldBox(final Level level, final Vec3 inFrame, final AABB box) {
        final Pose3dc pose = poseAt(level, inFrame);
        return pose == null ? box : transformed(box, pose, false);
    }

    /** The y extent, in {@code contraption}'s frame, of a box in the world ({min, max}). */
    public static double[] frameYRange(final Entity contraption, final AABB worldBox) {
        final Pose3dc pose = SABLE ? poseOf(contraption) : null;
        final AABB box = pose == null ? worldBox : transformed(worldBox, pose, true);
        return new double[]{box.minY, box.maxY};
    }

    /** Squared distance between two positions of one level, each in its own frame. */
    public static double distanceSqr(final Level level, final Vec3 a, final Vec3 b) {
        return SABLE ? Sable.HELPER.distanceSquaredWithSubLevels(level, a, b) : a.distanceToSqr(b);
    }

    private static @Nullable Pose3dc poseAt(final Level level, final Vec3 pos) {
        if (!SABLE) {
            return null;
        }
        final SubLevel ship = Sable.HELPER.getContaining(level, pos);
        return ship == null ? null : ship.logicalPose();
    }

    private static @Nullable Pose3dc poseOf(final Entity entity) {
        final SubLevel ship = Sable.HELPER.getContaining(entity);
        return ship == null ? null : ship.logicalPose();
    }

    private static AABB transformed(final AABB box, final Pose3dc pose, final boolean inverse) {
        double minX = Double.POSITIVE_INFINITY, minY = Double.POSITIVE_INFINITY, minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY, maxY = Double.NEGATIVE_INFINITY, maxZ = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            final Vec3 corner = new Vec3((i & 1) == 0 ? box.minX : box.maxX, (i & 2) == 0 ? box.minY : box.maxY,
                (i & 4) == 0 ? box.minZ : box.maxZ);
            final Vec3 p = inverse ? pose.transformPositionInverse(corner) : pose.transformPosition(corner);
            minX = Math.min(minX, p.x);
            minY = Math.min(minY, p.y);
            minZ = Math.min(minZ, p.z);
            maxX = Math.max(maxX, p.x);
            maxY = Math.max(maxY, p.y);
            maxZ = Math.max(maxZ, p.z);
        }
        return new AABB(minX, minY, minZ, maxX, maxY, maxZ);
    }
}
