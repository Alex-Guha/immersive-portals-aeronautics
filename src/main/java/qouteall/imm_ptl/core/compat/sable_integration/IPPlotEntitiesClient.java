/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * Where an entity on a ship really is. Some entities stand in the ship's plot, about 2e7 from the world (a Create
 * contraption on a ship, a train's carriage among them), and are drawn through the ship's pose; a test of such an entity
 * against anything in the world (a portal's plane, a distance from the camera) must go through the pose too.
 */
public final class IPPlotEntitiesClient {
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    private IPPlotEntitiesClient() {
    }

    /** {@code point} in {@code entity}'s coordinates, in the world, as this frame draws it. */
    public static Vec3 toWorld(final Entity entity, final Vec3 point) {
        return SABLE && IPSableCompatHelper.isPlotSpacePosition(point.x, point.z) ? Ships.toWorld(entity, point) : point;
    }

    /** {@code box} in {@code entity}'s coordinates, moved to where it is in the world (not turned). */
    public static AABB toWorld(final Entity entity, final AABB box) {
        final Vec3 centre = box.getCenter();
        return box.move(toWorld(entity, centre).subtract(centre));
    }

    /** Only loaded with Sable. */
    private static final class Ships {
        static Vec3 toWorld(final Entity entity, final Vec3 point) {
            return Sable.HELPER.getContaining(entity) instanceof final ClientSubLevel ship
                ? ship.renderPose().transformPosition(point) : point;
        }
    }
}
