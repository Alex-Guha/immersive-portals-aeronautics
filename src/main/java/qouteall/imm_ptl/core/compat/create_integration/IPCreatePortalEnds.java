/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/**
 * Who owns the space between the two sides of a portal at one spot. The two portal blocks there (each from its block
 * face to its plane) make one block of space between the blocks facing them, and only one thing goes through it: the
 * highest of what faces the portal on either side, by {@link Kind} (belts, then shafts, then pipes, then tracks; user,
 * 2026-10-28). The owner keeps its look and behaviour through the whole space (its own half, and a lone end's piece
 * past the plane); an outranked end draws nothing in its portal block and passes nothing through, as if it faced a
 * wall. Ends of the same kind join as before. Before 2.6.12.3 a sideways belt's pulley across the portal drew its half
 * shaft through the portal into the half where the near belt runs its items.
 */
public final class IPCreatePortalEnds {
    /** What faces a portal from the block next to it, lowest first. */
    public enum Kind {
        NONE, TRACK, PIPE, SHAFT, BELT;

        public boolean outranks(final Kind other) {
            return ordinal() > other.ordinal();
        }
    }

    private IPCreatePortalEnds() {
    }

    /** What faces {@code side} (towards the portal) at {@code pos}. */
    public static Kind kind(final Level level, final BlockPos pos, final Direction side) {
        if (!level.isLoaded(pos)) {
            return Kind.NONE;
        }
        final BlockState state = level.getBlockState(pos);
        if (isBeltEndFacing(state, side)) {
            return Kind.BELT;
        }
        if (state.getBlock() instanceof IRotate rotate && rotate.hasShaftTowards(level, pos, state, side)) {
            return Kind.SHAFT;
        }
        final FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
        if (pipe != null && pipe.canHaveFlowToward(state, side)) {
            return Kind.PIPE;
        }
        if (state.getBlock() instanceof TrackBlock && side.getAxis().isHorizontal()
            && state.getValue(TrackBlock.SHAPE) == TrackShape.asPortal(side)) {
            return Kind.TRACK;
        }
        return Kind.NONE;
    }

    /** Whether what faces the portal at {@code farPos} (towards {@code farSide}) outranks an end of kind {@code mine}. */
    public static boolean outranked(final Level farLevel, final BlockPos farPos, final Direction farSide, final Kind mine) {
        return kind(farLevel, farPos, farSide).outranks(mine);
    }

    /**
     * What faces the far portal block of a world portal block: {@code farCentre} is the near portal block's centre
     * through the portal, {@code toFarWorld} turns near directions into far ones, {@code behind} points from the near
     * end into the portal. NONE where no far portal block is there.
     */
    public static Kind farKind(final Level farWorld, final Vec3 farCentre, final Quaternionf toFarWorld, final Direction behind) {
        final BlockPos farCell = BlockPos.containing(farCentre);
        final Vector3f out = IPCreatePortalShaftHalves.snap(new Quaternionf(toFarWorld))
            .transform(new Vector3f(behind.getStepX(), behind.getStepY(), behind.getStepZ()));
        final Direction farOut = Direction.getNearest(out.x, out.y, out.z);
        if (!farWorld.isLoaded(farCell) || !IPCreatePortalPipes.isPlaceholderFacing(farWorld.getBlockState(farCell), farOut)) {
            return Kind.NONE;
        }
        return kind(farWorld, farCell.relative(farOut), farOut.getOpposite());
    }

    /** A belt whose run ends at this block's face {@code side} (an end cell's open face). */
    public static boolean isBeltEndFacing(final BlockState state, final Direction side) {
        if (!AllBlocks.BELT.has(state) || side.getAxis().isVertical()) {
            return false;
        }
        final BeltSlope slope = state.getValue(BeltBlock.SLOPE);
        if (slope == BeltSlope.VERTICAL || slope == BeltSlope.SIDEWAYS) {
            return false;
        }
        final Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        final BeltPart part = state.getValue(BeltBlock.PART);
        return part == BeltPart.END ? side == facing : part == BeltPart.START && side == facing.getOpposite();
    }
}
