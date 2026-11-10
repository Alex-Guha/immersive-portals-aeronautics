/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.ServerLevelPlot;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector3d;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

/**
 * Carries entities along with a ship through a portal. {@link #capture} collects entities in the ship's
 * plot-space chunks, entities inside its world AABB (excluding IP's own entities and Create trains with their riders,
 * which Create moves between dimensions itself) and players riding a vehicle
 * in the plot, recording mount links and Create seat positions, and gives everyone a
 * {@value #CARRIED_PORTAL_COOLDOWN_TICKS}-tick portal cooldown. {@link #replay} moves vehicles first (vehicle +
 * passengers as a unit via IP), players via {@code forceTeleportPlayer} and other entities via
 * {@code teleportRegularEntityTo}, then queues delayed re-seat ({@code PendingSit}) and remount
 * ({@code PendingRemount}) attempts. {@link #tickPendingRemounts} also keeps a chunk ticket on every plot chunk
 * a seated player is in.
 */
public final class IPShipRiderCarry {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final double MAX_RIDER_OFFSET_SQR = 1048576.0;
    private static final int REMOUNT_DELAY_TICKS = 10;
    private static final int REMOUNT_ATTEMPTS = 5;
    private static final int CHUNK_WAIT_ATTEMPTS = 40;
    private static final int CARRIED_PORTAL_COOLDOWN_TICKS = 300;
    private static final TicketType<ChunkPos> SEATED_RIDER_TICKET = TicketType.create("imm_ptl_seated_rider", Comparator.comparingLong(ChunkPos::toLong), 200);
    private static boolean seatTicketLogged;
    private static boolean seatTicketErrorLogged;
    private static final List<PendingRemount> PENDING_REMOUNTS = new ArrayList<>();
    /** Players the last {@link #replay} moved with its ship, for {@link IPPhysicsStaffHooks#onShipMoved} (server thread). */
    private static final List<ServerPlayer> CARRIED_PLAYERS = new ArrayList<>();
    private static final List<PendingSit> PENDING_SITS = new ArrayList<>();

    private IPShipRiderCarry() {
    }

    /** Drops the pending re-seats and remounts of a previous server. */
    static void reset() {
        synchronized (PENDING_REMOUNTS) {
            PENDING_REMOUNTS.clear();
        }
        synchronized (PENDING_SITS) {
            PENDING_SITS.clear();
        }
    }

    private static boolean isPortalInfrastructure(Entity entity) {
        return entity instanceof Portal ? true : entity.getClass().getName().startsWith("qouteall.imm_ptl");
    }

    public static Captured capture(ServerLevel level, ServerSubLevel sub) {
        return capture(level, sub, false);
    }

    /**
     * @param straddled the ship went through straddling the portal (a hand-off), so it is wholly past the plane: a
     *                  player standing next to it is not carried. One in front of the portal has not gone through and
     *                  walks through on their own; the carry moved them by the ship's displacement to just short of the
     *                  far portal's plane, and through a portal with two faces its back face then took them straight back
     *                  (user, 2026-11-04). One behind the plane in this world got there some other way (round the
     *                  frame, or out of the far end of a portal into the same world). Seated players are still carried.
     */
    public static Captured capture(ServerLevel level, ServerSubLevel sub, boolean straddled) {
        ArrayList<Binding> bindings = new ArrayList<>();
        ArrayList<MountLink> mounts = new ArrayList<>();
        ArrayList<SeatBinding> seats = new ArrayList<>();
        LinkedHashSet<UUID> seen = new LinkedHashSet<>();
        Vector3d subPosition = sub.logicalPose().position();
        Vec3 subPos = new Vec3(subPosition.x(), subPosition.y(), subPosition.z());
        ServerLevelPlot plot = sub.getPlot();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);

        // (a) everything living in this ship's plot-space chunks
        if (container != null) {
            for (Entity entity : level.getAllEntities()) {
                ChunkPos chunkPos = entity.chunkPosition();
                if (!IPSableCompatHelper.isPlotSpaceChunk(chunkPos)) {
                    continue;
                }

                boolean onThisPlot;
                try {
                    onThisPlot = container.getPlot(chunkPos) == plot;
                } catch (Throwable e) {
                    continue;
                }

                if (!onThisPlot) {
                    continue;
                }

                if (seen.add(entity.getUUID())) {
                    bindings.add(new Binding(entity.getUUID(), entity.position(), entity.getYRot(), entity.getXRot(), true));
                }
            }
        }

