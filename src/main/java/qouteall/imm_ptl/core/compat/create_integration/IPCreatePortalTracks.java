/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.AllTags;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackShape;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

/**
 * Create tracks through IP portals. Create's own linking ({@code TrackBlock.connectToPortal}) places the exit track at
 * the other end itself and binds the pair so that breaking either end destroys the other without a drop. Here the
 * player lays the track at each end and we only add what they cannot place: the piece reaching into the portal
 * (Create's portal track shape), as for pipes and shafts through portals.
 * <ul>
 *     <li>A straight track facing a portal block becomes such a bridge piece at once. If the other end's
 *     {@link IPCreatePortalTrackProvider#exitSpot} holds a straight track along the same line, or a bridge piece of
 *     ours waiting there, the two are linked and trains cross; until then the bridge is a dead end at the portal.</li>
 *     <li>Breaking one end of a linked pair leaves the other a waiting bridge; its portal going away turns a bridge back
 *     into the ordinary track it was (block entity and curves kept).</li>
 *     <li>Tracks laid up to a portal before it opened are linked when its portal blocks appear
 *     ({@link #onPortalBlockPlaced}).</li>
 * </ul>
 * Pairs Create linked itself before (an exit track it placed for free) keep Create's behaviour, so no free track turns
 * into a drop. A pair within one dimension becomes a portal edge in Create's graph ({@link #onGraphWalk}). An end may be
 * on a ship (a portal on a ship): it lies in the ship's plot, and a pair stays linked when Sable moves an end into a
 * plot or out of one ({@link #beforeBlocksMoved}, {@link #afterBlocksMoved}). Track and portal move as one, so the pair
 * needs nothing else while the ship flies.
 */
