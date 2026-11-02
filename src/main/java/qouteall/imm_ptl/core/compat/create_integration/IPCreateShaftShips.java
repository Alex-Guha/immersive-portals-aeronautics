/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;

/**
 * The Sable side of shafts through portals ({@link IPCreatePortalShafts}): a ship's blocks live in its plot, so
 * their place in the world is the ship's current pose applied to plot coordinates. Only loaded when Sable is.
 */
final class IPCreateShaftShips {
    private IPCreateShaftShips() {
    }

    /** A ship's plot as a frame: plot coordinates to world coordinates by the ship's current (logical) pose. */
    record ShipFrame(SubLevel subLevel) implements IPCreatePortalShafts.Frame {
        @Override
        public Vec3 toWorld(final Vec3 local) {
            return subLevel.logicalPose().transformPosition(local);
        }

        @Override
        public Vec3 toWorldDirection(final Vec3 local) {
            return subLevel.logicalPose().transformNormal(local);
        }

        @Override
        public Vec3 toLocal(final Vec3 world) {
            return subLevel.logicalPose().transformPositionInverse(world);
        }

        @Override
        public Vec3 toLocalDirection(final Vec3 world) {
            return subLevel.logicalPose().transformNormalInverse(world);
        }

        @Override
        public boolean holds(final Level level, final BlockPos pos) {
            return Sable.HELPER.getContaining(level, pos) == subLevel;
        }

        @Override
        public UUID ship() {
            return subLevel.getUniqueId();
        }

        @Override
        public Quaternionf rotation() {
            return new Quaternionf().set(subLevel.logicalPose().orientation());
        }
    }

    /** The frame of the ship with this id in {@code level}, or null when it is not there. */
    static IPCreatePortalShafts.@Nullable Frame frameOf(final Level level, final UUID ship) {
        final dev.ryanhcode.sable.api.sublevel.SubLevelContainer container = dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        final SubLevel subLevel = container == null ? null : container.getSubLevel(ship);
        return subLevel == null ? null : new ShipFrame(subLevel);
    }

    /** The frame of the ship whose plot holds {@code pos}, or null for a world block. */
    static IPCreatePortalShafts.@Nullable Frame frameAt(final Level level, final BlockPos pos) {
        final SubLevel ship = Sable.HELPER.getContaining(level, pos);
        return ship == null ? null : new ShipFrame(ship);
    }

    /** The positions of the block entities of {@code frame}'s ship from {@code min} to {@code max} (plot coordinates). */
    static List<BlockPos> blockEntitiesIn(final IPCreatePortalShafts.Frame frame, final BlockPos min, final BlockPos max) {
        final List<BlockPos> found = new ArrayList<>();
        for (final var holder : ((ShipFrame) frame).subLevel().getPlot().getLoadedChunks()) {
            for (final BlockPos pos : holder.getChunk().getBlockEntities().keySet()) {
                if (pos.getX() >= min.getX() && pos.getX() <= max.getX() && pos.getY() >= min.getY() && pos.getY() <= max.getY()
                    && pos.getZ() >= min.getZ() && pos.getZ() <= max.getZ()) {
                    found.add(pos);
                }
            }
        }
        return found;
    }

    /** The frames of the ships whose bounds reach into {@code world}. */
    static List<IPCreatePortalShafts.Frame> shipsTouching(final Level level, final AABB world) {
        final List<IPCreatePortalShafts.Frame> frames = new ArrayList<>();
        for (final SubLevel ship : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(world))) {
            frames.add(new ShipFrame(ship));
        }
        return frames;
    }
}
