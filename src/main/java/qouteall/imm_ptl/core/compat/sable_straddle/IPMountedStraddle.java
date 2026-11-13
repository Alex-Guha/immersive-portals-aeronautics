/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import ipaeronautics.rapier.RapierExt;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalAnchors;

/**
 * Straddling a portal mounted on a ship (docs/lessons/ship-mounted-portals.md). The native side derives the
 * image's portal isometry and frame velocity from the carrier's body every step (natives/rapier/src/moving_portals.rs);
 * this class gives it the transforms in the carrier's body frame (the body's translation and rotation are Sable's
 * logical pose; the native side is f32 and never sees plot coordinates), places clip regions in that frame, and adds
 * the carrier's motion to a hand-off's velocity.
 */
final class IPMountedStraddle {
    private IPMountedStraddle() {
    }

    /**
     * Anchors the session's image to its carriers. {@code k} is an invariant of the portal on the ships (while Sable keeps
     * their rotation points), so the current isometry and the current poses give it exactly.
     */
    static void sendAnchor(final IPStraddleSessions.Session session, final long destScene) {
        final IPShipPortalAnchors.Mount mount = session.mount;
        if (mount == null || destScene == 0L || session.imageHandle < 0L) {
            return;
        }
        // k is a similarity of the portal's scale: a carrier Create: Pocket Sized scaled has a metric native body frame,
        // so its pose's position and orientation are its body frame and only the portal scales
        final IPPortalSimilarity iso = session.map;
        final boolean scaled = iso.isScaled() || session.scaledImage;
        session.scaledImage = iso.isScaled();
        if (mount.source() != null && mount.destination() != null) {
            // k = D⁻¹ ∘ P ∘ S: the source carrier's body frame into the destination carrier's
            final Pose3dc source = mount.source().logicalPose();
            final Pose3dc dest = mount.destination().logicalPose();
            final Quaterniond inverse = new Quaterniond(dest.orientation()).conjugate();
            final Vector3d t = inverse.transform(toVector(iso.mapPoint(toVec3(new Vector3d(source.position())))
                    .subtract(toVec3(new Vector3d(dest.position())))));
            final Quaterniond q = new Quaterniond(inverse).mul(iso.rotation()).mul(source.orientation()).normalize();
            if (scaled) {
                RapierExt.setImageAnchorBetweenScaled(destScene, session.imageHandle, mount.source().getRuntimeId(),
                        mount.destination().getRuntimeId(), t.x, t.y, t.z, q.x, q.y, q.z, q.w, iso.scale());
            } else {
                RapierExt.setImageAnchorBetween(destScene, session.imageHandle, mount.source().getRuntimeId(),
                        mount.destination().getRuntimeId(), t.x, t.y, t.z, q.x, q.y, q.z, q.w);
            }
            return;
        }
        final boolean carrierIsSource = mount.source() != null;
        final ServerSubLevel carrier = carrierIsSource ? mount.source() : mount.destination();
        final Pose3dc pose = carrier.logicalPose();
        final Vec3 position = new Vec3(pose.position().x(), pose.position().y(), pose.position().z());
        final Quaterniondc bodyRotation = pose.orientation();
        final Vec3 t;
        final Quaterniond q;
        if (carrierIsSource) {
            // k = P ∘ B: the carrier's body frame to the fixed side
            t = iso.mapPoint(position);
            q = new Quaterniond(iso.rotation()).mul(bodyRotation);
        } else {
            // k = B⁻¹ ∘ P: the fixed side into the carrier's body frame
            final Quaterniond inverse = new Quaterniond(bodyRotation).conjugate();
            final Vector3d shifted = inverse.transform(toVector(iso.mapPoint(Vec3.ZERO).subtract(position)));
            t = new Vec3(shifted.x, shifted.y, shifted.z);
            q = inverse.mul(iso.rotation());
        }
        q.normalize();
        final int mode = carrierIsSource ? RapierExt.ANCHOR_CARRIER_IS_SOURCE : RapierExt.ANCHOR_CARRIER_IS_DESTINATION;
        if (scaled) {
            RapierExt.setImageAnchorScaled(destScene, session.imageHandle, carrier.getRuntimeId(), mode,
                    t.x, t.y, t.z, q.x, q.y, q.z, q.w, iso.scale());
        } else {
            RapierExt.setImageAnchor(destScene, session.imageHandle, carrier.getRuntimeId(), mode,
                    t.x, t.y, t.z, q.x, q.y, q.z, q.w);
        }
    }

