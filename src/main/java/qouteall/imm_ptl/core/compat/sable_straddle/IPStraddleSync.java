/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Follows IPSable 0.5.0's IplStraddleSessionSync by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalAnchors;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.joml.Quaterniondc;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Tells clients about straddle sessions (docs/lessons/straddle-rendering.md), so they can cut the ship at the portal
 * and draw its far half in the destination dimension. Sessions are few, so every player is told about every session,
 * whatever dimension they are in (a player can see both sides through portals): per player, each tick, the sessions
 * they were not told about yet (or whose portal moved since) are sent, and the ones that ended are retracted, over
 * IP's remote procedure calls ({@code IPStraddleClientSessions.RemoteCallables}).
 */
final class IPStraddleSync {
    private static final String CLIENT = "qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleClientSessions.RemoteCallables.";
    /** Per player: the sessions they were told about, with the version they were told. */
    private static final Map<UUID, Map<IPStraddleSessions.Key, Integer>> KNOWN = new HashMap<>();
    /** Sessions closed by a hand-off since the last sync (the client keeps drawing them until the ship arrives). */
    private static final Set<IPStraddleSessions.Key> HANDED_OFF = new HashSet<>();

    private IPStraddleSync() {
    }

    static void reset() {
        KNOWN.clear();
        HANDED_OFF.clear();
    }

    static void markHandedOff(final IPStraddleSessions.Key key) {
        HANDED_OFF.add(key);
    }

    static void tick(final MinecraftServer server) {
        final Map<IPStraddleSessions.Key, IPStraddleSessions.Session> current = new HashMap<>();
        IPStraddleSessions.forEach(session -> current.put(session.key, session));
        final Set<UUID> online = new HashSet<>();

        for (final ServerPlayer player : server.getPlayerList().getPlayers()) {
            online.add(player.getUUID());
            final Map<IPStraddleSessions.Key, Integer> known = KNOWN.computeIfAbsent(player.getUUID(), id -> new HashMap<>());
            for (final IPStraddleSessions.Session session : current.values()) {
                final Integer told = known.get(session.key);
                if (told == null || told != session.version) {
                    sendBegin(player, session);
                    known.put(session.key, session.version);
                }
            }
            known.entrySet().removeIf(entry -> {
                if (current.containsKey(entry.getKey())) {
                    return false;
                }
                McRemoteProcedureCall.tellClientToInvoke(player, CLIENT + "end",
                        entry.getKey().ship(), entry.getKey().portal(), HANDED_OFF.contains(entry.getKey()));
                return true;
            });
        }

        KNOWN.keySet().retainAll(online);
        HANDED_OFF.clear();
    }

    private static void sendBegin(final ServerPlayer player, final IPStraddleSessions.Session session) {
        final Portal portal = session.portal;
        final Quaterniondc q = session.isometry.rotation();
        McRemoteProcedureCall.tellClientToInvoke(player, CLIENT + "begin",
                session.key.ship(), session.source.dimension(), session.key.portal(), session.dest.dimension(),
                session.origin, session.normal, portal.getAxisW(), portal.getAxisH(),
                portal.getWidth() * 0.5, portal.getHeight() * 0.5,
                session.isometry.dest(), new DQuaternion(q.x(), q.y(), q.z(), q.w()));
        if (session.mount != null) {
            sendMount(player, session);
        }
    }

    /**
     * A session at a portal mounted on a ship: the client derives its geometry every frame from the carriers as they
     * are drawn (docs/plans/ship-mounted-portals.md, phase 4), from invariants in their plots. The portal maps
     * {@code D ∘ K ∘ S⁻¹}, with {@code S} the source carrier's pose and {@code D} the destination carrier's (either the
     * identity for a side fixed in the world): the plane in the source frame ({@code plot*}), and {@code K} as the
     * plane origin's image in the destination frame ({@code kDest}) and a rotation.
     */
    private static void sendMount(final ServerPlayer player, final IPStraddleSessions.Session session) {
        final IPShipPortalAnchors.Mount mount = session.mount;
        final Pose3dc source = mount.source() == null ? null : mount.source().logicalPose();
        final Pose3dc dest = mount.destination() == null ? null : mount.destination().logicalPose();
        final Vec3 plotOrigin = source == null ? session.origin : source.transformPositionInverse(session.origin);
        final Vec3 plotNormal = source == null ? session.normal : source.transformNormalInverse(session.normal);
        final Vec3 plotAxisW = source == null ? session.portal.getAxisW() : source.transformNormalInverse(session.portal.getAxisW());
        final Vec3 plotAxisH = source == null ? session.portal.getAxisH() : source.transformNormalInverse(session.portal.getAxisH());
        final Vec3 far = session.isometry.mapPoint(session.origin);
        final Vec3 kDest = dest == null ? far : dest.transformPositionInverse(far);
        final Quaterniond kRotation = dest == null ? new Quaterniond() : new Quaterniond(dest.orientation()).conjugate();
        kRotation.mul(session.isometry.rotation());
        if (source != null) {
            kRotation.mul(source.orientation());
        }
        kRotation.normalize();
        final ServerSubLevel sourceShip = mount.source() != null ? mount.source() : session.ship;
        final ServerSubLevel destShip = mount.destination() != null ? mount.destination() : session.ship;
        McRemoteProcedureCall.tellClientToInvoke(player, CLIENT + "mount",
                session.key.ship(), session.key.portal(),
                mount.source() != null, sourceShip.getUniqueId(), sourceShip.getLevel().dimension(),
                mount.destination() != null, destShip.getUniqueId(), destShip.getLevel().dimension(),
                plotOrigin, plotNormal, plotAxisW, plotAxisH,
                kDest, new DQuaternion(kRotation.x, kRotation.y, kRotation.z, kRotation.w));
    }
}
