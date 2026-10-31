/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

import java.util.UUID;

/**
 * The clip plane for a ship's sections in a portal pass through a portal whose far side the ship carries. IP clips
 * terrain 1 cm short of the far portal, on the camera's side ({@code FrontClipping.ADJUSTMENT}); anything ending
 * exactly in the plane showed that last centimetre through the portal's back, as a thin outline in the far world.
 * World terrain at mid-block planes drops it (1.22.4), ships kept it: with both ends of a portal on ships, the far
 * ship's half of a pipe through the portal (Create's model up to the plane) showed as a sliver in the portal view
 * from either side. When the plane lies mid-block in the carrier's own coordinates (a nether frame; no block face can
 * lie in it), the carrier is clipped exactly at it, the plane snapped to the block layer's middle, so the pose of the
 * portal (worked out each tick) and the ship's render pose cannot disagree by a hair.
 */
public final class IPShipPortalClip {
    private static final double AXIS_COS = 1 - 1.0e-4;
    /** How far the far portal may lie from the middle of the carrier's block layer and still count as through it. */
    private static final double MID_SLACK = 0.02;

    private IPShipPortalClip() {
    }

    /**
     * The clip equation for {@code subLevel}'s sections, in the space its normal is rotated into (the ship's
     * orientation undone, positions relative to the camera), or null to keep IP's.
     */
    public static double @Nullable [] equationFor(final ClientSubLevel subLevel) {
        if (!PortalRendering.isRendering()) {
            return null;
        }
        final Portal portal = PortalRendering.getRenderingPortal();
        final UUID carrier = IPShipPortals.farSideCarrier(portal);
        if (carrier == null || !carrier.equals(subLevel.getUniqueId())) {
            return null;
        }
        final Plane plane = PortalRendering.getActiveClippingPlane();
        if (plane == null) {
            return null;
        }
        final Pose3dc pose = subLevel.renderPose();
        final Vec3 localPos = pose.transformPositionInverse(plane.pos());
        final Vec3 localNormal = pose.transformNormalInverse(plane.normal()).normalize();
        final Vec3 snappedNormal;
        final Vec3 snappedPos;
        if (Math.abs(localNormal.x) > AXIS_COS) {
            final double mid = Math.floor(localPos.x) + 0.5;
            if (Math.abs(localPos.x - mid) > MID_SLACK) {
                return null;
            }
            snappedNormal = new Vec3(Math.signum(localNormal.x), 0, 0);
            snappedPos = new Vec3(mid, localPos.y, localPos.z);
        } else if (Math.abs(localNormal.y) > AXIS_COS) {
            final double mid = Math.floor(localPos.y) + 0.5;
            if (Math.abs(localPos.y - mid) > MID_SLACK) {
                return null;
            }
            snappedNormal = new Vec3(0, Math.signum(localNormal.y), 0);
            snappedPos = new Vec3(localPos.x, mid, localPos.z);
        } else if (Math.abs(localNormal.z) > AXIS_COS) {
            final double mid = Math.floor(localPos.z) + 0.5;
            if (Math.abs(localPos.z - mid) > MID_SLACK) {
                return null;
            }
            snappedNormal = new Vec3(0, 0, Math.signum(localNormal.z));
            snappedPos = new Vec3(localPos.x, localPos.y, mid);
        } else {
            return null;
        }
        final Vec3 worldNormal = pose.transformNormal(snappedNormal).normalize();
        final Vec3 worldPos = pose.transformPosition(snappedPos);
        // normal . p + c > 0 on the kept side, p relative to the camera (as FrontClipping's)
        final double c = -worldNormal.dot(worldPos.subtract(CHelper.getCurrentCameraPos()));
        return new double[]{snappedNormal.x, snappedNormal.y, snappedNormal.z, c};
    }
}