    /** Re-expresses world clip regions ({@link RapierExt#CLIP_REGION_DOUBLES} doubles each) in the carrier's body frame. */
    static double[] toCarrierFrame(final ServerSubLevel carrier, final double[] regions) {
        final Pose3dc pose = carrier.logicalPose();
        final Quaterniond inverse = new Quaterniond(pose.orientation()).conjugate();
        final double[] out = regions.clone();
        final Vector3d v = new Vector3d();
        for (int i = 0; i + RapierExt.CLIP_REGION_DOUBLES <= out.length; i += RapierExt.CLIP_REGION_DOUBLES) {
            inverse.transform(v.set(out[i] - pose.position().x(), out[i + 1] - pose.position().y(), out[i + 2] - pose.position().z()));
            out[i] = v.x;
            out[i + 1] = v.y;
            out[i + 2] = v.z;
            rotate(inverse, out, i + 3, v);
            rotate(inverse, out, i + 6, v);
            rotate(inverse, out, i + 10, v);
        }
        return out;
    }

    private static void rotate(final Quaterniondc rotation, final double[] data, final int at, final Vector3d scratch) {
        rotation.transform(scratch.set(data[at], data[at + 1], data[at + 2]));
        data[at] = scratch.x;
        data[at + 1] = scratch.y;
        data[at + 2] = scratch.z;
    }

    /**
     * The velocity a ship handed off through a mounted portal arrives with: mapped through the portal relative to the
     * moving sides, {@code R (v − v_S(x)) + v_D(q)} ({@code v_S}, {@code v_D} the source and destination carriers'
     * velocities at a point, zero for a fixed side; {@code x} the ship's position, {@code q} its arrival position);
     * spins likewise.
     */
    static Vector3d[] handoffVelocity(final IPShipPortalAnchors.Mount mount, final IPPortalSimilarity map,
                                      final Vec3 position, final Vec3 arrival, final Vector3d linear, final Vector3d angular) {
        final Vector3d relativeLinear = new Vector3d(linear);
        final Vector3d relativeAngular = new Vector3d(angular);
        if (mount.source() != null) {
            final Vector3d[] carrier = velocityOf(mount.source());
            relativeLinear.sub(pointVelocity(carrier[0], carrier[1], new Vector3d(mount.source().logicalPose().position()), position));
            relativeAngular.sub(carrier[1]);
        }
        final Vector3d outLinear = toVector(map.mapDisplacement(toVec3(relativeLinear)));
        final Vector3d outAngular = toVector(map.mapDirection(toVec3(relativeAngular)));
        if (mount.destination() != null) {
            final Vector3d[] carrier = velocityOf(mount.destination());
            outLinear.add(pointVelocity(carrier[0], carrier[1], new Vector3d(mount.destination().logicalPose().position()), arrival));
            outAngular.add(carrier[1]);
        }
        return new Vector3d[]{outLinear, outAngular};
    }

    /** A carrier's linear and angular velocity (zero without its body). */
    private static Vector3d[] velocityOf(final ServerSubLevel carrier) {
        final RigidBodyHandle body = RigidBodyHandle.of(carrier);
        final Vector3d linear = new Vector3d();
        final Vector3d angular = new Vector3d();
        if (body != null) {
            body.getLinearVelocity(linear);
            body.getAngularVelocity(angular);
        }
        return new Vector3d[]{linear, angular};
    }

    private static Vector3d pointVelocity(final Vector3d linear, final Vector3d angular, final Vector3d centre, final Vec3 point) {
        final Vector3d arm = new Vector3d(point.x - centre.x, point.y - centre.y, point.z - centre.z);
        return new Vector3d(angular).cross(arm).add(linear);
    }

    private static Vector3d toVector(final Vec3 v) {
        return new Vector3d(v.x, v.y, v.z);
    }

    private static Vec3 toVec3(final Vector3d v) {
        return new Vec3(v.x, v.y, v.z);
    }
}
