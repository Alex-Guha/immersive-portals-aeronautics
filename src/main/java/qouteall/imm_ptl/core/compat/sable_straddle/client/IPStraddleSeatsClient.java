/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;
import qouteall.imm_ptl.core.compat.sable_straddle.IPSeatProxiesClientAccess;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalUtils;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Client side of seat proxies ({@code IPStraddleSeats}): the local rider is placed where its seat is drawn (the ship's
 * client pose mapped through the portal, as Sable places riders of a seat on a ship), and while the server, which runs
 * a few ticks ahead of what the client draws, has already moved the rider one way, the view is taken through the
 * portal the other way ({@link #viewCrossing}).
 */
public final class IPStraddleSeatsClient {
    /** How far in front of the plane the cross-portal view starts its ray to the head. */
    private static final double ORIGIN_OFFSET = 0.05;

    private record Proxy(UUID ship, ResourceKey<Level> shipDim, Vec3 riderPlot, IPPortalIsometry isometry) {
    }

    private static final Map<UUID, Proxy> PROXIES = new HashMap<>();

    private IPStraddleSeatsClient() {
    }

    /**
     * @return where the proxy {@code entity} places its rider, or null when it is no proxy known here or its ship is not
     * on this client
     */
    @Nullable
    public static Vec3 riderPosition(final Entity entity) {
        final Proxy proxy = PROXIES.get(entity.getUUID());
        final SubLevel ship = proxy == null ? null : shipOf(proxy);
        if (ship == null) {
            return null;
        }
        final Vector3d feet = ship.logicalPose().transformPosition(new Vector3d(proxy.riderPlot.x, proxy.riderPlot.y, proxy.riderPlot.z));
        return proxy.isometry.mapPoint(new Vec3(feet.x, feet.y, feet.z));
    }

    /**
     * The local player rides a proxy and its head, as drawn, is still on this side of the destination's portal plane:
     * the server moved it through already. The view goes back through the portal into the ship's dimension.
     */
    @Nullable
    public static IPStraddleSeatView.Crossing viewCrossing(final float partialTick) {
        final LocalPlayer player = Minecraft.getInstance().player;
        final Entity vehicle = player == null ? null : player.getVehicle();
        final Proxy proxy = vehicle == null ? null : PROXIES.get(vehicle.getUUID());
        final SubLevel ship = proxy == null ? null : shipOf(proxy);
        if (ship == null) {
            return null;
        }
        final Vec3 eye = player.getEyePosition(partialTick);
        for (final IPStraddleClientSessions.Session session : IPStraddleClientSessions.renderSessionsOf(ship)) {
            if (!session.destDim().equals(player.level().dimension())) {
                continue;
            }
            final Vec3 normal = session.destNormal();
            final Vec3 offset = eye.subtract(session.destOrigin());
            final double depth = offset.dot(normal);
            if (depth >= 0.0 || Math.abs(offset.dot(session.isometry().mapVec(session.axisW()))) > session.halfW()
                || Math.abs(offset.dot(session.isometry().mapVec(session.axisH()))) > session.halfH()) {
                continue;
            }
            final Vec3 origin = eye.add(normal.scale(-depth + ORIGIN_OFFSET));
            final Portal portal = PortalUtils.raytracePortals(player.level(), origin, eye, true,
                candidate -> candidate.getDestDim().equals(proxy.shipDim)).map(hit -> hit.getFirst()).orElse(null);
            if (portal != null) {
                return new IPStraddleSeatView.Crossing(portal, origin);
            }
        }
        return null;
    }

    @Nullable
    private static SubLevel shipOf(final Proxy proxy) {
        final ClientLevel level = ClientWorldLoader.getOptionalWorld(proxy.shipDim);
        final SubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
        final SubLevel ship = container == null ? null : container.getSubLevel(proxy.ship);
        return ship == null || ship.isRemoved() ? null : ship;
    }

    public static final class RemoteCallables {
        private RemoteCallables() {
        }

        public static void begin(final UUID entity, final UUID ship, final ResourceKey<Level> shipDim, final Vec3 riderPlot,
                                 final Vec3 origin, final Vec3 dest, final DQuaternion rotation) {
            PROXIES.put(entity, new Proxy(ship, shipDim, riderPlot,
                new IPPortalIsometry(origin, dest, new Quaterniond(rotation.x, rotation.y, rotation.z, rotation.w).normalize())));
            IPSeatProxiesClientAccess.add(entity);
        }

        public static void end(final UUID entity) {
            PROXIES.remove(entity);
            IPSeatProxiesClientAccess.remove(entity);
        }
    }
}
