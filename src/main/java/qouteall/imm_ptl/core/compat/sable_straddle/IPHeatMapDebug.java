/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.heat.SubLevelHeatMapManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Test hook on Sable's split detection ({@link SubLevelHeatMapManager}): {@code reroot <n>} makes the n-th portal block
 * of each ship carrying portal blocks the root of its heat map, as Sable's own rebuild after a broken root may. A
 * shatter then removes the root with the other portal blocks, the case {@code MixinSableHeatMap_SplitFixes} fixes.
 */
final class IPHeatMapDebug {
    private static final Logger LOGGER = LogUtils.getLogger();

    private IPHeatMapDebug() {
    }

    static List<String> reroot(final ServerLevel level, final int index) {
        final List<String> lines = new ArrayList<>();
        final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return lines;
        }
        for (final ServerSubLevel ship : container.getAllSubLevels()) {
            final List<BlockPos> blocks = blocks(ship);
            final List<BlockPos> portals = blocks.stream()
                    .filter(pos -> level.getBlockState(pos).getBlock() instanceof PortalPlaceholderBlock)
                    .sorted(Comparator.comparingLong(BlockPos::asLong))
                    .toList();
            if (portals.isEmpty()) {
                continue;
            }
            final BlockPos root = portals.get(Math.floorMod(index, portals.size()));
            final List<BlockPos> order = new ArrayList<>(blocks.size());
            order.add(root);
            blocks.stream().filter(pos -> !pos.equals(root)).forEach(order::add);
            try {
                final Method rebuild = SubLevelHeatMapManager.class.getDeclaredMethod("rebuildHeatmapFrom", List.class);
                rebuild.setAccessible(true);
                rebuild.invoke(ship.getHeatMapManager(), order);
            } catch (final ReflectiveOperationException e) {
                lines.add("[IPHeat] reroot failed: " + e);
                continue;
            }
            final String line = "[IPHeat] ship " + ship.getUniqueId() + " rerooted at portal block " + root.toShortString()
                    + " (" + (Math.floorMod(index, portals.size()) + 1) + " of " + portals.size() + "), " + blocks.size() + " blocks";
            LOGGER.info(line);
            lines.add(line);
        }
        return lines;
    }

    /** The ship's non-air blocks, in plot coordinates. */
    private static List<BlockPos> blocks(final ServerSubLevel ship) {
        final ServerLevel level = ship.getLevel();
        final BoundingBox3ic box = ship.getPlot().getBoundingBox();
        final List<BlockPos> blocks = new ArrayList<>();
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    final BlockPos pos = new BlockPos(x, y, z);
                    if (!level.getBlockState(pos).isAir()) {
                        blocks.add(pos);
                    }
                }
            }
        }
        return blocks;
    }
}
