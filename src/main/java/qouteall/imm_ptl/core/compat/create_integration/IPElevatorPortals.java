/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllEntityTypes;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorColumn;
import com.simibubi.create.content.contraptions.elevator.ElevatorContactBlock;
import com.simibubi.create.content.contraptions.elevator.ElevatorContactBlockEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorContraption;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.createmod.catnip.data.Couple;
import net.createmod.catnip.data.IntAttached;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.mixin_create.IPControlledContraptionAccessor;
import qouteall.imm_ptl.core.compat.mixin_create.IPElevatorColumnAccessor;
import qouteall.imm_ptl.core.compat.mixin_create.IPLinearActuatorAccessor;

/**
 * Create elevators whose shaft runs through floor portals, one below the other ({@link IPElevatorShaft#findChain}). The
 * car (the home car) stays in its pulley's level and is moved by Create as anywhere, on past the portal planes; while
 * part of it is past a plane, a far copy stands in the level past that portal at the mapped place, placed by the car
 * every tick on the server and on each client, so players there stand on it, see it and use its controls. A far copy
 * has no controller, is never saved and ticks no actors on the server; its chests open the car's own storage
 * ({@link #openCarStorage}); seated riders are handed between the copies at each plane ({@link #handOverRiders}).
 * The floors of the pulley's column and of each far column form one list ({@link #link}): each sees the others' floors
 * in its own frame, and a call from a far floor targets the home column.
 */
public final class IPElevatorPortals {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ticks between shaft searches of a running elevator (portals appear, go and move). */
    private static final int SCAN_TICKS = 10;
    /** A far copy whose car has not placed it for this long is removed (its pulley stopped ticking). */
    private static final int STALE_TICKS = 40;
    /** A column link lasts this long after its last refresh. */
    private static final int LINK_TICKS = 40;
    private static final double EPS = 1.0e-4;
    /** {@code -Dip_aeronautics.debug.elevatorTrace=true}: a line per running elevator with a shaft each second. */
    private static final boolean TRACE = Boolean.getBoolean("ip_aeronautics.debug.elevatorTrace");

    /** A far copy's place in the tables: the pulley it stands for, and past which portal of its chain. */
    record CopyKey(BlockPos pulley, int index) {
    }

    /** Far copies by home dimension, pulley and portal, per side (server and client). */
    private static final Map<ResourceKey<Level>, Map<CopyKey, ControlledContraptionEntity>> SERVER_COPIES = new HashMap<>();
    private static final Map<ResourceKey<Level>, Map<CopyKey, ControlledContraptionEntity>> CLIENT_COPIES = new HashMap<>();

    /** Another column of a linked group; {@code dy}: a contact at y here is at {@code y + dy} there; {@code home}: the pulley's. */
    public record Member(ServerLevel level, ElevatorColumn.ColumnCoords coords, int dy, boolean home) {
    }

    /** A column's linked group: the pulley's column and the column past each portal of its shaft, this one left out. */
    public record Link(List<Member> others, long until) {
    }

    private static final Map<LevelAccessor, Map<ElevatorColumn.ColumnCoords, Link>> LINKS = new WeakHashMap<>();
    /** Set while a column calls its linked column, so the call is not mirrored back. */
    private static boolean mirroring;

    private IPElevatorPortals() {
    }

    // ---- the pulley's tick

    public static void tick(final ElevatorPulleyBlockEntity pulley) {
        final Level level = pulley.getLevel();
        if (level == null) {
            return;
        }
        if (level instanceof ServerLevel serverLevel) {
            tickServer(serverLevel, pulley);
        } else {
            tickClient(level, pulley);
        }
    }

