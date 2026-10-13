/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalUtils;

/**
 * The view of a player seated on a straddling ship while its head, as drawn, is on the other side of the portal plane
 * from the player. The server moves a seated rider through the portal with its seat ({@code IPStraddleSeats}) by its
 * own ship pose, which runs a few ticks ahead of the pose the client draws; in between, the rider's head is behind the
 * portal in the world it is in. For those ticks the view (IP's cross-portal view, from a point just in front of the
 * plane) and the crosshair target (IP's through-portal targeting) go through the portal: on a seat of the ship, through
 * the portal the ship straddles; on a seat proxy, back through the destination's portal
 * ({@link IPStraddleSeatsClient#viewCrossing}).
 */
public final class IPStraddleSeatView {
    /** How far in front of the plane the cross-portal view starts its ray to the head. */
    private static final double ORIGIN_OFFSET = 0.05;

    /**
     * @param portal the portal the ship straddles, the head being past it
     * @param origin a point just in front of the portal plane, level with the head
     */
    public record Crossing(Portal portal, Vec3 origin) {
    }

    private IPStraddleSeatView() {
    }

    @Nullable
    public static Crossing crossing(final float partialTick) {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (player == null || IPStraddleClientSessions.isEmpty() || !IPSableCompatHelper.isRidingOnShip(player)) {
            return null;
        }
        final Crossing back = IPStraddleSeatsClient.viewCrossing(partialTick);
        if (back != null) {
            return back;
        }
        final Entity vehicle = player.getVehicle();
        final SubLevel ship = vehicle == null ? null : Sable.HELPER.getContainingClient(vehicle);
        if (ship == null) {
            return null;
        }
        final Vec3 eye = player.getEyePosition(partialTick);
        for (final IPStraddleClientSessions.Session session : IPStraddleClientSessions.renderSessionsOf(ship)) {
            if (!session.shipDim().equals(player.level().dimension())) {
                continue;
            }
            final Vec3 offset = eye.subtract(session.origin());
            final double depth = offset.dot(session.normal());
            if (depth <= 0.0 || Math.abs(offset.dot(session.axisW())) > session.halfW()
                || Math.abs(offset.dot(session.axisH())) > session.halfH()) {
                continue;
            }
            final Vec3 origin = eye.subtract(session.normal().scale(depth + ORIGIN_OFFSET));
            final Portal portal = PortalUtils.raytracePortals(player.level(), origin, eye, true,
                candidate -> candidate.getUUID().equals(session.portal())).map(hit -> hit.getFirst()).orElse(null);
            if (portal != null) {
                return new Crossing(portal, origin);
            }
        }
        return null;
    }
}
