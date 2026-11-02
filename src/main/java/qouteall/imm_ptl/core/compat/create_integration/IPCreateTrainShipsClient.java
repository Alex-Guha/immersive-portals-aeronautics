/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

/**
 * Where a carriage copy's cut is drawn. A cut is kept in the coordinates of the copy's track, and a copy on a ship is
 * drawn through the ship's render pose of this frame, so its cut has to be too, or a moving ship shears it off the
 * carriage. A copy in the world stays as it is.
 */
final class IPCreateTrainShipsClient {
    private IPCreateTrainShipsClient() {
    }

    private static boolean noShips() {
        return !IPCreatePortalEnds.SABLE;
    }

    static Vec3 renderPoint(final Entity entity, final Vec3 point) {
        if (noShips()) {
            return point;
        }
        return Ships.renderPoint(entity, point);
    }

    static Vec3 renderDirection(final Entity entity, final Vec3 direction) {
        if (noShips()) {
            return direction;
        }
        return Ships.renderDirection(entity, direction);
    }

    /** Only loaded with Sable. */
    private static final class Ships {
        private static Pose3dc pose(final Entity entity) {
            final SubLevel ship = Sable.HELPER.getContaining(entity);
            return ship instanceof final ClientSubLevel client
                ? client.renderPose() : null;
        }

        static Vec3 renderPoint(final Entity entity, final Vec3 point) {
            final Pose3dc pose = pose(entity);
            return pose == null ? point : pose.transformPosition(point);
        }

        static Vec3 renderDirection(final Entity entity, final Vec3 direction) {
            final Pose3dc pose = pose(entity);
            return pose == null ? direction : pose.transformNormal(direction).normalize();
        }
    }
}
