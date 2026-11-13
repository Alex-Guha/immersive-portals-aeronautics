/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Declarations follow IPSable 0.5.0's IplRapierNatives by r2smith141 (Apache License 2.0). The functions are IPSable's
 * additions to Sable's crate and ours, in ipa_rapier's natives/rapier/src (ipl_ext.rs, moving_portals.rs, lib.rs); the
 * JNI names are fixed there.
 */
package ipaeronautics.rapier;

import dev.ryanhcode.sable.physics.impl.rapier.Rapier3D;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.ApiStatus;
import qouteall.imm_ptl.core.compat.sable_physics.IPPhysicsBackend;

/**
 * Image colliders, clip regions, portal rims and the chart-only step of the ipa_rapier natives
 * (docs/lessons/physics-backend.md). Only callable while {@link IPPhysicsBackend#isOwnBackendActive()}: Sable's own
 * library has none of these. Every scene handle must be live (see {@link #sceneHandle}).
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
     * @return the native scene handle (the level's chart view) of a level, or 0 when it has none (our library not
     * loaded, no pipeline, not initialised, or already disposed)
     */
    public static long sceneHandle(final ServerLevel level) {
        if (!IPPhysicsBackend.isOwnBackendActive()) {
            return 0L;
        }
        final SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(level);
        if (system == null || !(system.getPipeline() instanceof RapierPhysicsPipeline)) {
            return 0L;
        }
        try {
            return Rapier3D.getSceneHandle(level);
        } catch (final IllegalStateException e) {
            return 0L;
        }
    }

    /**
     * The per-chart upkeep of Sable's {@code Rapier3D.step} (ropes, joints) without stepping the shared world: the
     * fused step steps the world with one level's pipeline per substep and calls this for the others'
     * ({@code MixinSableRapierPipeline_OwnBackend}).
     */
    public static native void stepChartOnly(long sceneHandle);

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
     * {@link #createImageCollider} through a portal of scale {@code k}, whose map is {@code x -> k R x + t}
     * ({@code (dx, dy, dz)} = t): the image is k times its body, collides at that size and acts on the body through
     * the similarity. {@code k = 1} is {@code createImageCollider}.
     */
    public static native long createScaledImageCollider(long sceneHandle, int bodyId,
                                                        double dx, double dy, double dz,
                                                        double qx, double qy, double qz, double qw, double k);

    /**
     * {@link #setImagePrefix} with the portal's scale {@code k}. Not for a moving portal's image.
     */
    public static native void setImagePrefixScaled(long sceneHandle, long imageHandle,
                                                   double dx, double dy, double dz,
                                                   double qx, double qy, double qz, double qw, double k);

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
     * Moving portals with both sides on carriers (docs/lessons/ship-mounted-portals.md): the image follows the source
     * carrier {@code sourceId} and the destination carrier {@code destId} (possibly one body); {@code k} maps the source
     * carrier's body frame into the destination carrier's. A negative id drops the anchoring.
     */
    public static native void setImageAnchorBetween(long sceneHandle, long imageHandle, int sourceId, int destId,
                                                    double tx, double ty, double tz, double qx, double qy, double qz, double qw);

    /**
     * {@link #setImageAnchor} through a portal of scale {@code s} ({@code k} a similarity of scale s; the carriers'
     * native body frames are metric). The image's shape takes the scale; s = 1 puts it back.
     */
    public static native void setImageAnchorScaled(long sceneHandle, long imageHandle, int carrierId, int mode,
                                                   double tx, double ty, double tz, double qx, double qy, double qz, double qw,
                                                   double s);

    /** {@link #setImageAnchorBetween} through a portal of scale {@code s}, as {@link #setImageAnchorScaled}. */
    public static native void setImageAnchorBetweenScaled(long sceneHandle, long imageHandle, int sourceId, int destId,
                                                          double tx, double ty, double tz, double qx, double qy, double qz,
                                                          double qw, double s);

    /**
     * Moving portals: sets the clip regions of body {@code bodyId} ({@code imageHandle} &lt; 0) or of one of its image
     * colliders, given in the body frame of the carrier {@code carrierId}; they follow the carrier from then on.
     */
    public static native void setAnchoredClipRegions(long sceneHandle, int bodyId, long imageHandle, int carrierId, double[] regions);

    /**
     * Creates a portal rim in the chart of {@code sceneHandle}: a kinematic body of four cuboids framing a hole of
     * {@code holeWidth} x {@code holeHeight}, each bar {@code width} wide and {@code 2 * halfThickness} thick, in the
     * body's local XZ plane (X along the hole's width, Z along its height, Y its normal), centred on the local origin.
     * It collides like terrain of that chart. Place it with {@link #setPortalRimTransform} before the next step.
     *
     * @return the rim's id (negative), or -1 on bad arguments
     */
    public static native int createPortalRim(long sceneHandle, double holeWidth, double holeHeight, double width, double halfThickness);

    /**
     * Places a rim: the first call puts it there, later ones move it kinematically (with velocity) during the next step.
     */
    public static native void setPortalRimTransform(long sceneHandle, int rimId,
                                                    double x, double y, double z, double qx, double qy, double qz, double qw);

    /**
     * Anchors a rim to the carrier body {@code carrierId} and puts it there now: before every step it is sent where the
     * carrier will be after the step, so it moves with the carrier's velocity. {@code k} (translation, rotation
     * quaternion) is the rim's pose in the carrier's body frame. A negative {@code carrierId} drops the anchoring.
     */
    public static native void setPortalRimAnchor(long sceneHandle, int rimId, int carrierId,
                                                 double tx, double ty, double tz, double qx, double qy, double qz, double qw);

    /**
     * Removes a rim. The scene must be the live one it was created in.
     */
    public static native void removePortalRim(long sceneHandle, int rimId);

    /**
     * Excludes ({@code excluded}) or readmits contacts between two bodies, or between a rim ({@code idA}, negative) and
     * a body.
     */
    public static native void setBodyPairExclusion(long sceneHandle, int idA, int idB, boolean excluded);
}