    private static void tickServer(final ServerLevel level, final ElevatorPulleyBlockEntity pulley) {
        final IPElevatorPulley holder = (IPElevatorPulley) pulley;
        final BlockPos pos = pulley.getBlockPos();
        final AbstractContraptionEntity home = pulley.movedContraption;
        final long now = level.getServer().getTickCount();
        if (pulley.running && home == null) {
            // the car is not attached yet (just loaded): its shaft stays, or Create keeps the offset above the world's
            // floor meanwhile (the range of MixinPulleyBlockEntity_ElevatorRange)
            removeCopies(level, pos, "its car is not there");
            return;
        }
        if (!pulley.running || home == null || !home.isAlive() || !(home.getContraption() instanceof ElevatorContraption ec)) {
            if (!holder.ip$chain().isEmpty()) {
                holder.ip$setChain(List.of());
                pulley.sendData();
                sendShaft(level, pos, List.of());
                if (home != null && home.isAlive()) {
                    IPElevatorFrames.refreshCollider(home);
                }
            }
            removeCopies(level, pos, "the elevator stopped");
            return;
        }
        if (now - holder.ip$lastScan() >= SCAN_TICKS) {
            final boolean firstScan = holder.ip$lastScan() < 0; // since the pulley loaded
            holder.ip$setLastScan(now);
            final List<IPElevatorShaft> found = IPElevatorShaft.findChain(level, pos, home.getBoundingBox());
            if (!IPElevatorShaft.lastRejections.isEmpty()
                && !IPElevatorShaft.lastRejections.equals(REJECTIONS.put(pos.immutable(), IPElevatorShaft.lastRejections))) {
                LOGGER.info("[IPElevator] pulley {} in {}: {}; turned down {}", pos.toShortString(), level.dimension().location(),
                    found.isEmpty() ? "no shaft" : "the shaft ends after " + found.size() + " portal(s)", IPElevatorShaft.lastRejections);
            }
            final long sinceLoad = now - LOADED_AT.computeIfAbsent(pulley, k -> now);
            if (found.size() < holder.ip$chain().size() && sinceLoad < LOAD_GRACE_TICKS) {
                // just loaded with a saved shaft whose portals are not all loaded yet (ships load later than chunks): kept,
                // and looked for again next tick. Dropped, Create kept the car above the world's floor meanwhile and it
                // jumped there and back as the world loaded
                if (firstScan) {
                    LOGGER.info("[IPElevator] pulley {} in {}: waiting for the portals of its saved shaft to load (found {} of {})",
                        pos.toShortString(), level.dimension().location(), found.size(), holder.ip$chain().size());
                }
                holder.ip$setLastScan(now - SCAN_TICKS + 1);
                return;
            }
            if ((!found.isEmpty() && SHAFT_LOGGED.add(pulley)) || !found.equals(holder.ip$chain())) {
                LOGGER.info("[IPElevator] pulley {} in {}: {}", pos.toShortString(), level.dimension().location(),
                    found.isEmpty() ? "no shaft through a portal" : "shaft through " + describe(found));
            }
            if (!found.equals(holder.ip$chain())) {
                final boolean hadShaft = !holder.ip$chain().isEmpty();
                holder.ip$setChain(found);
                pulley.sendData();
                sendShaft(level, pos, found);
                IPElevatorFrames.refreshCollider(home);
                if (hadShaft) {
                    // a far copy stands in its shaft's far frame and carries that shaft: made again below in the new one.
                    // Moved instead, a copy left in the world when the far end went onto a ship (or taken into the ship's
                    // plot by its assembly) was drawn nowhere
                    removeCopies(level, pos, "its shaft changed");
                }
            }
        }
        final List<IPElevatorShaft> chain = holder.ip$chain();
        if (!chain.isEmpty()) {
            SHAFT_PULLEYS.computeIfAbsent(level.dimension(), k -> new java.util.HashSet<>()).add(pos.immutable());
        }
        if (!chain.isEmpty() && now % SHAFT_RESEND_TICKS == 0) {
            sendShaft(level, pos, chain);
        }
        if (TRACE && now % 20 == 0) {
            final ControlledContraptionEntity c = copyOf(SERVER_COPIES, level.dimension(), pos, 0, ofServer(level));
            LOGGER.info("[IPElevatorTrace] pulley {} car y {} minY {} portals {} copy {} offset {} floors {}", pos.toShortString(),
                home.getY(), home.getBoundingBox().minY, chain.size(), c == null ? "none" : c.getId() + " y " + c.getY(), pulley.offset,
                ec.namesList.stream().map(f -> f.getFirst() + "=" + f.getSecond().getFirst()).toList());
        }
        if (chain.isEmpty()) {
            removeCopies(level, pos, "no shaft");
            return;
        }
        link(level, ec.getGlobalColumn(), chain, now);
        // a far copy is made a little before the car reaches its plane, so that each client has it, and Flywheel its
        // visual, by the time the car is cut there (cut below its own plane, it shows nothing until then); the same
        // below, for a car coming up to the next portal's plane from past it
        // (a car's previous place means nothing in its first ticks: it was just assembled)
        final double speed = home.tickCount < 3 ? 0.0 : Math.min(2.0, Math.abs(home.getY() - home.yo));
        final double lead = COPY_LEAD + COPY_LEAD_TICKS * speed;
        final AABB box = home.getBoundingBox();
        final ControlledContraptionEntity[] copies = new ControlledContraptionEntity[chain.size()];
        for (int k = 0; k < chain.size(); k++) {
            final IPElevatorShaft shaft = chain.get(k);
            final double bottom = k + 1 < chain.size() ? chain.get(k + 1).planeY() : Double.NEGATIVE_INFINITY;
            final ServerLevel far = level.getServer().getLevel(shaft.farDim());
            ControlledContraptionEntity copy = copyOf(SERVER_COPIES, level.dimension(), pos, k, ofServer(level));
            final double keepFor = copy != null ? 1.0 : 0.0;
            if (far == null) {
                removeCopy(level, pos, k, "its level is not there");
                continue;
            }
            if (box.minY >= shaft.planeY() + lead + keepFor) {
                removeCopy(level, pos, k, "the car is above its plane");
                continue;
            }
            if (box.maxY <= bottom - lead - keepFor) {
                removeCopy(level, pos, k, "the car is past the next plane");
                continue;
            }
            if (copy == null) {
                if (!far.isLoaded(BlockPos.containing(shaft.entityToFar(home.position())))) {
                    continue;
                }
                copy = makeCopy(level, far, pulley, home, k, shaft, bottom);
                if (copy == null) {
                    continue;
                }
            }
            place(home, copy, shaft);
            ((IPElevatorFarCopy) copy).ip$setLastPush(now);
            copies[k] = copy;
        }
        handOverRiders(home, chain, copies);
    }

    private static String describe(final List<IPElevatorShaft> chain) {
        final StringBuilder text = new StringBuilder();
        for (final IPElevatorShaft shaft : chain) {
            text.append(text.isEmpty() ? "" : ", then ").append("portal ").append(shaft.portalId()).append(" at y ").append(shaft.planeY())
                .append(" into ").append(shaft.farDim().location()).append(" (shift ").append(shaft.shift().toShortString())
                .append(shaft.turns() == 0 ? "" : ", turned " + 90 * shaft.turns() + " degrees").append(')');
        }
        return text.toString();
    }

    /**
     * The far copy is there while the car is within this many blocks above the plane, plus {@link #COPY_LEAD_TICKS} ticks
     * of its motion (and one block more before it goes). A copy made as the car reached the plane got to a client about
     * two ticks later and was drawn about two ticks after that: the car's floor, cut at the plane, was drawn on neither
     * side meanwhile.
     */
    private static final double COPY_LEAD = 2.0;
    private static final double COPY_LEAD_TICKS = 8.0;

    /** When each pulley first ticked here, for {@link #LOAD_GRACE_TICKS}. */
    private static final Map<ElevatorPulleyBlockEntity, Long> LOADED_AT = new WeakHashMap<>();
    /** After loading, a saved shaft is kept this long while the search finds fewer of its portals (not loaded yet). */
    private static final int LOAD_GRACE_TICKS = 200;
    /** Pulleys whose shaft was logged since they loaded (a saved shaft found again unchanged is logged once). */
    private static final java.util.Set<ElevatorPulleyBlockEntity> SHAFT_LOGGED =
        java.util.Collections.newSetFromMap(new WeakHashMap<>());

    /** The last reasons logged for a pulley finding no shaft, so each is logged once. */
    private static final Map<BlockPos, String> REJECTIONS = new HashMap<>();

    /** Pulleys that had a shaft, by dimension (checked again on use): for {@link #whyNotMoved}. */
    private static final Map<ResourceKey<Level>, java.util.Set<BlockPos>> SHAFT_PULLEYS = new HashMap<>();

