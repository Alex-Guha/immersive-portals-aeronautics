/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackBlockEntity;
import com.simibubi.create.content.trains.track.TrackShape;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

import java.util.HashSet;
import java.util.Set;

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
 * into a drop.
 */
public final class IPCreatePortalTracks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPCreatePortalTracks] ";
    /** A bridge piece of ours (a portal track whose ends were laid by a player), linked or waiting. */
    private static final String OWN = "ip_aeronautics:own_portal_track";

    /** Pairs unlinked because they did not become a portal edge: not linked again this session. */
    private static final Set<GlobalPos> REFUSED = new HashSet<>();

    private IPCreatePortalTracks() {
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
            final IPCreatePortalTrackProvider.ExitSpot spot = REFUSED.contains(GlobalPos.of(level.dimension(), pos))
                ? null : IPCreatePortalTrackProvider.exitSpot(level, new BlockFace(pos, toPortal));
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
        if (spot.sameDimension() != null) {
            IPCreateTeleportersTrains.tag(level, pos, spot.pos(), spot.sameDimension());
        }
        LOGGER.info(TAG + "linked the tracks at {} in {} and {} in {} through the portal",
            pos, level.dimension().location(), spot.pos(), spot.level().dimension().location());
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
    public static boolean onPartnerRemoved(final ServerLevel level, final BlockPos pos) {
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

    /** Unlinks both ends of a pair of ours (left as waiting bridges), and never links them again this session. */
    public static void unlink(final ServerLevel level, final BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof final TrackBlockEntity track) || !isOwn(level, pos)) {
            return;
        }
        if (track.boundLocation != null) {
            REFUSED.add(GlobalPos.of(track.boundLocation.getFirst(), track.boundLocation.getSecond()));
        }
        REFUSED.add(GlobalPos.of(level.dimension(), pos));
        unlinkPartner(level, pos, track);
        waitAgain(level, pos, level.getBlockState(pos));
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
