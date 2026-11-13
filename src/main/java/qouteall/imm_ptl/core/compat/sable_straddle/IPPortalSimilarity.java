/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * The map of a portal from its own world into its destination, scale included: {@code p -> dest + k R (p - origin)},
 * as IP's {@code Portal.transformPoint} (k = {@code Portal.getScale()}, 1 for an unscaled portal).
 * <p>
 * {@link IPPortalIsometry} is its rigid part and stays a record of its own (Create Simurail builds and reads it, and
 * reads it from {@code IPStraddleSessions.Session.isometry}). Vectors come in two kinds here, and every caller says
 * which: a {@linkplain #mapDirection direction} (normals, axes, look vectors, angular velocities) turns by R only; a
 * {@linkplain #mapDisplacement displacement} (a difference of two points, a linear velocity) is also scaled by k.
 * At k = 1 every method gives exactly what the isometry's does.
 */
public record IPPortalSimilarity(IPPortalIsometry rigid, double scale) {
    public static final IPPortalSimilarity IDENTITY = new IPPortalSimilarity(IPPortalIsometry.IDENTITY, 1.0);

    public static IPPortalSimilarity of(final Portal portal) {
        return new IPPortalSimilarity(IPPortalIsometry.of(portal), portal.getScale());
    }

    /**
     * @return the map of an unscaled portal, {@code isometry} itself
     */
    public static IPPortalSimilarity rigid(final IPPortalIsometry isometry) {
        return new IPPortalSimilarity(isometry, 1.0);
    }

    public Vec3 origin() {
        return this.rigid.origin();
    }

    public Vec3 dest() {
        return this.rigid.dest();
    }

    public Quaterniondc rotation() {
        return this.rigid.rotation();
    }

    public boolean isScaled() {
        return this.scale != 1.0;
    }

    public Vec3 mapPoint(final Vec3 p) {
        return this.rigid.dest().add(this.mapDisplacement(p.subtract(this.rigid.origin())));
    }

    /**
     * @return {@code k R v}: a difference of points, a linear velocity
     */
    public Vec3 mapDisplacement(final Vec3 v) {
        final Vec3 turned = this.rigid.mapVec(v);
        return this.scale == 1.0 ? turned : turned.scale(this.scale);
    }

    /**
     * @return {@code R v}: a normal, an axis, a look vector, an angular velocity (its length is kept)
     */
    public Vec3 mapDirection(final Vec3 v) {
        return this.rigid.mapVec(v);
    }

    /**
     * @return a length of this portal's own world as seen in the destination
     */
    public double mapLength(final double length) {
        return length * this.scale;
    }

    /**
     * @return the point of this portal's own world that maps to {@code q}
     */
    public Vec3 unmapPoint(final Vec3 q) {
        if (this.scale == 1.0) {
            return this.rigid.unmapPoint(q);
        }
        final Vec3 local = this.rigid.inverse().mapVec(q.subtract(this.rigid.dest())).scale(1.0 / this.scale);
        return this.rigid.origin().add(local);
    }

    /**
     * @return {@code R^-1 v / k}, the displacement of this portal's own world that maps to {@code v}
     */
    public Vec3 unmapDisplacement(final Vec3 v) {
        final Vec3 turned = this.rigid.inverse().mapVec(v);
        return this.scale == 1.0 ? turned : turned.scale(1.0 / this.scale);
    }

    /**
     * @return {@code R^-1 v}
     */
    public Vec3 unmapDirection(final Vec3 v) {
        return this.rigid.inverse().mapVec(v);
    }

    public Quaterniond mapOrientation(final Quaterniondc orientation) {
        return this.rigid.mapOrientation(orientation);
    }

    /**
     * @return {@code pose} as seen in the destination: position mapped, orientation turned, scale times k (a Sable pose
     *         maps plot points by {@code pos + R s (p - rp)}, so the mapped pose maps them to the mapped world points)
     */
    public Pose3d mapPose(final Pose3dc pose) {
        final Pose3d out = this.rigid.mapPose(pose);
        if (this.scale != 1.0) {
            final Vec3 p = this.mapPoint(new Vec3(pose.position().x(), pose.position().y(), pose.position().z()));
            out.position().set(p.x, p.y, p.z);
            out.scale().mul(this.scale);
        }
        return out;
    }

    /**
     * @return the pose of this portal's own world that {@link #mapPose} maps to {@code pose}
     */
    public Pose3d unmapPose(final Pose3dc pose) {
        final Pose3d out = this.rigid.unmapPose(pose);
        if (this.scale != 1.0) {
            final Vec3 p = this.unmapPoint(new Vec3(pose.position().x(), pose.position().y(), pose.position().z()));
            out.position().set(p.x, p.y, p.z);
            out.scale().mul(1.0 / this.scale);
        }
        return out;
    }

    /**
     * @return the translation t of {@code p -> k R p + t}, {@code dest - k R origin}
     */
    public Vec3 translation() {
        return this.mapPoint(Vec3.ZERO);
    }

    /**
     * @return the map back, {@code q -> origin + R^-1 (q - dest) / k}
     */
    public IPPortalSimilarity inverse() {
        return new IPPortalSimilarity(this.rigid.inverse(), 1.0 / this.scale);
    }

    /**
     * @return the map {@code p -> next(this(p))}
     */
    public IPPortalSimilarity then(final IPPortalSimilarity next) {
        if (this.scale == 1.0 && next.scale == 1.0) {
            return rigid(this.rigid.then(next.rigid));
        }
        final Quaterniond rotation = new Quaterniond(next.rotation()).mul(this.rotation());
        return new IPPortalSimilarity(new IPPortalIsometry(Vec3.ZERO, next.mapPoint(this.translation()), rotation),
                this.scale * next.scale);
    }

    /**
     * @return whether this and {@code other} map every point and direction alike (within float noise)
     */
    public boolean sameAs(final IPPortalSimilarity other) {
        return Math.abs(this.scale - other.scale) <= 1.0e-9 * Math.max(1.0, this.scale)
                && this.translation().distanceToSqr(other.translation()) <= 1.0e-10
                && Math.abs(this.rotation().dot(other.rotation())) >= 1.0 - 1.0e-10;
    }
}
