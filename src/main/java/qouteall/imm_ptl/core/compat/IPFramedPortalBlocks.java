/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ServerPortalTickEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Portal blocks in the opening of a framed plain portal: one made by command or by another mod (Create Teleporters),
 * standing in a frame of blocks like a nether portal but with air in its opening. Everything that bridges a portal
 * (Create's portal tracks, shaft halves, pipe ends) finds the portal through IP's portal blocks, so such a portal gets
 * them: the opening's air cells are filled with {@link PortalPlaceholderBlock} of the portal's axis, and the blocks are
 * taken away when the portal is discarded or killed and no other portal covers them.
 * <p>
 * A portal qualifies when it is not a breakable portal (those place their own blocks), not global, not in a ship's plot,
 * teleportable, unscaled and rectangular, its plane is a block plane's centre (as a nether portal's) along a world axis,
 * its opening covers whole blocks, all of them air or portal blocks, and every block around the opening in that plane is
 * a solid non-replaceable block. Frameless portals (in mid-air) are left alone. Checked on the portal's first server
 * tick and every {@value #CHECK_TICKS} ticks after, which also puts back a portal block a player broke.
 * <p>
 * Another mod may check its own frame and take the portal down when it finds our blocks in it (Create Teleporters did,
 * see {@code MixinCreateTeleportersFrameCheck_PortalBlocks}), then put it up again: an opening whose portal is removed
 * within {@value #QUICK_REMOVAL_TICKS} ticks of being filled, {@value #MAX_QUICK_REMOVALS} times in a row, is left empty
 * until the server stops.
 */
public final class IPFramedPortalBlocks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPPortalBlocks] ";
    private static final int CHECK_TICKS = 20;
    /** Largest opening filled, in blocks (a nether portal is at most 21 x 21). */
    private static final int MAX_CELLS = 32 * 32;
    private static final double EPSILON = 1.0E-4;
    private static final int QUICK_REMOVAL_TICKS = 40;
    private static final int MAX_QUICK_REMOVALS = 3;

    /** Openings filled this session: the game time of the last fill and the quick removals in a row since. */
    private static final Map<Key, Filled> FILLED = new HashMap<>();
    /** Openings left empty for the rest of the session. */
    private static final Set<Key> GIVEN_UP = new HashSet<>();

    private IPFramedPortalBlocks() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerPortalTickEvent.class, event -> {
            final Portal portal = event.portal;
            if (portal.tickCount % CHECK_TICKS == 0 && portal.level() instanceof final ServerLevel level) {
                fill(level, portal);
            }
        });
        NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent.class, event -> {
            final Entity entity = event.getEntity();
            if (event.getLevel() instanceof final ServerLevel level && entity instanceof final Portal portal
                && entity.getRemovalReason() != null && entity.getRemovalReason().shouldDestroy()) {
                clear(level, portal);
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> {
            FILLED.clear();
            GIVEN_UP.clear();
        });
    }

    private static void fill(final ServerLevel level, final Portal portal) {
        final Opening opening = Opening.of(portal);
        if (opening == null) {
            return;
        }
        final Key key = new Key(level.dimension(), opening);
        if (GIVEN_UP.contains(key)) {
            return;
        }
        final List<BlockPos> air = new ArrayList<>();
        for (final BlockPos cell : opening.cells()) {
            if (!level.isLoaded(cell)) {
                return;
            }
            final BlockState state = level.getBlockState(cell);
            if (state.isAir()) {
                air.add(cell);
            } else if (state.getBlock() != PortalPlaceholderBlock.instance) {
                return;
            }
        }
        if (air.isEmpty()) {
            return;
        }
        for (final BlockPos around : opening.frame()) {
            if (!level.isLoaded(around)) {
                return;
            }
            final BlockState state = level.getBlockState(around);
            if (state.isAir() || state.canBeReplaced() || state.getBlock() == PortalPlaceholderBlock.instance) {
                return;
            }
        }
        final BlockState placeholder = PortalPlaceholderBlock.instance.defaultBlockState()
            .setValue(PortalPlaceholderBlock.AXIS, opening.normal().getAxis());
        for (final BlockPos cell : air) {
            level.setBlock(cell, placeholder, Block.UPDATE_ALL);
        }
        final Filled previous = FILLED.get(key);
        FILLED.put(key, new Filled(level.getGameTime(), previous == null ? 0 : previous.quickRemovals()));
        LOGGER.info(TAG + "{} portal blocks put in the framed opening of {} at {} in {}",
            air.size(), portal.getUUID(), portal.getOriginPos(), level.dimension().location());
    }

    private static void clear(final ServerLevel level, final Portal portal) {
        final Opening opening = Opening.of(portal);
        if (opening == null) {
            return;
        }
        int cleared = 0;
        for (final BlockPos cell : opening.cells()) {
            if (!level.isLoaded(cell) || level.getBlockState(cell).getBlock() != PortalPlaceholderBlock.instance
                || coveredByAnother(level, portal, cell)) {
                continue;
            }
            level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            cleared++;
        }
        if (cleared == 0) {
            return;
        }
        LOGGER.info(TAG + "{} portal blocks taken from the opening of removed portal {} at {} in {}",
            cleared, portal.getUUID(), portal.getOriginPos(), level.dimension().location());
        final Key key = new Key(level.dimension(), opening);
        final Filled filled = FILLED.remove(key);
        if (filled == null || level.getGameTime() - filled.tick() > QUICK_REMOVAL_TICKS) {
            return;
        }
        final int quickRemovals = filled.quickRemovals() + 1;
        if (quickRemovals < MAX_QUICK_REMOVALS) {
            FILLED.put(key, new Filled(Long.MIN_VALUE / 2, quickRemovals));
            return;
        }
        GIVEN_UP.add(key);
        LOGGER.warn(TAG + "the portal at {} in {} was removed right after its opening got portal blocks, {} times in a"
                + " row (another mod's frame check?): leaving that opening without portal blocks until the server stops",
            portal.getOriginPos(), level.dimension().location(), quickRemovals);
    }

    /** Another live portal (its twin face, a nether portal) runs through {@code cell}. */
    private static boolean coveredByAnother(final ServerLevel level, final Portal removed, final BlockPos cell) {
        final Vec3 centre = Vec3.atCenterOf(cell);
        return !IPPortalLookup.touching(level, new AABB(cell), p -> {
            if (p == removed || p.isRemoved() || Math.abs(p.getDistanceToPlane(centre)) >= 0.5) {
                return false;
            }
            final Vec3 offset = centre.subtract(p.getOriginPos());
            return Math.abs(offset.dot(p.getAxisW())) < p.getWidth() / 2 && Math.abs(offset.dot(p.getAxisH())) < p.getHeight() / 2;
        }).isEmpty();
    }

    private record Key(ResourceKey<Level> dimension, Opening opening) {
    }

    private record Filled(long tick, int quickRemovals) {
    }

    /**
     * A qualifying portal's opening: the plane's block layer along {@code normal}, cells {@code [u0, u1) x [v0, v1)}
     * along the axes {@code u} and {@code v}.
     */
    private record Opening(Direction normal, int layer, Direction.Axis u, int u0, int u1, Direction.Axis v, int v0, int v1) {
        static @Nullable Opening of(final Portal portal) {
            if (portal instanceof BreakablePortalEntity || portal.getIsGlobal() || !portal.isTeleportable()
                || portal.getDestDim() == null || Math.abs(portal.getScale() - 1.0) > EPSILON
                || !(portal.getPortalShape() instanceof RectangularPortalShape)) {
                return null;
            }
            final Vec3 origin = portal.getOriginPos();
            if (IPSableCompatHelper.isPlotSpacePosition(origin.x, origin.z)) {
                return null;
            }
            final Direction normal = aligned(portal.getNormal());
            final Direction w = aligned(portal.getAxisW());
            final Direction h = aligned(portal.getAxisH());
            if (normal == null || w == null || h == null) {
                return null;
            }
            final double plane = normal.getAxis().choose(origin.x, origin.y, origin.z);
            if (Math.abs(plane - Math.floor(plane) - 0.5) > EPSILON) {
                return null;
            }
            final int[] uRange = range(w.getAxis().choose(origin.x, origin.y, origin.z), portal.getWidth());
            final int[] vRange = range(h.getAxis().choose(origin.x, origin.y, origin.z), portal.getHeight());
            if (uRange == null || vRange == null
                || (long) (uRange[1] - uRange[0]) * (vRange[1] - vRange[0]) > MAX_CELLS) {
                return null;
            }
            return new Opening(normal, (int) Math.floor(plane), w.getAxis(), uRange[0], uRange[1], h.getAxis(), vRange[0], vRange[1]);
        }

        /** The direction along a world axis that {@code vector} points along, or null. */
        private static @Nullable Direction aligned(final Vec3 vector) {
            final Direction nearest = Direction.getNearest(vector.x, vector.y, vector.z);
            return Vec3.atLowerCornerOf(nearest.getNormal()).dot(vector.normalize()) > 1 - EPSILON ? nearest : null;
        }

        /** The whole-block span {@code [lo, hi)} of a length centred on {@code centre}, or null when not on block edges. */
        private static int @Nullable [] range(final double centre, final double length) {
            final double lo = centre - length / 2;
            final double hi = centre + length / 2;
            final long loBlock = Math.round(lo);
            final long hiBlock = Math.round(hi);
            if (Math.abs(lo - loBlock) > EPSILON || Math.abs(hi - hiBlock) > EPSILON || hiBlock <= loBlock) {
                return null;
            }
            return new int[]{(int) loBlock, (int) hiBlock};
        }

        List<BlockPos> cells() {
            final List<BlockPos> cells = new ArrayList<>();
            for (int a = u0; a < u1; a++) {
                for (int b = v0; b < v1; b++) {
                    cells.add(at(a, b));
                }
            }
            return cells;
        }

        /** The blocks around the opening in its plane (corners not needed). */
        List<BlockPos> frame() {
            final List<BlockPos> frame = new ArrayList<>();
            for (int a = u0; a < u1; a++) {
                frame.add(at(a, v0 - 1));
                frame.add(at(a, v1));
            }
            for (int b = v0; b < v1; b++) {
                frame.add(at(u0 - 1, b));
                frame.add(at(u1, b));
            }
            return frame;
        }

        private BlockPos at(final int a, final int b) {
            final int[] xyz = new int[3];
            xyz[normal.getAxis().ordinal()] = layer;
            xyz[u.ordinal()] = a;
            xyz[v.ordinal()] = b;
            return new BlockPos(xyz[0], xyz[1], xyz[2]);
        }
    }
}