        // (b) everything inside the hull in world space, except a train the ship rests on and its passengers
        AABB hull = IPSableBridge.worldAabb(sub).inflate(1.0);
        int leftOnTrain = 0;
        int leftStanding = 0;

        for (Entity entity : level.getEntities((Entity) null, hull, e -> true)) {
            if (seen.contains(entity.getUUID()) || isPortalInfrastructure(entity)) {
                continue;
            }

            if (IPShipCreateCompat.isOnTrain(entity)) {
                leftOnTrain++;
            } else if (straddled && entity instanceof Player && entity.getVehicle() == null) {
                leftStanding++;
            } else {
                Vec3 offset = entity.position().subtract(subPos);
                if (!(offset.lengthSqr() > MAX_RIDER_OFFSET_SQR) && seen.add(entity.getUUID())) {
                    bindings.add(new Binding(entity.getUUID(), entity.position(), entity.getYRot(), entity.getXRot(), false));
                }
            }
        }

        if (leftStanding > 0) {
            LOGGER.info("[IPShipPortal] ship {} leaves {} player(s) standing next to it to go through on their own", sub.getUniqueId(), leftStanding);
        }

        if (leftOnTrain > 0) {
            LOGGER.info("[IPShipPortal] ship {} leaves {} train carriage copy(ies) and rider(s) under it to Create", sub.getUniqueId(), leftOnTrain);
        }

        // (c) entities riding a vehicle that is on the ship (plus the vehicle, and the seat block to re-sit on), wherever
        // they are: Sable puts a rider at its seat a tick behind the ship, outside the hull on a fast one. The hand-off
        // deletes the old ship, which kills its seat entities (ServerLevelPlot.kickAllEntities), so every rider of one
        // is sat down again on its seat block
        for (Entity rider : level.getAllEntities()) {
            Entity vehicle = rider.getVehicle();
            if (vehicle != null) {
                boolean vehicleOnShip = seen.contains(vehicle.getUUID());
                if (!vehicleOnShip && container != null) {
                    try {
                        ChunkPos vehicleChunk = vehicle.chunkPosition();
                        vehicleOnShip = IPSableCompatHelper.isPlotSpaceChunk(vehicleChunk) && container.getPlot(vehicleChunk) == plot;
                    } catch (Throwable ignored) {
                    }
                }

                if (vehicleOnShip) {
                    if (seen.add(vehicle.getUUID())) {
                        bindings.add(new Binding(vehicle.getUUID(), vehicle.position(), vehicle.getYRot(), vehicle.getXRot(), true));
                    }

                    seats.add(new SeatBinding(rider.getUUID(), vehicle.blockPosition()));
                    if (seen.add(rider.getUUID())) {
                        bindings.add(new Binding(rider.getUUID(), rider.position(), rider.getYRot(), rider.getXRot(), true));
                    }
                }
            }
        }

        for (Binding binding : bindings) {
            Entity entity = level.getEntity(binding.uuid());
            if (entity != null) {
                Entity vehicle = entity.getVehicle();
                boolean vehicleInPlotSpace = vehicle != null && IPSableCompatHelper.isPlotSpacePosition(vehicle.getX(), vehicle.getZ());
                if (vehicle != null && (seen.contains(vehicle.getUUID()) || vehicleInPlotSpace)) {
                    mounts.add(new MountLink(binding.uuid(), vehicle.getUUID()));
                }

                entity.setPortalCooldown(CARRIED_PORTAL_COOLDOWN_TICKS);
            }
        }

        try {
            long nowTick = level.getServer().getTickCount();

            for (Binding binding : bindings) {
                if (level.getServer().getPlayerList().getPlayer(binding.uuid()) != null) {
                    IPShipRagdollGuard.suppressFor(binding.uuid(), nowTick);
                }
            }
        } catch (Throwable ignored) {
        }

