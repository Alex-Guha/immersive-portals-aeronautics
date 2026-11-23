/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.shapes.BooleanOp;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.Map;

/**
 * The bridge pieces a player can point at: a lone piece, the part a Create part at one side of a portal draws in the
 * far portal block, past the far plane, where nothing on the far side takes it up (a half shaft, a pipe's open end, a
 * track's half). The crosshair hits it there, its outline is drawn, and a block placed against its open end goes where
 * the part would continue; middle-click picks the part. Pieces joined to a block on their own side are not here: the
 * crosshair passes them as before.
 * <p>
 * Client only, and free of Create and client classes, so {@code PortalPlaceholderBlock} may ask from common code. The
 * scans of {@link IPCreatePortalEndScan} replace each kind's pieces after every walk (main thread); the crosshair
 * raycast, the outline and pick block read them on the main thread.
 */
public final class IPCreatePortalPieceTargets {
    /** One set of pieces per kind of part, each replaced whole by its own scan. */
    public enum Kind {
        SHAFT, PIPE, TRACK
    }

    /** A piece in a portal block: its shape there, and the item of the part it continues. */
    public record Piece(VoxelShape shape, Item item) {
    }

    private static volatile Map<Kind, Map<ResourceKey<Level>, Map<BlockPos, Piece>>> pieces = new EnumMap<>(Kind.class);

    private IPCreatePortalPieceTargets() {
    }

    /** {@code kind}'s pieces after a walk, per dimension, per portal block (plot positions for a ship's). */
    static void set(final Kind kind, final Map<ResourceKey<Level>, Map<BlockPos, Piece>> found) {
        final Map<Kind, Map<ResourceKey<Level>, Map<BlockPos, Piece>>> next = new EnumMap<>(Kind.class);
        next.putAll(pieces);
        next.put(kind, found);
        pieces = next;
    }

    /** The piece in the portal block at {@code pos}, or null. Only in client worlds. */
    public static @Nullable Piece at(final BlockGetter getter, final BlockPos pos) {
        if (!(getter instanceof final Level level) || !level.isClientSide()) {
            return null;
        }
        for (final Map<ResourceKey<Level>, Map<BlockPos, Piece>> byDimension : pieces.values()) {
            final Map<BlockPos, Piece> here = byDimension.get(level.dimension());
            final Piece piece = here == null ? null : here.get(pos);
            if (piece != null) {
                return piece;
            }
        }
        return null;
    }

    /** {@code whole}'s part in the half of a block from its centre (the portal plane) to its face {@code toward}. */
    static VoxelShape half(final VoxelShape whole, final Direction toward) {
        final double[] min = {0, 0, 0};
        final double[] max = {1, 1, 1};
        final int axis = toward.getAxis().ordinal();
        if (toward.getAxisDirection() == Direction.AxisDirection.POSITIVE) {
            min[axis] = 0.5;
        } else {
            max[axis] = 0.5;
        }
        return Shapes.join(whole, Shapes.box(min[0], min[1], min[2], max[0], max[1], max[2]), BooleanOp.AND).optimize();
    }

    /** {@link #half} for each direction, by {@link Direction#ordinal}, of a part whose shape runs along that direction. */
    static VoxelShape[] halves(final java.util.function.Function<Direction, VoxelShape> along) {
        final VoxelShape[] out = new VoxelShape[6];
        for (final Direction direction : Direction.values()) {
            out[direction.ordinal()] = half(along.apply(direction), direction);
        }
        return out;
    }
}