    /**
     * Why the blocks {@code moved} picks out (a ship's, about to be placed in the world, or the world's, about to become a
     * ship) may not move now, or null: one of them is an elevator pulley whose car is partly through a portal. Assembly
     * takes such a car apart and moves its blocks with the rest, and disassembly does the same from the ship: the part of
     * the car past the portal plane was placed behind the portal and the car was lost.
     */
    public static @Nullable Component whyNotMoved(final ServerLevel level, final java.util.function.Predicate<BlockPos> moved) {
        final java.util.Set<BlockPos> pulleys = SHAFT_PULLEYS.get(level.dimension());
        if (pulleys == null) {
            return null;
        }
        for (final BlockPos pos : List.copyOf(pulleys)) {
            if (!level.isLoaded(pos) || !(level.getBlockEntity(pos) instanceof ElevatorPulleyBlockEntity pulley)
                || ((IPElevatorPulley) pulley).ip$shaft() == null) {
                pulleys.remove(pos);
                continue;
            }
            final IPElevatorShaft shaft = ((IPElevatorPulley) pulley).ip$shaft();
            final AbstractContraptionEntity home = pulley.movedContraption;
            if (pulley.running && home != null && home.getBoundingBox().minY < shaft.planeY() - EPS && moved.test(pos)) {
                LOGGER.info("[IPElevator] pulley {} in {}: its blocks may not move now, its car is partly through a portal",
                    pos.toShortString(), level.dimension().location());
                return Component.literal("An elevator's car is partly through a portal: bring it to a floor on the pulley's side first");
            }
        }
        return null;
    }

    /** A pulley's shaft is sent to every client again this often while it has one (and at once when it changes). */
    private static final int SHAFT_RESEND_TICKS = 40;
    private static final String CLIENT = "qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortalsClient.RemoteCallables.";

    /**
     * Sends a pulley's shaft to every client (empty: none). The pulley's own block entity data carries it too, but for a
     * pulley on a ship that data reached a client late or not at all (only data sent with the ship's whole chunk came),
     * and a client without the shaft drew the car past the plane whole. Every player, since a car is seen through portals
     * from other dimensions; the message is small and goes out once every two seconds per elevator with a shaft.
     */
    private static void sendShaft(final ServerLevel level, final BlockPos pulley, final List<IPElevatorShaft> chain) {
        final CompoundTag tag = chain.isEmpty() ? new CompoundTag() : IPElevatorShaft.writeChain(chain);
        for (final net.minecraft.server.level.ServerPlayer player : level.getServer().getPlayerList().getPlayers()) {
            qouteall.q_misc_util.api.McRemoteProcedureCall.tellClientToInvoke(player, CLIENT + "setShaft", level.dimension(),
                pulley, tag);
        }
    }

    /** A seat's rider changes copy once its head is this far past the plane (the band is twice this). */
    private static final double SEAT_MARGIN = 0.05;
    /** Height of a seated rider's head above the seat block's bottom. */
    private static final double SEAT_HEAD = 1.2;

    /**
     * Server: the riders of each seat sit on the copy between whose planes their head is, handed between the car and
     * the far copies as it crosses (players with IP's seamless teleport, other riders with IP's entity teleport), with a
     * small band so a rider at a plane is not handed to and fro. {@code copies[k]}: the copy past portal k, or null.
     */
    private static void handOverRiders(final AbstractContraptionEntity home, final List<IPElevatorShaft> chain,
                                       final ControlledContraptionEntity[] copies) {
        final Contraption contraption = home.getContraption();
        if (contraption == null) {
            return;
        }
        final List<BlockPos> seats = contraption.getSeats();
        // -1 is the car, k the copy past portal k
        for (int i = -1; i < chain.size(); i++) {
            final AbstractContraptionEntity from = i < 0 ? home : copies[i];
            if (from == null || from.getPassengers().isEmpty() || from.getContraption() == null) {
                continue;
            }
            final IPElevatorShaft fromShaft = i < 0 ? null : chain.get(i);
            final double top = fromShaft == null ? Double.POSITIVE_INFINITY : fromShaft.planeY();
            final double bottom = i + 1 < chain.size() ? chain.get(i + 1).planeY() : Double.NEGATIVE_INFINITY;
            for (final Entity rider : List.copyOf(from.getPassengers())) {
                final Integer seat = from.getContraption().getSeatMapping().get(rider.getUUID());
                if (seat == null || seat >= seats.size()) {
                    continue;
                }
                // the head's height in the pulley's frame
                final double head = headY(from, seats.get(seat)) - (fromShaft == null ? 0 : fromShaft.shift().getY());
                final int to = head < bottom - SEAT_MARGIN ? i + 1 : head > top + SEAT_MARGIN ? i - 1 : i;
                final AbstractContraptionEntity target = to == i ? null : to < 0 ? home : copies[to];
                if (target == null || !(target.level() instanceof ServerLevel toLevel)) {
                    continue;
                }
                final IPElevatorShaft toShaft = to < 0 ? null : chain.get(to);
                moveRider(rider, from, target, toLevel, seat, p -> {
                    final Vec3 inPulleyFrame = fromShaft == null ? p : fromShaft.fromFar(p);
                    return toShaft == null ? inPulleyFrame : toShaft.toFar(inPulleyFrame);
                });
            }
        }
    }

    private static double headY(final AbstractContraptionEntity entity, final BlockPos seat) {
        return entity.toGlobalVector(new Vec3(seat.getX() + 0.5, seat.getY() + SEAT_HEAD, seat.getZ() + 0.5), 1.0f).y;
    }

    private static void moveRider(final Entity rider, final AbstractContraptionEntity from, final AbstractContraptionEntity to,
                                  final ServerLevel toLevel, final int seat, final java.util.function.UnaryOperator<Vec3> map) {
        final Vec3 at = from.getPassengerPosition(rider, 1.0f); // in from's frame
        if (at == null) {
            return;
        }
        // the seat's place on the other copy, in the world (a copy on a ship is in its plot)
        final Vec3 target = IPElevatorFrames.toWorld(toLevel, map.apply(at));
        rider.stopRiding();
        Entity moved = rider;
        if (rider instanceof net.minecraft.server.level.ServerPlayer player) {
            if (!IPCreateRiderTeleport.teleport(player, from, toLevel, target.x, target.y, target.z)) {
                return;
            }
        } else {
            moved = qouteall.imm_ptl.core.teleportation.ServerTeleportationManager.teleportEntityGeneral(rider, target, toLevel);
            if (moved == null) {
                return;
            }
        }
        to.addSittingPassenger(moved, seat);
        LOGGER.info("[IPElevator] seated {} handed from {} {} to {} {} (seat {})", moved.getName().getString(),
            from.level().dimension().location(), from.getId(), to.level().dimension().location(), to.getId(), seat);
    }

