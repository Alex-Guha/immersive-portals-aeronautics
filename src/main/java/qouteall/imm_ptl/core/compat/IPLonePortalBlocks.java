/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import com.mojang.logging.LogUtils;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Takes away portal blocks ({@link PortalPlaceholderBlock}) that no portal holds any more. A breakable portal clears its
 * own blocks when its frame breaks; when its entity goes any other way (killed or discarded by a command or another mod,
 * removed by IP as invalid, lost from the save) the blocks stay, and the frame cannot be lit again until each of them is
 * broken by hand.
 * <p>
 * Three things ask for a check: a breakable portal leaving its level for good (the blocks of its shape), a portal
 * block's neighbour changing and a portal block's random tick (blocks already left in a save go a little while after a
 * player comes near). The check waits {@value #DELAY_TICKS} ticks, so a portal replaced or generated in the same tick has
 * its entities by then, takes the connected sheet of portal blocks of one axis and clears those no live portal runs
 * through. It does nothing while the entities of a chunk the sheet reaches are not loaded (a chunk's entities load after
 * its blocks). Blocks in a ship's plot are checked only for a removed portal that was theirs, with its ship loaded: a
 * ship-side portal's entities stand at the ship's world position, not by its blocks.
 */
public final class IPLonePortalBlocks {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPPortalBlocks] ";
    private static final int DELAY_TICKS = 10;
    /** Largest sheet looked at, in blocks (a nether portal is at most 21 x 21). */
    private static final int MAX_SHEET = 64 * 64;
    private static final int CHECKS_PER_TICK = 64;
    /** Removals with portal blocks left whose caller is logged, per session. */
    private static final int MAX_REMOVAL_TRACES = 10;

    private enum Why {
        RANDOM_TICK("found on a random tick"),
        NEIGHBOUR("found after a neighbour changed"),
        REMOVED("left by a removed portal");

        final String text;

        Why(final String text) {
            this.text = text;
        }
    }

    private record Pending(long due, Why why, @Nullable String removed) {
    }

    private static final Map<ResourceKey<Level>, Map<BlockPos, Pending>> PENDING = new HashMap<>();
    private static int removalTraces;

    private IPLonePortalBlocks() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent.class, event -> {
            if (event.getLevel() instanceof final ServerLevel level && event.getEntity() instanceof final BreakablePortalEntity portal) {
                onRemoved(level, portal);
            }
        });
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> process(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> {
            PENDING.clear();
            removalTraces = 0;
        });
    }

    /** A portal block was random-ticked. */
    public static void onRandomTick(final ServerLevel level, final BlockPos pos) {
        if (!isPlot(pos)) {
            request(level, pos, Why.RANDOM_TICK, null);
        }
    }

    /** A portal block's neighbour changed. */
    public static void onNeighbourChanged(final ServerLevel level, final BlockPos pos) {
        if (!isPlot(pos)) {
            request(level, pos, Why.NEIGHBOUR, null);
        }
    }

    private static void onRemoved(final ServerLevel level, final BreakablePortalEntity portal) {
        final Entity.RemovalReason reason = portal.getRemovalReason();
        final BlockPortalShape shape = portal.blockPortalShape;
        if (reason == null || !reason.shouldDestroy() || shape == null) {
            return;
        }
        int left = 0;
        for (final BlockPos pos : shape.area) {
            if (level.isLoaded(pos) && level.getBlockState(pos).getBlock() == PortalPlaceholderBlock.instance) {
                left++;
                request(level, pos, Why.REMOVED, portal.toString());
            }
        }
        if (left == 0) {
            return; // it broke the IP way, taking its blocks with it
        }
        if (removalTraces < MAX_REMOVAL_TRACES) {
            removalTraces++;
            LOGGER.info(TAG + "{} removed ({}) with {} portal blocks still in its frame; removed by:", portal, reason, left,
                new Throwable("stack of the removal"));
        } else {
            LOGGER.info(TAG + "{} removed ({}) with {} portal blocks still in its frame", portal, reason, left);
        }
    }

    private static void request(final ServerLevel level, final BlockPos pos, final Why why, @Nullable final String removed) {
        final Map<BlockPos, Pending> pending = PENDING.computeIfAbsent(level.dimension(), k -> new LinkedHashMap<>());
        final Pending was = pending.get(pos);
        if (was == null || (why == Why.REMOVED && was.why() != Why.REMOVED)) {
            pending.put(pos.immutable(), new Pending(level.getGameTime() + DELAY_TICKS, why, removed));
        }
    }

    private static void process(final MinecraftServer server) {
        if (PENDING.isEmpty()) {
            return;
        }
        int budget = CHECKS_PER_TICK;
        final Iterator<Map.Entry<ResourceKey<Level>, Map<BlockPos, Pending>>> levels = PENDING.entrySet().iterator();
        while (levels.hasNext()) {
            final Map.Entry<ResourceKey<Level>, Map<BlockPos, Pending>> entry = levels.next();
            final ServerLevel level = server.getLevel(entry.getKey());
            if (level == null) {
                levels.remove();
                continue;
            }
            final long now = level.getGameTime();
            final Iterator<Map.Entry<BlockPos, Pending>> it = entry.getValue().entrySet().iterator();
            while (it.hasNext() && budget > 0) {
                final Map.Entry<BlockPos, Pending> due = it.next();
                if (due.getValue().due() > now) {
                    continue;
                }
                it.remove();
                budget--;
                check(level, due.getKey(), due.getValue());
            }
            if (entry.getValue().isEmpty()) {
                levels.remove();
            }
        }
    }

    private static void check(final ServerLevel level, final BlockPos pos, final Pending pending) {
        if (!level.isLoaded(pos)) {
            return;
        }
        final BlockState state = level.getBlockState(pos);
        if (state.getBlock() != PortalPlaceholderBlock.instance) {
            return;
        }
        final boolean plot = isPlot(pos);
        final Vec3 worldCentre;
        if (plot) {
            // a ship's block: only for a removed portal that was there, and only with its ship loaded
            worldCentre = pending.why() == Why.REMOVED ? IPShipPortals.worldPositionOfPlotBlock(level, pos) : null;
            if (worldCentre == null) {
                return;
            }
        } else {
            worldCentre = Vec3.atCenterOf(pos);
        }
        if (held(pos, plot, holders(level, new AABB(worldCentre, worldCentre).inflate(1)))) {
            return; // the common case for a random tick: a live portal's block
        }

        final List<BlockPos> sheet = sheetOf(level, pos, state.getValue(PortalPlaceholderBlock.AXIS));
        if (sheet == null) {
            return;
        }
        final AABB box = boundsOf(sheet);
        final AABB worldBox = plot
            ? new AABB(worldCentre, worldCentre).inflate(Math.max(box.getXsize(), Math.max(box.getYsize(), box.getZsize())) + 2)
            : box.inflate(1);
        if (!entitiesLoaded(level, worldBox)) {
            return; // its portal may not be loaded yet
        }
        final List<Portal> holders = holders(level, worldBox);
        final List<BlockPos> lone = new ArrayList<>();
        for (final BlockPos cell : sheet) {
            if (!held(cell, plot, holders)) {
                lone.add(cell);
            }
        }
        if (lone.isEmpty()) {
            return;
        }
        try {
            for (final BlockPos cell : lone) {
                level.setBlock(cell, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
        } catch (final RuntimeException e) {
            // a ship's plot that went away under us: Sable refuses block changes there
            LOGGER.warn(TAG + "could not clear portal blocks at {} in {}: {}", pos.toShortString(), level.dimension().location(), e.toString());
            return;
        }
        LOGGER.info(TAG + "{} portal blocks no portal holds cleared at {} in {} ({}{})", lone.size(), pos.toShortString(),
            level.dimension().location(), pending.why().text, pending.removed() == null ? "" : ": " + pending.removed());
    }

    /** Live portals that may hold portal blocks in {@code box} (world space). */
    private static List<Portal> holders(final ServerLevel level, final AABB box) {
        return IPPortalLookup.touching(level, box, p -> !p.isRemoved());
    }

    /**
     * Whether a portal holds the block at {@code pos}: a breakable portal whose shape has it, or (outside a plot) any
     * portal reaching within half a block of its centre, such as an end portal or a framed plain portal.
     */
    private static boolean held(final BlockPos pos, final boolean plot, final List<Portal> portals) {
        final Vec3 centre = Vec3.atCenterOf(pos);
        for (final Portal portal : portals) {
            if (portal instanceof final BreakablePortalEntity breakable && breakable.blockPortalShape != null
                && breakable.blockPortalShape.area.contains(pos)) {
                return true;
            }
            if (!plot && portal.getBoundingBox().inflate(0.5).contains(centre)) {
                return true;
            }
        }
        return false;
    }

    /** The portal blocks of {@code axis} joined to {@code start} in its plane, or null if unloaded or too large. */
    private static @Nullable List<BlockPos> sheetOf(final ServerLevel level, final BlockPos start, final Direction.Axis axis) {
        final List<Direction> inPlane = new ArrayList<>(4);
        for (final Direction d : Direction.values()) {
            if (d.getAxis() != axis) {
                inPlane.add(d);
            }
        }
        final Set<BlockPos> seen = new HashSet<>();
        final List<BlockPos> sheet = new ArrayList<>();
        final ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        seen.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            final BlockPos at = queue.poll();
            sheet.add(at);
            if (sheet.size() > MAX_SHEET) {
                LOGGER.warn(TAG + "portal blocks at {} in {} form a sheet of over {} blocks: not checked", start.toShortString(),
                    level.dimension().location(), MAX_SHEET);
                return null;
            }
            for (final Direction d : inPlane) {
                final BlockPos next = at.relative(d);
                if (!seen.add(next)) {
                    continue;
                }
                if (!level.isLoaded(next)) {
                    return null;
                }
                final BlockState state = level.getBlockState(next);
                if (state.getBlock() == PortalPlaceholderBlock.instance && state.getValue(PortalPlaceholderBlock.AXIS) == axis) {
                    queue.add(next);
                }
            }
        }
        return sheet;
    }

    private static AABB boundsOf(final List<BlockPos> cells) {
        int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE;
        int x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
        for (final BlockPos c : cells) {
            x0 = Math.min(x0, c.getX());
            y0 = Math.min(y0, c.getY());
            z0 = Math.min(z0, c.getZ());
            x1 = Math.max(x1, c.getX());
            y1 = Math.max(y1, c.getY());
            z1 = Math.max(z1, c.getZ());
        }
        return new AABB(x0, y0, z0, x1 + 1, y1 + 1, z1 + 1);
    }

    private static boolean entitiesLoaded(final ServerLevel level, final AABB box) {
        final int cx0 = (int) Math.floor(box.minX) >> 4;
        final int cx1 = (int) Math.floor(box.maxX) >> 4;
        final int cz0 = (int) Math.floor(box.minZ) >> 4;
        final int cz1 = (int) Math.floor(box.maxZ) >> 4;
        for (int cx = cx0; cx <= cx1; cx++) {
            for (int cz = cz0; cz <= cz1; cz++) {
                if (!level.areEntitiesLoaded(ChunkPos.asLong(cx, cz))) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isPlot(final BlockPos pos) {
        return IPSableCompatHelper.isPlotSpacePosition(pos.getX(), pos.getZ());
    }
}
