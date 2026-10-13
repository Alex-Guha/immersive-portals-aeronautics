/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Follows IPSable 0.5.0's IplStraddleSessionSync by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

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
    }
}
