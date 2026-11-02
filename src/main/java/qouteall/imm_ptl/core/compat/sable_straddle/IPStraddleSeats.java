/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Display;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableBridge;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipCreateCompat;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipRiderCarry;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Players seated on a straddling ship go through the portal with their seat (docs/lessons/straddle-entities.md).
 * <p>
 * Riding needs the vehicle in the rider's level, and a seat is a plot-space entity of the ship's dimension until the
 * hand-off, so a seated player whose head passes the portal plane would stay behind the portal in the ship's own world,
 * where that part of the ship is not drawn. Instead the player is taken off the seat, moved through the portal and
 * seated on a proxy in the destination: an invisible block display that places its rider where the seat is, mapped
 * through the portal (from the ship's pose on each side, {@link #riderPosition}). The player sees, hears and acts in the
 * destination and can get off onto the far half. The real seat is taken again when the ship is handed off (in the
 * destination) or when the seat comes back through the portal (in the ship's dimension).
 */
public final class IPStraddleSeats {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CLIENT = "qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleSeatsClient.RemoteCallables.";
    /** How far past the plane the head must be before the rider goes through (blocks). */
    private static final double CROSS_MARGIN = 0.02;
    /** How far back before the plane the head must be before the rider comes back (blocks). */
    private static final double RETURN_MARGIN = 0.1;

    private static final class Proxy {
        final UUID player;
        final UUID ship;
        final ResourceKey<Level> shipDim;
        final BlockPos seatPos;
        /** The rider's feet in the ship's plot. */
        final Vec3 riderPlot;
        final IPPortalIsometry isometry;
        final Vec3 origin;
        final Vec3 normal;
        final Display.BlockDisplay entity;

        Proxy(final UUID player, final UUID ship, final ResourceKey<Level> shipDim, final BlockPos seatPos, final Vec3 riderPlot,
              final IPPortalIsometry isometry, final Vec3 origin, final Vec3 normal, final Display.BlockDisplay entity) {
            this.player = player;
            this.ship = ship;
            this.shipDim = shipDim;
            this.seatPos = seatPos;
            this.riderPlot = riderPlot;
            this.isometry = isometry;
            this.origin = origin;
            this.normal = normal;
            this.entity = entity;
        }
    }

    private static final Map<UUID, Proxy> BY_PLAYER = new HashMap<>();
    private static final Map<UUID, Proxy> BY_ENTITY = new HashMap<>();
    private static boolean listening;

    private IPStraddleSeats() {
    }

    static void clearState() {
        BY_PLAYER.clear();
        BY_ENTITY.clear();
        IPSeatProxies.clear(false);
        if (!listening) {
            listening = true;
            // a proxy saved with its chunk (server stopped mid-crossing) is dropped when it loads again
            NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, event -> {
                final Entity entity = event.getEntity();
                if (!event.getLevel().isClientSide() && entity.getTags().contains(IPSeatProxies.TAG)
                    && !IPSeatProxies.isServerProxy(entity.getUUID())) {
                    event.setCanceled(true);
                }
            });
        }
    }

    static void tick(final MinecraftServer server) {
        IPStraddleSessions.forEach(IPStraddleSeats::crossRiders);
        for (final Proxy proxy : new ArrayList<>(BY_PLAYER.values())) {
            maintain(server, proxy);
        }
    }

    // ---- a seat in another level

    /**
     * {@code player} took a seat in another level: on a far half, or on something seen through a portal. Riding needs the
     * vehicle in the rider's level: the client never learns of such a ride and stays at the plot position Sable sends it,
     * where the server then generates terrain. So the player moves to the seat's world first and sits like anyone there,
     * and {@link #crossRiders} takes them back through the portal if their head is past it. This waits for a task of its
     * own: the click runs inside IP's packet redirection, which must not see the player change dimension.
     */
    public static void sitInOtherLevel(final ServerPlayer player, final Entity seat) {
        final MinecraftServer server = player.server;
        server.tell(new TickTask(server.getTickCount(), () -> {
            if (player.hasDisconnected() || !player.isAlive() || player.isPassenger()) {
                return;
            }
            final ServerLevel level = (ServerLevel) seat.level();
            final SubLevel ship = Sable.HELPER.getContaining(seat);
            final Vec3 plot = seat.position();
            final Vec3 feet = ship == null ? plot : ship.logicalPose().transformPosition(plot);
            ServerTeleportationManager.of(server).forceTeleportPlayer(player, level.dimension(), feet);
            if (player.level() != level) {
                LOGGER.warn("[IPStraddle] could not move {} to a seat in {}; left standing", player.getGameProfile().getName(),
                    level.dimension().location());
                return;
            }
            if (seat.isRemoved()) {
                sit(level, seat.blockPosition(), player);
            } else {
                player.startRiding(seat, true);
            }
            LOGGER.info("[IPStraddle] {} took a seat in {} from another dimension", player.getGameProfile().getName(),
                level.dimension().location());
        }));
    }

    // ---- through the portal

    private static void crossRiders(final IPStraddleSessions.Session session) {
        if (session.ship.isRemoved()) {
            return;
        }
        for (final ServerPlayer player : List.copyOf(session.source.players())) {
            final Entity vehicle = player.getVehicle();
            if (vehicle == null || BY_PLAYER.containsKey(player.getUUID()) || !IPSableCompatHelper.isRidingOnShip(player)
                || Sable.HELPER.getContaining(vehicle) != session.ship) {
                continue;
            }
            final Vec3 offset = player.getEyePosition().subtract(session.origin);
            if (offset.dot(session.normal) > CROSS_MARGIN && inOpening(session.portal, offset)) {
                cross(player, vehicle, session);
            }
        }
    }

    private static boolean inOpening(final Portal portal, final Vec3 offset) {
        return Math.abs(offset.dot(portal.getAxisW())) <= portal.getWidth() * 0.5
            && Math.abs(offset.dot(portal.getAxisH())) <= portal.getHeight() * 0.5;
    }

    private static void cross(final ServerPlayer player, final Entity seat, final IPStraddleSessions.Session session) {
        final ServerLevel dest = session.dest;
        final IPPortalIsometry isometry = session.isometry;
        final Vec3 feet = player.position();
        final Vector3d plot = session.ship.logicalPose().transformPositionInverse(new Vector3d(feet.x, feet.y, feet.z));
        final Vec3 target = isometry.mapPoint(feet);
        final Vec3 look = isometry.mapVec(player.getLookAngle());
        final BlockPos seatPos = seat.blockPosition();

        // unlinked without vanilla's dismount placement (the player is moved right after)
        player.removeVehicle();
        ServerTeleportationManager.of(player.server).forceTeleportPlayer(player, dest.dimension(), target);
        if (player.level() != dest) {
            LOGGER.warn("[IPStraddle] could not move seated player {} through portal {}; left standing", player.getGameProfile().getName(),
                session.portal.getUUID());
            return;
        }
        setLook(player, look);

        final Display.BlockDisplay entity = EntityType.BLOCK_DISPLAY.create(dest);
        if (entity == null) {
            return;
        }
        entity.moveTo(target.x, target.y, target.z, 0.0f, 0.0f);
        entity.addTag(IPSeatProxies.TAG);
        IPSeatProxies.add(entity.getUUID(), false);
        dest.addFreshEntity(entity);
        final Proxy proxy = new Proxy(player.getUUID(), session.ship.getUniqueId(), session.source.dimension(), seatPos,
            new Vec3(plot.x, plot.y, plot.z), isometry, session.origin, session.normal, entity);
        BY_PLAYER.put(proxy.player, proxy);
        BY_ENTITY.put(entity.getUUID(), proxy);
        player.startRiding(entity, true);

        final Quaterniondc q = isometry.rotation();
        McRemoteProcedureCall.tellClientToInvoke(player, CLIENT + "begin", entity.getUUID(), proxy.ship, proxy.shipDim, proxy.riderPlot,
            isometry.origin(), isometry.dest(), new DQuaternion(q.x(), q.y(), q.z(), q.w()));
        LOGGER.info("[IPStraddle] {} seated on ship {} went through portal {} ({} -> {})", player.getGameProfile().getName(),
            proxy.ship, session.portal.getUUID(), proxy.shipDim.location(), dest.dimension().location());
    }

    // ---- while through

    private static void maintain(final MinecraftServer server, final Proxy proxy) {
        final ServerPlayer player = server.getPlayerList().getPlayer(proxy.player);
        if (player == null || !player.isAlive()) {
            drop(server, proxy, "player gone");
            return;
        }
        if (player.getVehicle() != proxy.entity) {
            // got off (onto the far half), or moved by something else
            drop(server, proxy, "got off");
            return;
        }
        final ServerLevel source = server.getLevel(proxy.shipDim);
        final SubLevel ship = shipOf(source, proxy.ship);
        if (ship == null) {
            player.stopRiding();
            drop(server, proxy, "ship gone");
            return;
        }
        final Vec3 feet = feetOf(ship, proxy);
        if (feet.add(0.0, player.getEyeHeight(), 0.0).subtract(proxy.origin).dot(proxy.normal) < -RETURN_MARGIN) {
            comeBack(server, proxy, player, source, feet);
            return;
        }
        proxy.entity.setPos(proxy.isometry.mapPoint(feet));
    }

    private static void comeBack(final MinecraftServer server, final Proxy proxy, final ServerPlayer player, final ServerLevel source,
                                 final Vec3 feet) {
        final Vec3 look = proxy.isometry.inverse().mapVec(player.getLookAngle());
        player.removeVehicle();
        ServerTeleportationManager.of(server).forceTeleportPlayer(player, source.dimension(), feet);
        setLook(player, look);
        sit(source, proxy.seatPos, player);
        drop(server, proxy, "came back through the portal with the seat");
    }

    /**
     * {@code shipId} was handed off from {@code source} to {@code dest}: riders through the portal with it take their seat
     * again, now in the destination.
     */
    public static void onHandedOff(final ServerLevel source, final ServerLevel dest, final UUID shipId, final IPSableBridge.Moved moved) {
        for (final Proxy proxy : new ArrayList<>(BY_PLAYER.values())) {
            if (!proxy.ship.equals(shipId)) {
                continue;
            }
            final ServerPlayer player = dest.getServer().getPlayerList().getPlayer(proxy.player);
            if (player != null && player.getVehicle() == proxy.entity) {
                player.removeVehicle();
                if (player.level() == dest) {
                    sit(dest, proxy.seatPos.offset(moved.shift()), player);
                }
            }
            drop(dest.getServer(), proxy, "ship crossed");
        }
    }

    private static void sit(final ServerLevel level, final BlockPos seatPos, final ServerPlayer player) {
        if (!IPShipCreateCompat.sitDown(level, seatPos, player)) {
            IPShipRiderCarry.queueSit(level.dimension(), seatPos, player.getUUID());
        }
    }

    private static void drop(final MinecraftServer server, final Proxy proxy, final String reason) {
        BY_PLAYER.remove(proxy.player);
        BY_ENTITY.remove(proxy.entity.getUUID());
        IPSeatProxies.remove(proxy.entity.getUUID(), false);
        proxy.entity.discard();
        final ServerPlayer player = server.getPlayerList().getPlayer(proxy.player);
        if (player != null) {
            McRemoteProcedureCall.tellClientToInvoke(player, CLIENT + "end", proxy.entity.getUUID());
        }
        LOGGER.info("[IPStraddle] seat proxy of {} on ship {} ended: {}", player != null ? player.getGameProfile().getName() : proxy.player,
            proxy.ship, reason);
    }

    // ---- placing the rider

    /**
     * @return where a seat proxy places its rider (server side): the seat's spot, mapped through the portal; null when
     * {@code entity} is no proxy or its ship is gone
     */
    @Nullable
    public static Vec3 riderPosition(final Entity entity) {
        final Proxy proxy = BY_ENTITY.get(entity.getUUID());
        if (proxy == null || entity.getServer() == null) {
            return null;
        }
        final SubLevel ship = shipOf(entity.getServer().getLevel(proxy.shipDim), proxy.ship);
        return ship == null ? null : proxy.isometry.mapPoint(feetOf(ship, proxy));
    }

    private static Vec3 feetOf(final SubLevel ship, final Proxy proxy) {
        final Vector3d world = ship.logicalPose().transformPosition(new Vector3d(proxy.riderPlot.x, proxy.riderPlot.y, proxy.riderPlot.z));
        return new Vec3(world.x, world.y, world.z);
    }

    @Nullable
    private static SubLevel shipOf(@Nullable final ServerLevel level, final UUID id) {
        final SubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
        final SubLevel ship = container == null ? null : container.getSubLevel(id);
        return ship == null || ship.isRemoved() ? null : ship;
    }

    private static void setLook(final ServerPlayer player, final Vec3 look) {
        final double horizontal = Math.sqrt(look.x * look.x + look.z * look.z);
        player.setYRot((float) Math.toDegrees(Math.atan2(-look.x, look.z)));
        player.setXRot((float) Math.toDegrees(Math.atan2(-look.y, horizontal)));
    }
}
