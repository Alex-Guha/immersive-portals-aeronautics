/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Follows IPSable 0.5.0's IplStraddlePoseMap.StraddleMapping by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

/**
 * The rigid map of an unscaled portal from its own world into its destination: {@code p -> dest + R (p - origin)}.
 * As a pose prefix it is {@code (R, dest - R origin)}, which is what the native image collider takes.
 */
public record IPPortalIsometry(Vec3 origin, Vec3 dest, Quaterniondc rotation) {
    public static final IPPortalIsometry IDENTITY = new IPPortalIsometry(Vec3.ZERO, Vec3.ZERO, new Quaterniond());

    public static IPPortalIsometry of(final Portal portal) {
        final DQuaternion q = portal.getRotationD();
        return new IPPortalIsometry(portal.getOriginPos(), portal.getDestPos(), new Quaterniond(q.x, q.y, q.z, q.w).normalize());
    }

    public Vec3 mapPoint(final Vec3 p) {
        return this.dest.add(this.mapVec(p.subtract(this.origin)));
    }

    public Vec3 mapVec(final Vec3 v) {
        final Vector3d out = this.rotation.transform(new Vector3d(v.x, v.y, v.z));
        return new Vec3(out.x, out.y, out.z);
    }

    /**
     * @return the point of this portal's own world that maps to {@code q}
     */
    public Vec3 unmapPoint(final Vec3 q) {
        final Vector3d out = this.rotation.transformInverse(new Vector3d(q.x - this.dest.x, q.y - this.dest.y, q.z - this.dest.z));
        return new Vec3(this.origin.x + out.x, this.origin.y + out.y, this.origin.z + out.z);
    }

    public Quaterniond mapOrientation(final Quaterniondc orientation) {
        return new Quaterniond(this.rotation).mul(orientation);
    }

    public Pose3d mapPose(final Pose3dc pose) {
        final Pose3d out = new Pose3d(pose);
        final Vec3 p = this.mapPoint(new Vec3(pose.position().x(), pose.position().y(), pose.position().z()));
        out.position().set(p.x, p.y, p.z);
        out.orientation().set(this.mapOrientation(pose.orientation()));
        return out;
    }

    /**
     * @return the pose of this portal's own world that {@link #mapPose} maps to {@code pose}
     */
    public Pose3d unmapPose(final Pose3dc pose) {
        final Pose3d out = new Pose3d(pose);
        final Vec3 p = this.unmapPoint(new Vec3(pose.position().x(), pose.position().y(), pose.position().z()));
        out.position().set(p.x, p.y, p.z);
        out.orientation().set(new Quaterniond(this.rotation).conjugate().mul(pose.orientation()));
        return out;
    }

    /**
     * @return the translation of the pose prefix, {@code dest - R origin}
     */
    public Vec3 translation() {
        return this.mapPoint(Vec3.ZERO);
    }

    /**
     * @return the map back, {@code q -> origin + R^-1 (q - dest)}
     */
    public IPPortalIsometry inverse() {
        return new IPPortalIsometry(this.dest, this.origin, new Quaterniond(this.rotation).conjugate());
    }

    /**
     * @return the map {@code p -> next(this(p))}
     */
    public IPPortalIsometry then(final IPPortalIsometry next) {
        return new IPPortalIsometry(Vec3.ZERO, next.mapPoint(this.translation()), new Quaterniond(next.rotation).mul(this.rotation));
    }

    /**
     * @return whether this and {@code other} map every point and direction alike (within float noise)
     */
    public boolean sameAs(final IPPortalIsometry other) {
        return this.translation().distanceToSqr(other.translation()) <= 1.0e-10
                && Math.abs(this.rotation.dot(other.rotation)) >= 1.0 - 1.0e-10;
    }
}