public final class IPCreatePortalTracks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPCreatePortalTracks] ";
    /** A bridge piece of ours (a portal track whose ends were laid by a player), linked or waiting. */
    private static final String OWN = "ip_aeronautics:own_portal_track";

    /** An end of a pair being moved by Sable: where it goes, and the end it is bound to. */
    private record Moving(ServerLevel to, BlockPos toPos, ResourceKey<Level> boundDimension, BlockPos boundPos) {
    }

    /** The ends of ours in the current {@code moveBlocks}, by where they stood. Server thread. */
    private static final Map<BlockPos, Moving> MOVING = new HashMap<>();
    private static ResourceKey<Level> movingFrom;

    private IPCreatePortalTracks() {
    }

    /** Sable is about to move {@code blocks}: notes every end of ours among them. */
    public static void beforeBlocksMoved(final ServerLevel level, final Iterable<BlockPos> blocks, final java.util.function.UnaryOperator<BlockPos> moved,
                                         final ServerLevel to) {
        MOVING.clear();
        movingFrom = level.dimension();
        for (final BlockPos block : blocks) {
            final BlockState state = level.getBlockState(block);
            if (state.getBlock() instanceof TrackBlock && state.getValue(TrackBlock.SHAPE).isPortal() && isOwn(level, block)
                && level.getBlockEntity(block) instanceof final TrackBlockEntity track && track.boundLocation != null) {
                MOVING.put(block.immutable(), new Moving(to, moved.apply(block), track.boundLocation.getFirst(), track.boundLocation.getSecond()));
            }
        }
    }

    /** Sable has moved the blocks: each moved end and its other end are bound to where the other now is. */
    public static void afterBlocksMoved() {
        for (final Moving end : MOVING.values()) {
            final Moving other = end.boundDimension().equals(movingFrom) ? MOVING.get(end.boundPos()) : null;
            final ServerLevel otherLevel = other != null ? other.to() : end.to().getServer().getLevel(end.boundDimension());
            final BlockPos otherPos = other != null ? other.toPos() : end.boundPos();
            if (!(end.to().getBlockEntity(end.toPos()) instanceof final TrackBlockEntity moved) || otherLevel == null) {
                continue;
            }
            moved.bind(otherLevel.dimension(), otherPos);
            moved.getPersistentData().putBoolean(OWN, true);
            moved.setChanged();
            end.to().scheduleTick(end.toPos(), moved.getBlockState().getBlock(), 1);
            if (otherLevel.isLoaded(otherPos) && otherLevel.getBlockEntity(otherPos) instanceof final TrackBlockEntity far) {
                far.bind(end.to().dimension(), end.toPos());
                far.setChanged();
                otherLevel.scheduleTick(otherPos, far.getBlockState().getBlock(), 1);
            }
            LOGGER.info(TAG + "the track moved to {} in {} stays linked to {} in {}", end.toPos(), end.to().dimension().location(),
                otherPos, otherLevel.dimension().location());
        }
        MOVING.clear();
    }

    /** A one-sided link (an end moved before ends followed their pair, or while the other was not loaded): both ways again. */
    private static void mendLink(final ServerLevel level, final BlockPos pos, final TrackBlockEntity track) {
        final ServerLevel otherLevel = level.getServer().getLevel(track.boundLocation.getFirst());
        final BlockPos otherPos = track.boundLocation.getSecond();
        if (otherLevel == null || !otherLevel.isLoaded(otherPos) || !isOwn(otherLevel, otherPos)
            || !(otherLevel.getBlockEntity(otherPos) instanceof final TrackBlockEntity other)) {
            return;
        }
        if (other.boundLocation != null) {
            final ServerLevel boundLevel = level.getServer().getLevel(other.boundLocation.getFirst());
            if ((other.boundLocation.getSecond().equals(pos) && other.boundLocation.getFirst().equals(level.dimension()))
                || (boundLevel != null && boundLevel.isLoaded(other.boundLocation.getSecond())
                && boundLevel.getBlockState(other.boundLocation.getSecond()).getBlock() instanceof TrackBlock)) {
                return;
            }
        }
        other.bind(level.dimension(), pos);
        other.setChanged();
        otherLevel.scheduleTick(otherPos, other.getBlockState().getBlock(), 1);
        LOGGER.info(TAG + "the portal track at {} in {} is linked to {} in {} again", otherPos, otherLevel.dimension().location(), pos, level.dimension().location());
    }

    /**
     * {@code TrackBlock.connectToPortal} for a straight track: returns whether a portal block is in front of it, in
     * which case it was handled here and Create's own linking is skipped.
     */
    public static boolean connect(final ServerLevel level, final BlockPos pos, final BlockState state) {
        final TrackShape shape = state.getValue(TrackBlock.SHAPE);
        final Direction.Axis axis = shape == TrackShape.XO ? Direction.Axis.X : shape == TrackShape.ZO ? Direction.Axis.Z : null;
        if (axis == null) {
            return false;
        }
        for (final Direction.AxisDirection sign : Direction.AxisDirection.values()) {
            final Direction toPortal = Direction.get(sign, axis);
            if (level.getBlockState(pos.relative(toPortal)).getBlock() != PortalPlaceholderBlock.instance) {
                continue;
            }
            final IPCreatePortalTrackProvider.ExitSpot spot = IPCreatePortalTrackProvider.exitSpot(level, new BlockFace(pos, toPortal));
            final BlockState other = spot == null ? null : spot.level().getBlockState(spot.pos());
            if (spot != null && isLinkable(spot.level(), spot.pos(), other, spot.towardPortal())) {
                link(level, pos, state, toPortal, spot, other);
            } else {
                bridge(level, pos, state, toPortal);
            }
            return true;
        }
        return false;
    }

    /** A straight track along the line through the portal, or a waiting bridge of ours facing it. */
    private static boolean isLinkable(final ServerLevel level, final BlockPos pos, final BlockState state, final Direction towardPortal) {
        if (!(state.getBlock() instanceof TrackBlock)) {
            return false;
        }
        final TrackShape shape = state.getValue(TrackBlock.SHAPE);
        if (shape == (towardPortal.getAxis() == Direction.Axis.X ? TrackShape.XO : TrackShape.ZO)) {
            return true;
        }
        return shape == TrackShape.asPortal(towardPortal) && isOwn(level, pos)
            && level.getBlockEntity(pos) instanceof final TrackBlockEntity track && track.boundLocation == null;
    }

    /** The track at {@code pos} becomes a waiting bridge into the portal. */
    private static void bridge(final ServerLevel level, final BlockPos pos, final BlockState state, final Direction toPortal) {
        level.setBlock(pos, state.setValue(TrackBlock.SHAPE, TrackShape.asPortal(toPortal)).setValue(TrackBlock.HAS_BE, true), Block.UPDATE_ALL);
        if (level.getBlockEntity(pos) instanceof final TrackBlockEntity track) {
            track.getPersistentData().putBoolean(OWN, true);
            track.setChanged();
        }
        LOGGER.info(TAG + "the track at {} in {} reaches into the portal, waiting for a track at the other end",
            pos, level.dimension().location());
    }

    private static void link(
        final ServerLevel level, final BlockPos pos, final BlockState state, final Direction toPortal,
        final IPCreatePortalTrackProvider.ExitSpot spot, final BlockState other
    ) {
        level.setBlock(pos, state.setValue(TrackBlock.SHAPE, TrackShape.asPortal(toPortal)).setValue(TrackBlock.HAS_BE, true), Block.UPDATE_ALL);
        final BlockState otherBridge = other.setValue(TrackBlock.SHAPE, TrackShape.asPortal(spot.towardPortal())).setValue(TrackBlock.HAS_BE, true);
        final boolean otherWasBridge = other == otherBridge;
        spot.level().setBlock(spot.pos(), otherBridge, Block.UPDATE_ALL);
        if (!(level.getBlockEntity(pos) instanceof final TrackBlockEntity near)
            || !(spot.level().getBlockEntity(spot.pos()) instanceof final TrackBlockEntity far)) {
            return;
        }
        near.bind(spot.level().dimension(), spot.pos());
        far.bind(level.dimension(), pos);
        near.getPersistentData().putBoolean(OWN, true);
        far.getPersistentData().putBoolean(OWN, true);
        near.setChanged();
        far.setChanged();
        if (otherWasBridge) {
            // its state is unchanged, so Create adds its new link to the graph only on a tick of its own
            spot.level().scheduleTick(spot.pos(), otherBridge.getBlock(), 1);
        }
        LOGGER.info(TAG + "linked the tracks at {} in {} and {} in {} through the portal",
            pos, level.dimension().location(), spot.pos(), spot.level().dimension().location());
    }

    /**
     * {@code TrackBlock.getConnected} (server, graph walks): at an end of ours linked within one dimension, records the
     * pair as a portal edge ({@link IPCreatePortalEdges}) and makes both portal faces graph nodes, as Create does for a
     * pair across dimensions ({@code ITrackBlock.addToListIfConnected}). Pairs tagged for Create Teleporters' own train
     * code (2.5.3-2.5.7) are left to it while it is installed.
     */
    public static void onGraphWalk(final ServerLevel level, final BlockPos pos, final BlockState state,
                                   final Collection<TrackNodeLocation.DiscoveredLocation> found) {
        if (!(state.getBlock() instanceof final TrackBlock block) || !state.getValue(TrackBlock.HAS_BE)
            || !state.getValue(TrackBlock.SHAPE).isPortal()
            || !(level.getBlockEntity(pos) instanceof final TrackBlockEntity track) || track.boundLocation == null
            || !track.getPersistentData().getBoolean(OWN)) {
            return;
        }
        mendLink(level, pos, track);
        if (!track.boundLocation.getFirst().equals(level.dimension()) || IPCreateTeleportersTrains.isLeftToIt(track)) {
            return;
        }
        final BlockPos boundPos = track.boundLocation.getSecond();
        final BlockState boundState = level.getBlockState(boundPos);
        if (!AllTags.AllBlockTags.TRACKS.matches(boundState) || !(boundState.getBlock() instanceof final TrackBlock boundBlock)) {
            return;
        }
        // the two portal faces exactly as Create's getConnected places them for a bound pair
        final TrackNodeLocation face = face(level, block, pos, state);
        final TrackNodeLocation boundFace = face(level, boundBlock, boundPos, boundState);
        IPCreatePortalEdges.add(face, boundFace);
        for (final TrackNodeLocation.DiscoveredLocation location : found) {
            if (location.equals(face) || location.equals(boundFace)) {
                location.forceNode();
            }
        }
    }

    private static TrackNodeLocation face(final ServerLevel level, final TrackBlock block, final BlockPos pos, final BlockState state) {
        final Vec3 center = Vec3.atBottomCenterOf(pos).add(0, block.getElevationAtCenter(level, pos, state), 0);
        return new TrackNodeLocation(block.getTrackAxes(level, pos, state).getFirst().scale(0.5).add(center)).in(level);
    }

    /**
     * A portal block was placed at {@code pos}: a straight track in front of it (laid before the portal opened) gets
     * its link attempt now, through Create's tick of that track.
     */
    public static void onPortalBlockPlaced(final ServerLevel level, final BlockPos pos, final Direction.Axis axis) {
        if (axis.isVertical()) {
            return;
        }
        for (final Direction.AxisDirection sign : Direction.AxisDirection.values()) {
            final BlockPos track = pos.relative(Direction.get(sign, axis));
            final BlockState state = level.getBlockState(track);
            if (state.getBlock() instanceof TrackBlock && !state.getValue(TrackBlock.SHAPE).isPortal()) {
                level.scheduleTick(track, state.getBlock(), 1);
            }
        }
    }

    /**
     * {@code TrackBlockEntity.remove} is about to destroy the other end at {@code pos} of a portal track being removed.
     * Returns whether that is handled here: an end of ours stays as a waiting bridge, and a track that is no longer a
     * portal track is left alone. Otherwise Create destroys it.
     */
    public static boolean onPartnerRemoved(final ServerLevel level, final BlockPos pos, final BlockPos removed) {
        if (MOVING.containsKey(removed)) {
            return true; // only moving: afterBlocksMoved links the pair again
        }
        final BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof TrackBlock) || !state.getValue(TrackBlock.SHAPE).isPortal()) {
            return true;
        }
        if (!isOwn(level, pos)) {
            return false;
        }
        waitAgain(level, pos, state);
        return true;
    }

    /**
     * {@code TrackBlock.updateShape} found the portal block in front of the portal track at {@code pos} gone: an end of
     * ours becomes the ordinary track it was (its linked other end a waiting bridge) and that state is returned;
     * otherwise null (Create removes it).
     */
    public static @Nullable BlockState onPortalLost(final ServerLevel level, final BlockPos pos, final BlockState state) {
        if (!isOwn(level, pos) || !(level.getBlockEntity(pos) instanceof final TrackBlockEntity track)) {
            return null;
        }
        unlinkPartner(level, pos, track);
        clear(track);
        track.getPersistentData().remove(OWN);
        LOGGER.info(TAG + "the portal at the track at {} in {} is gone: an ordinary track again", pos, level.dimension().location());
        return straight(state);
    }

    private static boolean isOwn(final ServerLevel level, final BlockPos pos) {
        return level.getBlockEntity(pos) instanceof final TrackBlockEntity track && track.getPersistentData().getBoolean(OWN);
    }

    private static void unlinkPartner(final ServerLevel level, final BlockPos pos, final TrackBlockEntity track) {
        if (track.boundLocation == null) {
            return;
        }
        final ServerLevel otherLevel = level.getServer().getLevel(track.boundLocation.getFirst());
        final BlockPos otherPos = track.boundLocation.getSecond();
        if (otherLevel == null || !otherLevel.isLoaded(otherPos)) {
            return;
        }
        final BlockState other = otherLevel.getBlockState(otherPos);
        if (other.getBlock() instanceof TrackBlock && other.getValue(TrackBlock.SHAPE).isPortal() && isOwn(otherLevel, otherPos)
            && otherLevel.getBlockEntity(otherPos) instanceof final TrackBlockEntity otherTrack
            && otherTrack.boundLocation != null && otherTrack.boundLocation.getSecond().equals(pos)
            && otherTrack.boundLocation.getFirst().equals(level.dimension())) {
            waitAgain(otherLevel, otherPos, other);
        }
    }

    /** An end of ours loses its link and waits as a bridge; Create re-adds it to the graph without the link. */
    private static void waitAgain(final ServerLevel level, final BlockPos pos, final BlockState state) {
        if (level.getBlockEntity(pos) instanceof final TrackBlockEntity track) {
            clear(track);
        }
        level.scheduleTick(pos, state.getBlock(), 1);
        LOGGER.info(TAG + "the portal track at {} in {} waits for a track at the other end again", pos, level.dimension().location());
    }

    private static void clear(final TrackBlockEntity track) {
        track.boundLocation = null;
        IPCreateTeleportersTrains.untag(track);
        track.setChanged();
    }

    private static BlockState straight(final BlockState portalTrack) {
        final Vec3 axis = portalTrack.getValue(TrackBlock.SHAPE).getAxes().getFirst();
        return portalTrack.setValue(TrackBlock.SHAPE, Math.abs(axis.x) > Math.abs(axis.z) ? TrackShape.XO : TrackShape.ZO);
    }
}
