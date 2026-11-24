/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
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
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalPieceKind;


/**
 * Create's parts as kinds facing a portal ({@link IPPortalPieceKind}): who owns the space between the two sides at a
 * spot, belts, then shafts, then pipes, then tracks. Without an owner a sideways belt's pulley across the portal drew
 * its half shaft through the portal into the half where the near belt runs its items.
 */
public final class IPCreatePortalEnds {
    public static final IPPortalPieceKind TRACK = IPPortalPieceKind.register("create:track", 100, (level, pos, state, side) ->
        state.getBlock() instanceof TrackBlock && side.getAxis().isHorizontal() && state.getValue(TrackBlock.SHAPE) == TrackShape.asPortal(side));
    public static final IPPortalPieceKind PIPE = IPPortalPieceKind.register("create:pipe", 200, (level, pos, state, side) -> {
        final FluidTransportBehaviour pipe = FluidPropagator.getPipe(level, pos);
        return pipe != null && pipe.canHaveFlowToward(state, side);
    });
    public static final IPPortalPieceKind SHAFT = IPPortalPieceKind.register("create:shaft", 300, (level, pos, state, side) ->
        state.getBlock() instanceof IRotate rotate && rotate.hasShaftTowards(level, pos, state, side));
    public static final IPPortalPieceKind BELT = IPPortalPieceKind.register("create:belt", 400, (level, pos, state, side) ->
        isBeltEndFacing(state, side));

    private IPCreatePortalEnds() {
    }

    /** Registers Create's kinds (this class's constants), before anything asks who owns a spot. Common setup. */
    static void init() {
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