    private static void tickClient(final Level level, final ElevatorPulleyBlockEntity pulley) {
        IPElevatorPortalsClient.track(pulley);
        final AbstractContraptionEntity home = pulley.movedContraption;
        final Map<CopyKey, ControlledContraptionEntity> byKey = CLIENT_COPIES.get(level.dimension());
        if (((IPElevatorPulley) pulley).ip$chain().isEmpty() || home == null || byKey == null || byKey.isEmpty()) {
            return;
        }
        for (final Map.Entry<CopyKey, ControlledContraptionEntity> entry : List.copyOf(byKey.entrySet())) {
            if (!entry.getKey().pulley().equals(pulley.getBlockPos())) {
                continue;
            }
            final ControlledContraptionEntity copy = copyOf(CLIENT_COPIES, level.dimension(), entry.getKey().pulley(), entry.getKey().index(),
                IPElevatorPortalsClient::isLive);
            final IPElevatorFarCopy.FarInfo info = copy == null ? null : farInfo(copy);
            if (info == null) {
                continue;
            }
            final IPElevatorShaft shaft = info.shaft();
            place(home, copy, shaft);
            IPElevatorPortalsClient.ensureFarVisual(copy);
            // while the car is about to cross, at it or just past it, its portal is drawn every frame: the car may fill the
            // opening and hide it until its floor or roof is past the plane, and IP draws a portal that was hidden last frame
            // one frame late
            qouteall.imm_ptl.core.render.IPPortalVisibility.keepDrawn(shaft.portalId(), 250);
            if (home.getContraption() instanceof ElevatorContraption ec && copy.getContraption() instanceof ElevatorContraption fec) {
                fec.setClientYTarget(ec.clientYTarget + shaft.shift().getY());
            }
        }
    }

    /** Puts the far copy where the car is, mapped, for this tick and the one before. */
    private static void place(final AbstractContraptionEntity home, final ControlledContraptionEntity copy, final IPElevatorShaft shaft) {
        final Vec3 target = shaft.entityToFar(home.position());
        final Vec3 prev = shaft.entityToFar(new Vec3(home.xo, home.yo, home.zo));
        ((IPElevatorFarCopy) copy).ip$setTargets(target, prev);
        applyTargets(copy);
        copy.setContraptionMotion(shaft.turn(home.getDeltaMovement()));
        if (home.getContraption() instanceof ElevatorContraption ec && copy.getContraption() instanceof ElevatorContraption fec) {
            fec.arrived = ec.arrived; // a copy of an arrived car is at rest too (MixinControlledContraptionEntity_ElevatorAtRest)
        }
    }

    /** Called at the end of a far copy's own tick too, which reset its previous position. */
    public static void applyTargets(final AbstractContraptionEntity copy) {
        final IPElevatorFarCopy far = (IPElevatorFarCopy) copy;
        final Vec3 target = far.ip$target();
        final Vec3 prev = far.ip$prevTarget();
        if (target == null || prev == null) {
            return;
        }
        copy.setPos(target.x, target.y, target.z);
        turnLikeItsPortal(copy);
        copy.xo = prev.x;
        copy.yo = prev.y;
        copy.zo = prev.z;
        copy.xOld = prev.x;
        copy.yOld = prev.y;
        copy.zOld = prev.z;
    }

    /**
     * A far copy through a turned portal is turned as its portal turns: Create's own rotation of a controlled contraption
     * (about y, by the angle that matches), this tick and the one before, so it never swings as it is drawn.
     */
    private static void turnLikeItsPortal(final AbstractContraptionEntity copy) {
        final IPElevatorFarCopy.FarInfo info = farInfo(copy);
        if (info == null || info.shaft().turns() == 0 || !(copy instanceof ControlledContraptionEntity controlled)) {
            return;
        }
        final float angle = info.shaft().createAngle();
        if (controlled.getRotationAxis() != net.minecraft.core.Direction.Axis.Y) {
            controlled.setRotationAxis(net.minecraft.core.Direction.Axis.Y);
        }
        controlled.setAngle(angle);
        ((IPControlledContraptionAccessor) controlled).ip_setPrevAngle(angle);
    }

    /** End of a far copy's tick: keep its place; on the server drop it once its car stopped placing it. */
    public static void onFarCopyTicked(final AbstractContraptionEntity copy) {
        applyTargets(copy);
        if (copy.level() instanceof ServerLevel level
            && level.getServer().getTickCount() - ((IPElevatorFarCopy) copy).ip$lastPush() > STALE_TICKS) {
            LOGGER.info("[IPElevator] far copy {} in {}: its car no longer places it, removed", copy.getId(),
                level.dimension().location());
            copy.discard();
        }
    }

    private static @Nullable ControlledContraptionEntity makeCopy(final ServerLevel level, final ServerLevel far,
                                                                  final ElevatorPulleyBlockEntity pulley,
                                                                  final AbstractContraptionEntity home, final int index,
                                                                  final IPElevatorShaft shaft, final double bottomPlane) {
        final CompoundTag tag = new CompoundTag();
        home.saveWithoutId(tag);
        tag.remove("UUID");
        tag.remove("ControllerRelative");
        tag.remove("Passengers");
        final ControlledContraptionEntity copy = AllEntityTypes.CONTROLLED_CONTRAPTION.create(far);
        if (copy == null) {
            return null;
        }
        copy.load(tag);
        final Contraption contraption = copy.getContraption();
        if (contraption == null || home.getContraption() == null) {
            return null;
        }
        contraption.anchor = shaft.blockToFar(home.getContraption().anchor);
        contraption.getSeatMapping().clear(); // the car's riders sit on the car until handed over

        if (contraption instanceof ElevatorContraption fec && home.getContraption() instanceof ElevatorContraption hec) {
            // its column, relative to its anchor, is the far column (turned with the portal)
            final ElevatorColumn.ColumnCoords farColumn = farColumn(hec.getGlobalColumn(), shaft);
            ((qouteall.imm_ptl.core.compat.mixin_create.IPElevatorContraptionAccessor) fec).ip_setColumn(new ElevatorColumn.ColumnCoords(
                farColumn.x() - contraption.anchor.getX(), farColumn.z() - contraption.anchor.getZ(), farColumn.side()));
            fec.maxContactY += shaft.shift().getY();
            fec.minContactY += shaft.shift().getY();
        }
        ((IPElevatorFarCopy) copy).ip$setFarInfo(new IPElevatorFarCopy.FarInfo(level.dimension(), pulley.getBlockPos(), index, shaft,
            bottomPlane));
        final Vec3 at = shaft.entityToFar(home.position());
        copy.setPos(at.x, at.y, at.z);
        turnLikeItsPortal(copy); // before it is sent: its spawn data carries the turn
        ((IPElevatorFarCopy) copy).ip$setLastPush(level.getServer().getTickCount());
        if (!far.addFreshEntity(copy)) {
            return null;
        }
        SERVER_COPIES.computeIfAbsent(level.dimension(), k -> new HashMap<>()).put(new CopyKey(pulley.getBlockPos().immutable(), index), copy);
        LOGGER.info("[IPElevator] pulley {} in {}: far copy {} of car {} past portal {} of {} made in {} at {}", pulley.getBlockPos().toShortString(),
            level.dimension().location(), copy.getId(), home.getId(), index + 1, ((IPElevatorPulley) pulley).ip$chain().size(),
            far.dimension().location(), at);
        return copy;
    }

