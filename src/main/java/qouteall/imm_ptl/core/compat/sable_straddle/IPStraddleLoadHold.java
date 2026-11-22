/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.TicketType;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * A ship that loads still through a portal is held where it was saved until every portal it was saved through has its
 * session back and the terrain around its far halves is in the destination's physics. Sable holds back a ship until the chunks around it are loaded, but not the destination of
 * its images: at a load nothing has loaded the far side yet, so a ship resting partly on its far half (its contacts
 * past the plane are the destination's) fell for the ticks the destination took to come in, and the far side's terrain
 * then appeared around a far half already sunk into it and threw the ship. A session whose portal entity loads a tick
 * later than the other's left that end without its far half for the tick.
 */
final class IPStraddleLoadHold {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ticks a ship is held at most; past them it goes on as it is. */
    private static final long MAX_HOLD = 200;
    /** Ticks the destination must have been ready before the ship goes: its sections enter physics in that time. */
    private static final int READY_TICKS = 2;
    /** Loads the destination around a held ship's far half; lapses by itself shortly after the hold ends. */
    private static final TicketType<UUID> TICKET = TicketType.create("ip_aeronautics_straddle_load", UUID::compareTo, 40);

    private static final class Hold {
        final Pose3d pose;
        final long since;
        int readyTicks;

        Hold(final Pose3d pose, final long since) {
            this.pose = pose;
            this.since = since;
        }
    }

    private static final Map<UUID, Hold> HELD = new HashMap<>();

    private IPStraddleLoadHold() {
    }

    /** {@code ship} got a session back at load: hold it until its destinations are ready. */
    static void hold(final ServerSubLevel ship, final long tick) {
        HELD.computeIfAbsent(ship.getUniqueId(), id -> new Hold(new Pose3d(ship.logicalPose()), tick));
    }

    static void tick(final MinecraftServer server, final long tick) {
        for (final Iterator<Map.Entry<UUID, Hold>> it = HELD.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Hold> entry = it.next();
            final Hold hold = entry.getValue();
            final ServerSubLevel ship = find(server, entry.getKey());
            if (ship == null) {
                it.remove();
                continue;
            }
            final boolean[] ready = {true};
            for (final UUID portal : IPStraddleSavedSessions.of(server).portalsOf(ship.getUniqueId())) {
                // a portal it was saved through whose session is not back yet (its entity loads a tick or more later)
                ready[0] &= IPStraddleSessions.has(new IPStraddleSessions.Key(ship.getUniqueId(), portal));
            }
            IPStraddleSessions.forEach(session -> {
                if (session.ship == ship && !destinationReady(session.dest, IPStraddle.mapBounds(session.map, ship.boundingBox()).inflate(2.0), ship.getUniqueId())) {
                    ready[0] = false;
                }
            });
            hold.readyTicks = ready[0] ? hold.readyTicks + 1 : 0;
            if (hold.readyTicks >= READY_TICKS) {
                LOGGER.info("[IPStraddle] ship {} goes on: the far side of its portals is loaded ({} ticks held)", ship.getUniqueId(), tick - hold.since);
                it.remove();
                continue;
            }
            if (tick - hold.since > MAX_HOLD) {
                LOGGER.warn("[IPStraddle] ship {} goes on after {} ticks held: the far side of its portals did not load", ship.getUniqueId(), tick - hold.since);
                it.remove();
                continue;
            }
            pin(ship, hold.pose);
        }
    }

    /**
     * @return whether every chunk around {@code region} of {@code level} is loaded enough for Sable's physics; asks
     * for the ones that are not
     */
    private static boolean destinationReady(final ServerLevel level, final AABB region, final UUID ship) {
        boolean ready = true;
        for (int x = Mth.floor(region.minX) >> 4; x <= Mth.floor(region.maxX) >> 4; x++) {
            for (int z = Mth.floor(region.minZ) >> 4; z <= Mth.floor(region.maxZ) >> 4; z++) {
                if (!PhysicsChunkTicketManager.isChunkLoadedEnough(level, x, z)) {
                    ready = false;
                    level.getChunkSource().addRegionTicket(TICKET, new ChunkPos(x, z), 1, ship);
                }
            }
        }
        return ready;
    }

    private static void pin(final ServerSubLevel ship, final Pose3d pose) {
        try {
            final RigidBodyHandle body = RigidBodyHandle.of(ship);
            if (body == null) {
                return;
            }
            body.teleport(pose.position(), pose.orientation());
            final Vector3d linear = body.getLinearVelocity(new Vector3d());
            final Vector3d angular = body.getAngularVelocity(new Vector3d());
            body.addLinearAndAngularVelocity(linear.negate(new Vector3d()), angular.negate(new Vector3d()));
        } catch (final Throwable e) {
            LOGGER.warn("[IPStraddle] could not hold ship {} while the far side loads: {}", ship.getUniqueId(), e.toString());
        }
    }

    static void reset() {
        HELD.clear();
    }

    private static ServerSubLevel find(final MinecraftServer server, final UUID id) {
        for (final ServerLevel level : server.getAllLevels()) {
            final SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && container.getSubLevel(id) instanceof final ServerSubLevel ship && !ship.isRemoved()) {
                return ship;
            }
        }
        return null;
    }
}
