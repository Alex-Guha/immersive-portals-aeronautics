/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Declarations follow IPSable 0.5.0's IplRapierNatives by r2smith141 (Apache License 2.0). The functions are IPSable's
 * additions to Sable's crate, in ipa_rapier's natives/rapier/src/ipl_ext.rs; the JNI names are fixed there.
 */
package ipaeronautics.rapier;

import dev.ryanhcode.sable.api.physics.PhysicsPipeline;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;

/**
 * Image colliders and clip regions of the ipa_rapier natives (docs/lessons/physics-backend.md). Only callable
 * while {@link Rapier3D#isLoaded()}; every scene handle must be live (see {@link #sceneHandle}).
 * <p>
 * Clip region layout: N regions of 16 doubles, {@code [px py pz  nx ny nz  wx wy wz halfW  hx hy hz halfH  skin
 * maxDepth]}. A solver contact whose point lies inside a region (from {@code skin} before the plane through
 * {@code p} with normal {@code n} to {@code maxDepth} past it, within the rectangle) is neutralised.
 */
@ApiStatus.Internal
public final class RapierExt {
    public static final int CLIP_REGION_DOUBLES = 16;

    private RapierExt() {
    }

    /**
     * @return the native scene handle (the level's chart view) of a level using our pipeline, or 0 when it has none
     * (stock pipeline, not initialised, or already disposed)
     */
    public static long sceneHandle(final ServerLevel level) {
        final SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        if (system == null) {
            return 0L;
        }
        final PhysicsPipeline pipeline = system.getPipeline();
        if (!(pipeline instanceof final RapierPhysicsPipeline rapier)) {
            return 0L;
        }
        try {
            return rapier.getSceneHandle();
        } catch (final IllegalStateException e) {
            return 0L;
        }
    }

    /**
     * Sets (or clears, with an empty array) the clip regions of a body's own collider.
     */
    public static native void setClipRegions(long sceneHandle, int bodyId, double[] regions);

    /**
     * Creates an image collider of a body in the chart of {@code sceneHandle}: the body's own shape, attached to the
     * same rigid body, placed at the body's pose mapped by the isometry (translation {@code d}, rotation {@code q}).
     * Contacts on it act on the body through the engine's portal-frame mapping.
     *
     * @return the packed collider handle, or -1 if the body is unknown
     */
    public static native long createImageCollider(long sceneHandle, int bodyId,
                                                  double dx, double dy, double dz,
                                                  double qx, double qy, double qz, double qw);

    /**
     * Replaces an image collider's isometry (a portal that moved).
     */
    public static native void setImagePrefix(long sceneHandle, long imageHandle,
                                             double dx, double dy, double dz,
                                             double qx, double qy, double qz, double qw);

    /**
     * Removes an image collider. Harmless when the body (and with it the image) is already gone.
     */
    public static native void removeImageCollider(long sceneHandle, int bodyId, long imageHandle);

    /**
     * Sets (or clears, with an empty array) the clip regions of one image collider.
     */
    public static native void setImageClipRegions(long sceneHandle, int bodyId, long imageHandle, double[] regions);

    /** {@link #setImageAnchor} mode: the carrier carries the source side (the body is in the carrier's world). */
    public static final int ANCHOR_CARRIER_IS_SOURCE = 0;
    /** {@link #setImageAnchor} mode: the carrier carries the destination side (the image is at the carrier). */
    public static final int ANCHOR_CARRIER_IS_DESTINATION = 1;

    /**
     * Moving portals (natives/rapier/src/moving_portals.rs): the image follows the carrier body {@code carrierId} from
     * now on, its prefix derived from the carrier's pose before every step, with the portal's frame velocity. {@code k}
     * (translation, rotation quaternion) maps the carrier's body frame to the fixed side ({@link #ANCHOR_CARRIER_IS_SOURCE})
     * or the fixed side into the carrier's body frame ({@link #ANCHOR_CARRIER_IS_DESTINATION}). A negative
     * {@code carrierId} drops the anchoring.
     */
    public static native void setImageAnchor(long sceneHandle, long imageHandle, int carrierId, int mode,
                                             double tx, double ty, double tz, double qx, double qy, double qz, double qw);

    /**
     * Moving portals: sets the clip regions of body {@code bodyId} ({@code imageHandle} &lt; 0) or of one of its image
     * colliders, given in the body frame of the carrier {@code carrierId}; they follow the carrier from then on.
     */
    public static native void setAnchoredClipRegions(long sceneHandle, int bodyId, long imageHandle, int carrierId, double[] regions);
}