    /**
     * The far copy standing for a pulley, if it is alive in a live world. The tables are static and outlive a world: a
     * singleplayer world left and joined again runs a new server in the same game, and a copy kept from the old one stood
     * in a world no longer there, placed every tick but drawn nowhere, while no new copy was made (the car was missing
     * past the portal); discarding it then crashed in the old world's physics. Such a copy is dropped, not discarded.
     */
    private static @Nullable ControlledContraptionEntity copyOf(final Map<ResourceKey<Level>, Map<CopyKey, ControlledContraptionEntity>> copies,
                                                               final ResourceKey<Level> homeDim, final BlockPos pulley, final int index,
                                                               final java.util.function.Predicate<Entity> live) {
        final Map<CopyKey, ControlledContraptionEntity> byKey = copies.get(homeDim);
        final CopyKey key = new CopyKey(pulley, index);
        final ControlledContraptionEntity copy = byKey == null ? null : byKey.get(key);
        if (copy != null && (copy.isRemoved() || !live.test(copy))) {
            byKey.remove(key);
            return null;
        }
        return copy;
    }

    /** Whether a server-side copy belongs to this server (not to one a singleplayer game ran before). */
    private static java.util.function.Predicate<Entity> ofServer(final ServerLevel level) {
        return copy -> copy.getServer() == level.getServer();
    }

    private static void removeCopy(final ServerLevel level, final BlockPos pulley, final int index, final String why) {
        final ResourceKey<Level> homeDim = level.dimension();
        final Map<CopyKey, ControlledContraptionEntity> byKey = SERVER_COPIES.get(homeDim);
        final ControlledContraptionEntity copy = byKey == null ? null : byKey.remove(new CopyKey(pulley, index));
        if (copy != null && !copy.isRemoved() && ofServer(level).test(copy)) {
            LOGGER.info("[IPElevator] pulley {} in {}: far copy {} past portal {} removed ({})", pulley.toShortString(), homeDim.location(),
                copy.getId(), index + 1, why);
            copy.discard();
        }
    }

    /** Removes every far copy of a pulley. */
    private static void removeCopies(final ServerLevel level, final BlockPos pulley, final String why) {
        final Map<CopyKey, ControlledContraptionEntity> byKey = SERVER_COPIES.get(level.dimension());
        if (byKey == null || byKey.isEmpty()) {
            return;
        }
        for (final CopyKey key : List.copyOf(byKey.keySet())) {
            if (key.pulley().equals(pulley)) {
                removeCopy(level, pulley, key.index(), why);
            }
        }
    }

    /** Client: the far copies known on this client, by home dimension, pulley and portal. */
    static Map<ResourceKey<Level>, Map<CopyKey, ControlledContraptionEntity>> clientCopies() {
        return CLIENT_COPIES;
    }

    /** Puts a far copy where its car is, mapped, this tick and the one before (no motion: not a tick). */
    static void placeFrom(final AbstractContraptionEntity home, final ControlledContraptionEntity copy, final IPElevatorShaft shaft) {
        ((IPElevatorFarCopy) copy).ip$setTargets(shaft.entityToFar(home.position()), shaft.entityToFar(new Vec3(home.xo, home.yo, home.zo)));
        applyTargets(copy);
    }

    /** Client: a far copy arrived from the server; its car's pulley places it from now on. */
    public static void onClientFarCopy(final ControlledContraptionEntity copy, final IPElevatorFarCopy.FarInfo info) {
        CLIENT_COPIES.computeIfAbsent(info.homeDim(), k -> new HashMap<>()).put(new CopyKey(info.pulleyPos(), info.index()), copy);
        // its floors: the server sends them only when they change, and a far copy has no pulley here to ask for them
        net.createmod.catnip.platform.CatnipServices.NETWORK.sendToServer(
            new com.simibubi.create.content.contraptions.elevator.ElevatorFloorListPacket.RequestFloorList(copy));
    }

    /** Test hook ({@code /ip_aeronautics straddle debug elevator}): a click on an elevator pulley. */
    public static boolean debugClick(final ServerLevel level, final BlockPos pos) {
        if (!(level.getBlockEntity(IPElevatorFrames.blockAt(level, pos)) instanceof ElevatorPulleyBlockEntity pulley)) {
            return false;
        }
        pulley.clicked();
        return true;
    }

    /**
     * Test hook ({@code /ip_aeronautics straddle debug elevator <pulley> <floor>}): picks the {@code floor}-th floor from
     * the bottom of the car's list, as the car's controls do. {@code pos} may be a world position over a ship's pulley.
     *
     * @return what was done, or null if there is no running elevator there
     */
    public static @Nullable String debugTarget(final ServerLevel level, final BlockPos pos, final int floor) {
        final BlockPos at = IPElevatorFrames.blockAt(level, pos);
        if (!(level.getBlockEntity(at) instanceof ElevatorPulleyBlockEntity pulley) || pulley.movedContraption == null
            || !(pulley.movedContraption.getContraption() instanceof ElevatorContraption ec)
            || floor < 0 || floor >= ec.namesList.size()) {
            return null;
        }
        final int homeY = ec.namesList.get(floor).getFirst();
        callFloor(level, pulley, ec, homeY);
        return "pulley " + at.toShortString() + ": floor " + floor + " (" + ec.namesList.get(floor).getSecond().getFirst()
            + ") at y " + homeY + " of " + ec.namesList.size();
    }

    // ---- what a car or far copy is

    public static IPElevatorFarCopy.@Nullable FarInfo farInfo(final Entity entity) {
        return entity instanceof IPElevatorFarCopy far ? far.ip$farInfo() : null;
    }

    /** The shaft of the elevator this contraption is the home car of, or null. */
    public static @Nullable IPElevatorShaft homeShaft(final AbstractContraptionEntity entity) {
        if (!(entity instanceof IPControlledContraptionAccessor controlled) || farInfo(entity) != null
            || !(entity.getContraption() instanceof ElevatorContraption)) {
            return null;
        }
        final BlockPos controller = controlled.ip_getControllerPos();
        if (controller == null || !entity.level().isLoaded(controller)
            || !(entity.level().getBlockEntity(controller) instanceof ElevatorPulleyBlockEntity pulley)
            || pulley.movedContraption != entity) {
            return null;
        }
        return ((IPElevatorPulley) pulley).ip$shaft();
    }

