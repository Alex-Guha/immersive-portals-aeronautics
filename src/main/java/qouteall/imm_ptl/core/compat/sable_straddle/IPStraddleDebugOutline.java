/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import net.minecraft.core.particles.DustParticleOptions;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3f;

/**
 * Debug view of straddle sessions (step 3, before projections are drawn): the edges of each straddling ship's plot box
 * at its pose mapped through the portal, as dust particles in the destination level (orange), every
 * {@value #INTERVAL_TICKS} ticks, for players within {@value #RANGE} blocks.
 */
final class IPStraddleDebugOutline {
    private static final int INTERVAL_TICKS = 5;
    private static final double RANGE = 96.0;
    private static final double SPACING = 0.5;
    private static final DustParticleOptions IMAGE = new DustParticleOptions(new Vector3f(1.0f, 0.55f, 0.0f), 1.0f);
    private static final int[][] EDGES = {
            {0, 1}, {2, 3}, {4, 5}, {6, 7},
            {0, 2}, {1, 3}, {4, 6}, {5, 7},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}
    };

    private IPStraddleDebugOutline() {
    }

    static void draw(final long tick) {
        if (tick % INTERVAL_TICKS != 0) {
            return;
        }
        IPStraddleSessions.forEach(session -> {
            if (session.ship.isRemoved() || session.dest.players().isEmpty()) {
                return;
            }
            final Pose3d mapped = session.isometry.mapPose(session.ship.logicalPose());
            final BoundingBox3ic box = session.ship.getPlot().getBoundingBox();
            final Vec3[] corners = new Vec3[8];
            for (int i = 0; i < 8; i++) {
                corners[i] = mapped.transformPosition(new Vec3(
                        (i & 1) == 0 ? box.minX() : box.maxX() + 1.0,
                        (i & 2) == 0 ? box.minY() : box.maxY() + 1.0,
                        (i & 4) == 0 ? box.minZ() : box.maxZ() + 1.0));
            }
            for (final ServerPlayer player : session.dest.players()) {
                if (player.position().distanceToSqr(corners[0]) <= RANGE * RANGE) {
                    drawEdges(session.dest, player, corners);
                }
            }
        });
    }

    private static void drawEdges(final ServerLevel level, final ServerPlayer player, final Vec3[] corners) {
        for (final int[] edge : EDGES) {
            final Vec3 from = corners[edge[0]];
            final Vec3 to = corners[edge[1]];
            final int steps = Math.max(1, (int) Math.ceil(from.distanceTo(to) / SPACING));
            for (int i = 0; i <= steps; i++) {
                final Vec3 p = from.lerp(to, (double) i / steps);
                level.sendParticles(player, IMAGE, true, p.x, p.y, p.z, 1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }
}
