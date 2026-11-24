/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.portal_pieces;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Where a block's coordinates sit in the world: a ship's plot, moved by the ship. World blocks have none (null). */
public interface IPPortalFrame {
    Vec3 toWorld(Vec3 local);

    Vec3 toWorldDirection(Vec3 local);

    Vec3 toLocal(Vec3 world);

    Vec3 toLocalDirection(Vec3 world);

    /** Whether {@code pos} is a block of this frame (of this ship's plot). */
    boolean holds(Level level, BlockPos pos);

    /** The ship's id. */
    java.util.UUID ship();

    /** The rotation from frame to world directions. */
    org.joml.Quaternionf rotation();

    /** How many world blocks one of the frame's blocks spans (a ship's Pym scale). */
    default double scale() {
        return 1.0;
    }

    /** The frame's coordinates to the world's, scale included. */
    default org.joml.Matrix4d matrix() {
        final Vec3 origin = toWorld(Vec3.ZERO);
        return new org.joml.Matrix4d().translation(origin.x, origin.y, origin.z)
            .rotate(new org.joml.Quaterniond(rotation())).scale(scale());
    }
}
