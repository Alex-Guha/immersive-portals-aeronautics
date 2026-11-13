/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import org.joml.Vector3d;

/**
 * A ship's scale, which Sable's pose carries and Create: Pocket Sized (Pym) sets (docs/lessons/scaled-ships.md).
 * {@code Pose3dc} already applies it in its transforms; this is for the places that turn a plot-space size into a
 * world size, or a world plane into the ship's drawing space, by hand.
 */
public final class IPShipScale {
    private IPShipScale() {
    }

    /**
     * @return the pose's scale: Pym's is uniform; for a non-uniform one, the largest component, so a depth or a block
     * extent derived from it is never too small
     */
    public static double of(final Pose3dc pose) {
        final var scale = pose.scale();
        return Math.max(Math.abs(scale.x()), Math.max(Math.abs(scale.y()), Math.abs(scale.z())));
    }

    /**
     * A world clip plane's normal in a ship's drawing space. Ship terrain vertices reach the shader as
     * {@code s^-1 R^-1 (world - camera)} (Sable's {@code R^-1 (world - camera)}; Pym folds {@code matrix.scale(s)} into the
     * model view and divides the camera compensation by s), so {@code n . (world - camera) + w} is
     * {@code (s R^-1 n) . vertex + w}: the normal is rotated into the ship and multiplied by s, the plane's w stays.
     */
    public static Vector3d clipNormal(final Pose3dc pose, final double nx, final double ny, final double nz) {
        return pose.orientation().transformInverse(new Vector3d(nx, ny, nz)).mul(of(pose));
    }
}