    /**
     * Which side of which plane of a car or far copy is drawn: the home car above its shaft's first plane, a far copy
     * below its own, or above the next portal's plane when it reaches past that one instead (never both: the shaft's
     * portals are at least the car's height apart).
     */
    public record Keep(double planeY, boolean above) {
        public boolean keeps(final double y) {
            return above ? y >= planeY : y <= planeY;
        }
    }

    /** The cut a car or far copy is drawn with, or null if it is not one (or the car is wholly on its side). */
    public static @Nullable Keep keep(final Entity entity) {
        final IPElevatorFarCopy.FarInfo far = farInfo(entity);
        if (far != null) {
            final AABB box = entity.getBoundingBox();
            if (far.hasBottom() && box.minY < far.farBottomPlaneY() && box.maxY <= far.shaft().farPlaneY()) {
                return new Keep(far.farBottomPlaneY(), true);
            }
            return new Keep(far.shaft().farPlaneY(), false);
        }
        if (!(entity instanceof AbstractContraptionEntity contraption)) {
            return null;
        }
        final IPElevatorShaft shaft = homeShaft(contraption);
        return shaft == null || contraption.getBoundingBox().minY >= shaft.planeY() ? null : new Keep(shaft.planeY(), true);
    }

    /**
     * Whether a block a car or far copy moves into (in its own frame) is not on its drawn side of its plane: the blocks
     * there belong to the other level. A block the plane cuts counts as past it.
     */
    public static boolean isPastPlane(final AbstractContraptionEntity entity, final BlockPos pos) {
        final Keep keep = keep(entity);
        if (keep == null) {
            return false;
        }
        final double centre = pos.getY() + 0.5;
        return keep.above() ? centre <= keep.planeY() : centre >= keep.planeY();
    }

    /**
     * Whether a car or far copy leaves this entity alone: it is wholly behind a portal on the copy's side (the part of
     * the car there belongs to the other level). Entities straddling a plane collide with both.
     */
    public static boolean skipsCollision(final AbstractContraptionEntity contraption, final Entity other) {
        final IPElevatorFarCopy.FarInfo far = farInfo(contraption);
        final IPElevatorShaft shaft = far != null ? null : homeShaft(contraption);
        if (far == null && shaft == null) {
            return false;
        }
        // the entity's box in the contraption's frame (a ship's plot for a contraption on a ship)
        final double[] y = IPElevatorFrames.frameYRange(contraption, other.getBoundingBox());
        if (far != null) {
            return y[0] >= far.shaft().farPlaneY() - EPS || (far.hasBottom() && y[1] <= far.farBottomPlaneY() + EPS);
        }
        return y[1] <= shaft.planeY() + EPS;
    }

    /** Whether this is an elevator car's far copy (any object; false for anything else). */
    public static boolean isFarCopy(final Object contraption) {
        return contraption instanceof Entity entity && farInfo(entity) != null;
    }

    /**
     * Whether Sable's physics leaves this contraption without a collider: an elevator car on a ship with a shaft
     * through a portal, or a far copy on a ship. A contraption on a ship pushes its ship, and past the plane the car is
     * inside whatever lies behind the portal in its own level (the ground under a ship whose portal is near it).
     */
    public static boolean hasNoCollider(final Object contraption) {
        return contraption instanceof AbstractContraptionEntity entity && IPElevatorFrames.isOnShip(entity)
            && (farInfo(entity) != null || homeShaft(entity) != null);
    }

    /**
     * Server: a player opened a storage block (chest, barrel, vault) on a far copy: the car's own storage at that block
     * opens instead, its menu kept valid by the far copy the player is next to, so both sides edit the same items (the far
     * copy's own copy of them is never opened). False when the car is not found here.
     */
    public static boolean openCarStorage(final AbstractContraptionEntity copy, final net.minecraft.server.level.ServerPlayer player,
                                         final BlockPos localPos) {
        final IPElevatorFarCopy.FarInfo far = farInfo(copy);
        final ServerLevel homeLevel = far == null ? null : player.server.getLevel(far.homeDim());
        if (homeLevel == null || !homeLevel.isLoaded(far.pulleyPos())
            || !(homeLevel.getBlockEntity(far.pulleyPos()) instanceof ElevatorPulleyBlockEntity pulley)
            || pulley.movedContraption == null || pulley.movedContraption.getContraption() == null || copy.getContraption() == null) {
            return false;
        }
        final var storage = pulley.movedContraption.getContraption().getStorage().getAllItemStorages().get(localPos);
        final var info = copy.getContraption().getBlocks().get(localPos);
        return storage != null && info != null && storage.handleInteraction(player, copy.getContraption(), info);
    }

    /** Whether a click on the pulley to stop it is refused: blocks past the plane would be placed behind the portal. */
    public static boolean refusesStop(final ElevatorPulleyBlockEntity pulley) {
        final IPElevatorShaft shaft = ((IPElevatorPulley) pulley).ip$shaft();
        final AbstractContraptionEntity home = pulley.movedContraption;
        if (!pulley.running || shaft == null || home == null || home.getBoundingBox().minY >= shaft.planeY() - EPS) {
            return false;
        }
        ((IPLinearActuatorAccessor) pulley).ip_setLastException(new AssemblyException(
            Component.literal("The car is partly through a portal: bring it to a floor on the pulley's side to stop it")));
        pulley.sendData();
        return true;
    }

    // ---- floors

    static ElevatorColumn.ColumnCoords farColumn(final ElevatorColumn.ColumnCoords column, final IPElevatorShaft shaft) {
        final BlockPos far = shaft.blockToFar(new BlockPos(column.x(), 0, column.z()));
        return new ElevatorColumn.ColumnCoords(far.getX(), far.getZ(), shaft.directionToFar(column.side()));
    }