        LOGGER.debug("[IPShipPortal] carrying {} entit(ies) and {} mount link(s) through the portal", bindings.size(), mounts.size());
        return new Captured(bindings, mounts, seats, subPos);
    }

    public static void replay(ServerLevel srcLevel, ServerLevel dstLevel, ServerSubLevel sub, BlockPos shift, Captured captured, Vec3 velocity) {
        CARRIED_PLAYERS.clear();
        if (captured.isEmpty()) {
            return;
        }

        Vector3d subPosition = sub.logicalPose().position();
        Vec3 subPos = new Vec3(subPosition.x(), subPosition.y(), subPosition.z());
        Vec3 shipDelta = subPos.subtract(captured.subPosBefore());
        Replay replay = new Replay(dstLevel, sub, shift, velocity, subPos, shipDelta);

        for (MountLink link : captured.mounts()) {
            replay.passengerToVehicle.put(link.passenger(), link.vehicle());
            replay.vehicles.add(link.vehicle());
        }

        for (SeatBinding seat : captured.seats()) {
            replay.seatOf.put(seat.riderUuid(), seat.seatBlockPos().offset(shift));
        }

        for (Binding binding : orderVehiclesFirst(captured.bindings(), replay)) {
            try {
                replay.carry(binding);
            } catch (Throwable e) {
                LOGGER.warn("[IPShipPortal] failed to carry entity {}: {}", binding.uuid(), e.toString());
            }
        }

        for (SeatBinding seat : captured.seats()) {
            BlockPos seatPos = seat.seatBlockPos().offset(shift);
            // a mob cannot hold on by itself: sit it down on the next tick, at the seat it was put on
            int delay = replay.mobsAtSeat.contains(seat.riderUuid()) ? 1 : REMOUNT_DELAY_TICKS;
            synchronized (PENDING_SITS) {
                PENDING_SITS.add(new PendingSit(seat.riderUuid(), seatPos, dstLevel.dimension(), delay, REMOUNT_ATTEMPTS));
            }
        }

        int queuedRemounts = 0;

        for (MountLink link : captured.mounts()) {
            if (!replay.movedAsUnit.contains(link.vehicle()) && replay.moved.containsKey(link.passenger()) && replay.moved.containsKey(link.vehicle())) {
                synchronized (PENDING_REMOUNTS) {
                    PENDING_REMOUNTS.add(new PendingRemount(link.passenger(), link.vehicle(), dstLevel.dimension(), REMOUNT_DELAY_TICKS, REMOUNT_ATTEMPTS));
                }

                queuedRemounts++;
            }
        }

        LOGGER.debug(
            "[IPShipPortal] carried {} entit(ies) into {} ({} vehicle unit(s), {} player(s), {} vanished, {} landed on deck, {} force-dismounted); queued {} re-seat(s), {} remount(s)",
            replay.moved.size(),
            dstLevel.dimension().location(),
            replay.movedAsUnit.size(),
            replay.playersMoved,
            replay.vanished,
            replay.landedOnDeck,
            replay.forceDismounted,
            captured.seats().size(),
            queuedRemounts
        );
    }

    /**
     * @return the players the last {@link #replay} moved through with its ship, then forgets them: they went through the
     * ship's portal without IP's own teleport, so nothing told the physics staff or a held handle
     */
    public static List<ServerPlayer> takeCarriedPlayers() {
        final List<ServerPlayer> players = List.copyOf(CARRIED_PLAYERS);
        CARRIED_PLAYERS.clear();
        return players;
    }

    /** Bindings re-ordered so vehicles (non-passengers) come before their passengers. */
    private static List<Binding> orderVehiclesFirst(List<Binding> bindings, Replay replay) {
        ArrayList<Binding> ordered = new ArrayList<>();

        for (Binding binding : bindings) {
            if (!replay.passengerToVehicle.containsKey(binding.uuid())) {
                ordered.add(binding);
            }
        }

        for (Binding binding : bindings) {
            if (replay.passengerToVehicle.containsKey(binding.uuid())) {
                ordered.add(binding);
            }
        }

        return ordered;
    }

    /** Mutable state of one {@link #replay} run, with the per-entity move logic. */
    private static final class Replay {
        final ServerLevel dstLevel;
        final ServerSubLevel sub;
        final BlockPos shift;
        final Vec3 velocity;
        final Vec3 subPos;
        final Vec3 shipDelta;
        final HashMap<UUID, Entity> moved = new HashMap<>();
        final LinkedHashSet<UUID> movedAsUnit = new LinkedHashSet<>();
        final HashMap<UUID, UUID> passengerToVehicle = new HashMap<>();
        final LinkedHashSet<UUID> vehicles = new LinkedHashSet<>();
        /** The seat block (in the arrived ship's plot) of each rider of a seat. */
        final HashMap<UUID, BlockPos> seatOf = new HashMap<>();
        /** Mobs whose seat died with the old ship, put at its place on the arrived one. */
        final LinkedHashSet<UUID> mobsAtSeat = new LinkedHashSet<>();
        int playersMoved;
        int vanished;
        int landedOnDeck;
        int forceDismounted;

        Replay(ServerLevel dstLevel, ServerSubLevel sub, BlockPos shift, Vec3 velocity, Vec3 subPos, Vec3 shipDelta) {
            this.dstLevel = dstLevel;
            this.sub = sub;
            this.shift = shift;
            this.velocity = velocity;
            this.subPos = subPos;
            this.shipDelta = shipDelta;
        }

        void carry(Binding binding) {
            UUID vehicleId = this.passengerToVehicle.get(binding.uuid());
            if (vehicleId != null && this.movedAsUnit.contains(vehicleId)) {
                return; // already moved together with its vehicle
            }

            Entity entity = findEntity(this.dstLevel.getServer(), binding.uuid());
            if (entity == null) {
                this.vanished++;
                return;
            }

            Vec3 target = this.targetFor(binding, entity, vehicleId);
            if (this.vehicles.contains(binding.uuid()) && !entity.getPassengers().isEmpty()) {
                Entity unit = ServerTeleportationManager.of(this.dstLevel.getServer()).teleportVehicleAcrossDimensions(entity, this.dstLevel.dimension(), target);
                if (unit != null) {
                    this.movedAsUnit.add(binding.uuid());
                    this.moved.put(binding.uuid(), unit);
                    return;
                }

                LOGGER.warn("[IPShipPortal] vehicle unit teleport failed; falling back to per-entity");
            }

            if (entity instanceof ServerPlayer player) {
                this.carryPlayer(binding, player, target, vehicleId);
            } else {
                Entity teleported = ServerTeleportationManager.teleportRegularEntityTo(entity, this.dstLevel.dimension(), target);
                if (teleported != null) {
                    teleported.setYRot(binding.yRot());
                    teleported.setXRot(binding.xRot());
                    teleported.setDeltaMovement(this.velocity);
                    this.moved.put(binding.uuid(), teleported);
                } else {
                    LOGGER.warn("[IPShipPortal] teleportRegularEntityTo returned null for {}", entity.getType());
                }
            }
        }

        /**
         * Where the entity lands: on its already-moved vehicle, at its seat on the arrived ship for a mob whose seat
         * died with the old one, above the ship for another orphaned passenger or a plot-space player without a mount
         * link, plot-shifted for other plot-space entities, otherwise moved by the ship's displacement.
         */
        private Vec3 targetFor(Binding binding, Entity entity, UUID vehicleId) {
            boolean plotSpace = IPSableCompatHelper.isPlotSpacePosition(binding.position().x, binding.position().z);
            Entity movedVehicle = vehicleId == null ? null : this.moved.get(vehicleId);
            BlockPos seat = this.seatOf.get(binding.uuid());
            if (movedVehicle != null) {
                return movedVehicle.position();
            } else if (vehicleId != null && seat != null && !(entity instanceof Player)) {
                this.mobsAtSeat.add(binding.uuid());
                return this.sub.logicalPose().transformPosition(Vec3.atCenterOf(seat));
            } else if (vehicleId != null) {
                return this.subPos.add(0.0, 1.0, 0.0);
            } else if (plotSpace && entity instanceof Player) {
                // sat down mid-air with no captured mount link: land them on the craft instead of plot-shifting
                this.landedOnDeck++;
                return this.subPos.add(0.0, 1.0, 0.0);
            } else if (plotSpace) {
                return binding.position().add(this.shift.getX(), this.shift.getY(), this.shift.getZ());
            } else {
                return binding.position().add(this.shipDelta);
            }
        }

        private void carryPlayer(Binding binding, ServerPlayer player, Vec3 target, UUID vehicleId) {
            ServerTeleportationManager.of(player.server).forceTeleportPlayer(player, this.dstLevel.dimension(), target);
            player.setYRot(binding.yRot());
            player.setXRot(binding.xRot());
            player.setDeltaMovement(this.velocity);
            player.hurtMarked = true;
            if (player.level() != this.dstLevel) {
                LOGGER.warn("[IPShipPortal] player still in {} after forceTeleportPlayer; retrying", player.level().dimension().location());
                ServerTeleportationManager.of(player.server).forceTeleportPlayer(player, this.dstLevel.dimension(), target, true);
            }

            this.playersMoved++;
            CARRIED_PLAYERS.add(player);
            if (vehicleId != null && !this.moved.containsKey(vehicleId)) {
                // the seat did not survive the plot removal; dismount so the client releases it
                player.stopRiding();
                this.forceDismounted++;
            }

            this.moved.put(binding.uuid(), player);
        }
    }

    private static Entity findEntity(MinecraftServer server, UUID uuid) {
        if (server == null) {
            return null;
        } else {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                return player;
            } else {
                for (ServerLevel level : server.getAllLevels()) {
                    Entity entity = level.getEntity(uuid);
                    if (entity != null) {
                        return entity;
                    }
                }

                return null;
            }
        }
    }

    /** Keeps a chunk ticket on the plot chunk of every seated player so the seat's chunk stays resident. */
    private static void holdSeatedRiderChunks(MinecraftServer server) {
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (player.isPassenger()) {
                    ChunkPos chunkPos = player.chunkPosition();
                    if (IPSableCompatHelper.isPlotSpaceChunk(chunkPos)) {
                        ServerLevel level = player.serverLevel();
                        level.getChunkSource().addRegionTicket(SEATED_RIDER_TICKET, chunkPos, 2, chunkPos);
                        if (!seatTicketLogged) {
                            seatTicketLogged = true;
                            LOGGER.debug("[IPShipPortal] holding plot chunk {} resident while a rider is seated there", chunkPos);
                        }
                    }
                }
            }
        } catch (Throwable e) {
            if (!seatTicketErrorLogged) {
                seatTicketErrorLogged = true;
                LOGGER.warn("[IPShipPortal] seated-rider chunk hold failed: {}", e.toString());
            }
        }
    }

    private static boolean isSeatChunkResident(ServerLevel level, BlockPos seatPos) {
        try {
            return level.getChunkSource().hasChunk(seatPos.getX() >> 4, seatPos.getZ() >> 4);
        } catch (Throwable e) {
            return true;
        }
    }

    private static void tickPendingSits(MinecraftServer server) {
        ArrayList<PendingSit> due;
        synchronized (PENDING_SITS) {
            if (PENDING_SITS.isEmpty()) {
                return;
            }

            due = new ArrayList<>();
            Iterator<PendingSit> iterator = PENDING_SITS.iterator();

            while (iterator.hasNext()) {
                PendingSit pending = iterator.next();
                if (--pending.delay <= 0) {
                    due.add(pending);
                    iterator.remove();
                }
            }
        }

        for (PendingSit pending : due) {
            try {
                ServerLevel level = server.getLevel(pending.dim);
                Entity rider = findEntity(server, pending.rider);
                boolean seated = false;
                if (level != null && rider != null && rider.isAlive() && !rider.isPassenger()) {
                    if (!isSeatChunkResident(level, pending.seatPos)) {
                        if (--pending.chunkWaitsLeft > 0) {
                            pending.delay = REMOUNT_DELAY_TICKS;
                            synchronized (PENDING_SITS) {
                                PENDING_SITS.add(pending);
                            }
                        } else {
                            LOGGER.debug("[IPShipPortal] plot chunk for seat {} never became resident; left rider standing on the deck", pending.seatPos);
                        }
                        continue;
                    }

                    seated = IPShipCreateCompat.sitDown(level, pending.seatPos, rider);
                }

                if (seated) {
                    LOGGER.debug("[IPShipPortal] re-seated {} on {}", pending.rider, pending.seatPos);
                } else if (--pending.attemptsLeft > 0) {
                    pending.delay = REMOUNT_DELAY_TICKS;
                    synchronized (PENDING_SITS) {
                        PENDING_SITS.add(pending);
                    }
                } else {
                    LOGGER.debug("[IPShipPortal] could not re-seat {} on {}; left standing on the deck", pending.rider, pending.seatPos);
                }
            } catch (Throwable e) {
                LOGGER.warn("[IPShipPortal] re-seat error: {}", e.toString());
            }
        }
    }

    public static void tickPendingRemounts(MinecraftServer server) {
        holdSeatedRiderChunks(server);
        tickPendingSits(server);
        ArrayList<PendingRemount> due;
        synchronized (PENDING_REMOUNTS) {
            if (PENDING_REMOUNTS.isEmpty()) {
                return;
            }

            due = new ArrayList<>();
            Iterator<PendingRemount> iterator = PENDING_REMOUNTS.iterator();

            while (iterator.hasNext()) {
                PendingRemount pending = iterator.next();
                if (--pending.delay <= 0) {
                    due.add(pending);
                    iterator.remove();
                }
            }
        }

        for (PendingRemount pending : due) {
            try {
                Entity passenger = findEntity(server, pending.passenger);
                Entity vehicle = findEntity(server, pending.vehicle);
                boolean mounted = false;
                if (passenger != null && vehicle != null && passenger.isAlive() && vehicle.isAlive() && passenger.level() == vehicle.level()) {
                    mounted = passenger.startRiding(vehicle, true);
                }

                if (mounted) {
                    LOGGER.debug("[IPShipPortal] re-seated {}", pending.passenger);
                } else if (--pending.attemptsLeft > 0) {
                    pending.delay = REMOUNT_DELAY_TICKS;
                    synchronized (PENDING_REMOUNTS) {
                        PENDING_REMOUNTS.add(pending);
                    }
                } else {
                    LOGGER.debug("[IPShipPortal] gave up re-seating {}; left standing (better than a stuck seat)", pending.passenger);
                }
            } catch (Throwable e) {
                LOGGER.warn("[IPShipPortal] re-seat error: {}", e.toString());
            }
        }
    }

    public record Binding(UUID uuid, Vec3 position, float yRot, float xRot, boolean plotSpace) {
    }

    public record Captured(List<Binding> bindings, List<MountLink> mounts, List<SeatBinding> seats, Vec3 subPosBefore) {
        public boolean isEmpty() {
            return this.bindings.isEmpty();
        }
    }

    public record MountLink(UUID passenger, UUID vehicle) {
    }

    private static final class PendingRemount {
        final UUID passenger;
        final UUID vehicle;
        final ResourceKey<Level> dim;
        int delay;
        int attemptsLeft;

        PendingRemount(UUID passenger, UUID vehicle, ResourceKey<Level> dim, int delay, int attemptsLeft) {
            this.passenger = passenger;
            this.vehicle = vehicle;
            this.dim = dim;
            this.delay = delay;
            this.attemptsLeft = attemptsLeft;
        }
    }

    private static final class PendingSit {
        int chunkWaitsLeft = CHUNK_WAIT_ATTEMPTS;
        final UUID rider;
        final BlockPos seatPos;
        final ResourceKey<Level> dim;
        int delay;
        int attemptsLeft;

        PendingSit(UUID rider, BlockPos seatPos, ResourceKey<Level> dim, int delay, int attemptsLeft) {
            this.rider = rider;
            this.seatPos = seatPos;
            this.dim = dim;
            this.delay = delay;
            this.attemptsLeft = attemptsLeft;
        }
    }

    /**
     * Seats {@code rider} on the seat at {@code seatPos} of {@code dim} once it can (the seat's plot chunk resident, the
     * block there), retrying as a hand-off's re-seat does.
     */
    public static void queueSit(ResourceKey<Level> dim, BlockPos seatPos, UUID rider) {
        synchronized (PENDING_SITS) {
            PENDING_SITS.add(new PendingSit(rider, seatPos, dim, REMOUNT_DELAY_TICKS, REMOUNT_ATTEMPTS));
        }
    }

    public record SeatBinding(UUID riderUuid, BlockPos seatBlockPos) {
    }
}
