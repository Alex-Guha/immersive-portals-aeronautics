/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.portal_pieces;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * A kind of part that can face a portal, and who owns the space between the two sides of a portal at one spot. The two
 * portal blocks there (each from its block face to its plane) make one block of space between the blocks facing them,
 * and only one thing goes through it: the highest-ranked of what faces the portal on either side (Create's: belts,
 * then shafts, then pipes, then tracks). The owner keeps its look and behaviour through the whole space (its own half,
 * and a lone end's piece past the plane); an outranked end draws nothing in its portal block and passes nothing
 * through, as if it faced a wall. Ends of the same kind join. Server and client.
 */
public final class IPPortalPieceKind {
    /** Whether a part of a kind at {@code pos} has a connection on {@code side} (towards the portal). */
    @FunctionalInterface
    public interface Faces {
        boolean faces(Level level, BlockPos pos, BlockState state, Direction side);
    }

    /** Nothing facing the portal there: outranks nothing. */
    public static final IPPortalPieceKind NONE = new IPPortalPieceKind("none", 0, (level, pos, state, side) -> false);

    /** Every registered kind, highest rank first. Replaced whole on a registration (setup); read on any thread. */
    private static volatile List<IPPortalPieceKind> byRank = List.of();

    private final String id;
    private final int rank;
    private final Faces faces;

    private IPPortalPieceKind(final String id, final int rank, final Faces faces) {
        this.id = id;
        this.rank = rank;
        this.faces = faces;
    }

    /**
     * Adds a kind. Ranks are spaced (Create's: track 100, pipe 200, shaft 300, belt 400) so others can go between; two
     * kinds may not share one, or who owns a spot would depend on the order of the checks. Mod setup.
     */
    public static synchronized IPPortalPieceKind register(final String id, final int rank, final Faces faces) {
        final List<IPPortalPieceKind> next = new ArrayList<>(byRank);
        for (final IPPortalPieceKind kind : next) {
            if (kind.rank == rank || kind.id.equals(id)) {
                throw new IllegalArgumentException("portal piece kind " + id + " (rank " + rank + ") clashes with " + kind);
            }
        }
        if (rank <= NONE.rank) {
            throw new IllegalArgumentException("portal piece kind " + id + ": rank " + rank + " must be above " + NONE.rank);
        }
        final IPPortalPieceKind kind = new IPPortalPieceKind(id, rank, faces);
        next.add(kind);
        next.sort(Comparator.comparingInt((IPPortalPieceKind k) -> k.rank).reversed());
        byRank = List.copyOf(next);
        return kind;
    }

    public String id() {
        return id;
    }

    public int rank() {
        return rank;
    }

    public boolean outranks(final IPPortalPieceKind other) {
        return rank > other.rank;
    }

    /** What faces {@code side} (towards the portal) at {@code pos}: the highest-ranked kind that does, or {@link #NONE}. */
    public static IPPortalPieceKind at(final Level level, final BlockPos pos, final Direction side) {
        if (!level.isLoaded(pos)) {
            return NONE;
        }
        final BlockState state = level.getBlockState(pos);
        for (final IPPortalPieceKind kind : byRank) {
            if (kind.faces.faces(level, pos, state, side)) {
                return kind;
            }
        }
        return NONE;
    }

    /** Whether what faces the portal at {@code farPos} (towards {@code farSide}) outranks an end of kind {@code mine}. */
    public static boolean outranked(final Level farLevel, final BlockPos farPos, final Direction farSide, final IPPortalPieceKind mine) {
        return at(farLevel, farPos, farSide).outranks(mine);
    }

    @Override
    public String toString() {
        return id + "@" + rank;
    }
}