    /**
     * Links the pulley's column and the column past each portal of its shaft into one group, refreshed every tick. A
     * new link turns the redstone contacts of the far columns into elevator contacts, as Create does in the pulley's own
     * column when the elevator is assembled, and has every column's floor list compiled again.
     */
    private static void link(final ServerLevel home, final ElevatorColumn.ColumnCoords homeColumn, final List<IPElevatorShaft> chain,
                             final long now) {
        // every column of the group, dy from the pulley's frame
        final List<Member> all = new ArrayList<>();
        all.add(new Member(home, homeColumn, 0, true));
        for (final IPElevatorShaft shaft : chain) {
            final ServerLevel far = home.getServer().getLevel(shaft.farDim());
            final Member member = far == null ? null : new Member(far, farColumn(homeColumn, shaft), shaft.shift().getY(), false);
            // a column met twice (a portal into the same column of the same level) is linked once
            if (member != null && all.stream().noneMatch(m -> m.level() == member.level() && m.coords().equals(member.coords()))) {
                all.add(member);
            }
        }
        boolean fresh = false;
        for (final Member self : all) {
            final List<Member> others = new ArrayList<>();
            for (final Member m : all) {
                if (m != self) {
                    others.add(new Member(m.level(), m.coords(), m.dy() - self.dy(), m.home()));
                }
            }
            final Map<ElevatorColumn.ColumnCoords, Link> byCoords = LINKS.computeIfAbsent(self.level(), k -> new HashMap<>());
            final Link before = byCoords.get(self.coords());
            fresh |= before == null || before.until() < now || !before.others().equals(others);
            byCoords.put(self.coords(), new Link(List.copyOf(others), now + LINK_TICKS));
        }
        if (!fresh) {
            return;
        }
        mirroring = true;
        try {
            for (final Member member : all) {
                if (!member.home()) {
                    final ElevatorColumn farOne = ElevatorColumn.getOrCreate(member.level(), member.coords());
                    farOne.gatherAll();
                    farOne.namesListVersion++;
                }
            }
            final ElevatorColumn homeOne = ElevatorColumn.get(home, homeColumn);
            if (homeOne != null) {
                homeOne.namesListVersion++;
            }
        } finally {
            mirroring = false;
        }
        final ElevatorColumn homeOne = ElevatorColumn.get(home, homeColumn);
        if (homeOne != null) {
            initNamesMerged(homeOne);
        }
        final StringBuilder text = new StringBuilder();
        for (final Member member : all.subList(1, all.size())) {
            text.append(text.isEmpty() ? "" : ", ").append("column ").append(member.coords().x()).append(' ').append(member.coords().z())
                .append(" in ").append(member.level().dimension().location());
        }
        LOGGER.info("[IPElevator] column {} {} in {} linked to {}", homeColumn.x(), homeColumn.z(), home.dimension().location(), text);
    }

    /** The group linked to this column, or null. */
    public static @Nullable Link linkOf(final ElevatorColumn column) {
        final IPElevatorColumnAccessor access = (IPElevatorColumnAccessor) column;
        if (!(access.ip_getLevel() instanceof ServerLevel level)) {
            return null;
        }
        final Map<ElevatorColumn.ColumnCoords, Link> byCoords = LINKS.get(level);
        final Link link = byCoords == null ? null : byCoords.get(access.ip_getCoords());
        if (link != null && link.until() < level.getServer().getTickCount()) {
            byCoords.remove(access.ip_getCoords());
            return null;
        }
        return link;
    }

    private static @Nullable ElevatorColumn column(final Member member) {
        return ElevatorColumn.get(member.level(), member.coords());
    }

    /** This column's floors and the linked columns', in this column's frame, by height. */
    public static List<IntAttached<Couple<String>>> mergedNames(final ElevatorColumn column, final List<IntAttached<Couple<String>>> own) {
        final Link link = mirroring ? null : linkOf(column);
        if (link == null) {
            return own;
        }
        final List<IntAttached<Couple<String>>> merged = new ArrayList<>(own);
        mirroring = true;
        try {
            for (final Member member : link.others()) {
                final ElevatorColumn other = column(member);
                if (other != null) {
                    for (final IntAttached<Couple<String>> floor : other.compileNamesList()) {
                        merged.add(IntAttached.with(floor.getFirst() - member.dy(), floor.getSecond()));
                    }
                }
            }
        } finally {
            mirroring = false;
        }
        merged.sort((a, b) -> Integer.compare(a.getFirst(), b.getFirst()));
        return merged;
    }

    /** Runs {@code call} on each linked column there is, without mirroring it back. */
    public static void mirror(final ElevatorColumn column, final java.util.function.BiConsumer<ElevatorColumn, Member> call) {
        final Link link = mirroring ? null : linkOf(column);
        if (link == null) {
            return;
        }
        mirroring = true;
        try {
            for (final Member member : link.others()) {
                final ElevatorColumn other = column(member);
                if (other != null) {
                    call.accept(other, member);
                }
            }
        } finally {
            mirroring = false;
        }
    }

    /**
     * A column of a group was given a target: the others get the same floor in their own frames. The car reads the
     * home column; the far columns must follow it too, since each contact saves its column's target and restores it on
     * load (a far column left at an old far floor called the car back there after a reload).
     */
    public static void onTarget(final ElevatorColumn column, final int y) {
        final Link link = mirroring ? null : linkOf(column);
        if (link == null) {
            return;
        }
        mirroring = true;
        try {
            for (final Member member : link.others()) {
                final ElevatorColumn other = member.home() ? ElevatorColumn.getOrCreate(member.level(), member.coords()) : column(member);
                if (other != null) {
                    other.target(y + member.dy());
                }
            }
        } finally {
            mirroring = false;
        }
    }

    /**
     * Numbers the floors of a linked group as one column, the way Create numbers one column ({@code ElevatorColumn.initNames}):
     * from the bottom up, keeping names that are not numbers, counting on from a number already given. Numbered per
     * column, the lowest floor on each side was "0", and the car's controls showed the same name for both. False when the
     * column is not linked (Create numbers it).
     */
    public static boolean initNamesMerged(final ElevatorColumn column) {
        final Link link = mirroring ? null : linkOf(column);
        if (link == null) {
            return false;
        }
        final List<IntAttached<ElevatorContactBlockEntity>> floors = new ArrayList<>();
        collectFloors(column, 0, floors);
        for (final Member member : link.others()) {
            final ElevatorColumn other = column(member);
            if (other != null) {
                collectFloors(other, -member.dy(), floors);
            }
        }
        floors.sort((a, b) -> Integer.compare(a.getFirst(), b.getFirst()));
        mirroring = true;
        try {
            Integer prevLevel = null;
            for (int i = 0; i < floors.size(); i++) {
                final ElevatorContactBlockEntity contact = floors.get(i).getSecond();
                Integer currentLevel = null;
                if (!contact.shortName.isBlank()) {
                    currentLevel = number(contact.shortName);
                    if (currentLevel == null) {
                        continue;
                    }
                }
                if (prevLevel != null) {
                    currentLevel = prevLevel + 1;
                }
                Integer nextLevel = null;
                for (int peek = i + 1; peek < floors.size(); peek++) {
                    final ElevatorContactBlockEntity next = floors.get(peek).getSecond();
                    final Integer value = number(next.shortName);
                    if (value != null) {
                        if (currentLevel != null && currentLevel >= value) {
                            next.shortName = "";
                        } else {
                            nextLevel = value;
                        }
                        break;
                    }
                }
                if (currentLevel == null) {
                    currentLevel = nextLevel != null ? nextLevel - 1 : 0;
                }
                contact.updateName(String.valueOf(currentLevel), contact.longName);
                prevLevel = currentLevel;
            }
        } finally {
            mirroring = false;
        }
        return true;
    }

    /** A column's contacts with their heights moved by {@code dy} into the frame they are numbered in. */
    private static void collectFloors(final ElevatorColumn column, final int dy, final List<IntAttached<ElevatorContactBlockEntity>> out) {
        final LevelAccessor level = ((IPElevatorColumnAccessor) column).ip_getLevel();
        for (final BlockPos pos : column.getContacts()) {
            if (level.getBlockEntity(pos) instanceof ElevatorContactBlockEntity contact) {
                out.add(IntAttached.with(pos.getY() + dy, contact));
            }
        }
    }

    private static @Nullable Integer number(final String name) {
        try {
            return Integer.valueOf(name, 10);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A floor was called: the other columns' contacts stop showing a call, as Create does for the rest of one column. */
    public static void onCalled(final ElevatorColumn column) {
        mirror(column, (other, member) -> {
            for (final BlockPos pos : other.getContacts()) {
                final BlockState state = member.level().getBlockState(pos);
                if (AllBlocks.ELEVATOR_CONTACT.has(state) && state.getValue(ElevatorContactBlock.CALLING)) {
                    member.level().setBlock(pos, state.setValue(ElevatorContactBlock.CALLING, false), 18);
                    ((ElevatorContactBlock) AllBlocks.ELEVATOR_CONTACT.get()).scheduleActivation(member.level(), pos);
                }
            }
        });
    }

    /**
     * The car arrived at a floor past a plane: light that far contact as Create lights the home one. False when the
     * floor is on the pulley's side (Create handles it).
     */
    public static boolean triggerFarContact(final ElevatorPulleyBlockEntity pulley, final ElevatorContraption ec, final int targetLevel) {
        final List<IPElevatorShaft> chain = ((IPElevatorPulley) pulley).ip$chain();
        final int contactY = targetLevel + ec.getContactYOffset();
        if (chain.isEmpty() || contactY >= chain.get(0).planeY() || !(pulley.getLevel() instanceof ServerLevel level)) {
            return false;
        }
        IPElevatorShaft shaft = chain.get(0);
        for (final IPElevatorShaft below : chain) {
            if (contactY < below.planeY()) {
                shaft = below; // the lowest portal above the contact: the contact is in the level past it
            }
        }
        final ServerLevel far = level.getServer().getLevel(shaft.farDim());
        if (far == null) {
            return true;
        }
        final ElevatorColumn.ColumnCoords column = farColumn(ec.getGlobalColumn(), shaft);
        final BlockPos farPos = new BlockPos(column.x(), contactY + shaft.shift().getY(), column.z());
        if (far.isLoaded(farPos)) {
            final BlockState state = far.getBlockState(farPos);
            if (AllBlocks.ELEVATOR_CONTACT.has(state) && !state.getValue(ElevatorContactBlock.POWERING)) {
                final ElevatorContactBlock block = (ElevatorContactBlock) AllBlocks.ELEVATOR_CONTACT.get();
                block.withBlockEntityDo(far, farPos, be -> be.activateBlock = true);
                block.scheduleActivation(far, farPos);
            }
        }
        return true;
    }

    /**
     * A floor picked on the car's controls ({@code ElevatorTargetFloorPacket}), from the home car or a far copy, for an
     * elevator with a shaft: the floor may be in either column, and y is in the frame of the contraption clicked.
     * False when this is not such an elevator (Create handles it).
     */
    public static boolean onTargetFloor(final ServerLevel playerLevel, final Entity clicked, final int targetY, final Entity player) {
        if (!(clicked instanceof AbstractContraptionEntity ace) || !(ace.getContraption() instanceof ElevatorContraption)) {
            return false;
        }
        final IPElevatorFarCopy.FarInfo far = farInfo(ace);
        final ServerLevel homeLevel;
        final ElevatorPulleyBlockEntity pulley;
        final int homeY;
        if (far != null) {
            homeLevel = playerLevel.getServer().getLevel(far.homeDim());
            pulley = homeLevel != null && homeLevel.isLoaded(far.pulleyPos())
                && homeLevel.getBlockEntity(far.pulleyPos()) instanceof ElevatorPulleyBlockEntity p ? p : null;
            homeY = targetY - far.shaft().shift().getY();
        } else {
            if (homeShaft(ace) == null) {
                return false;
            }
            homeLevel = playerLevel;
            pulley = (ElevatorPulleyBlockEntity) playerLevel.getBlockEntity(((IPControlledContraptionAccessor) ace).ip_getControllerPos());
            homeY = targetY;
        }
        if (pulley == null || homeLevel == null || IPElevatorFrames.distanceSqr(playerLevel, ace.position(), player.position()) > 2500.0
            || !(pulley.movedContraption != null && pulley.movedContraption.getContraption() instanceof ElevatorContraption ec)
            || ec.isTargetUnreachable(homeY)) {
            return true;
        }
        callFloor(homeLevel, pulley, ec, homeY);
        return true;
    }

    /** Calls the car to the floor at {@code homeY} in the pulley's frame, in whichever column it is. */
    private static void callFloor(final ServerLevel homeLevel, final ElevatorPulleyBlockEntity pulley, final ElevatorContraption ec,
                                  final int homeY) {
        final ElevatorColumn homeColumn = ElevatorColumn.get(homeLevel, ec.getGlobalColumn());
        if (homeColumn != null && ((IPElevatorColumnAccessor) homeColumn).ip_getContacts().contains(homeY)) {
            call(homeColumn, homeLevel, homeColumn.contactAt(homeY));
            return;
        }
        for (final IPElevatorShaft shaft : ((IPElevatorPulley) pulley).ip$chain()) {
            final ServerLevel farLevel = homeLevel.getServer().getLevel(shaft.farDim());
            final ElevatorColumn farColumn = farLevel == null ? null : ElevatorColumn.get(farLevel, farColumn(ec.getGlobalColumn(), shaft));
            final int farY = homeY + shaft.shift().getY();
            if (farColumn != null && ((IPElevatorColumnAccessor) farColumn).ip_getContacts().contains(farY)) {
                call(farColumn, farLevel, farColumn.contactAt(farY));
                return;
            }
        }
    }

    private static void call(final ElevatorColumn column, final Level level, final BlockPos pos) {
        final BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ElevatorContactBlock block) {
            block.callToContactAndUpdate(column, state, level, pos, false);
        }
    }
}
