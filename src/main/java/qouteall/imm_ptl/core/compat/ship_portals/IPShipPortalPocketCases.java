/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

/**
 * Create: Pocket Sized packs a ship into a case (box, bottle, display plate): it saves the ship's plot, saves and
 * deletes every other entity in the ship's box (Sable's entity lookup maps that box onto the ship's place in the world,
 * so a ship's own portals go too), and removes the ship. A case can be unpacked anywhere, in any dimension, and copied
 * in creative, so a portal's link cannot survive it: the ship's portals shatter as packing starts, while the plot can
 * still clear their portal blocks, and the case keeps the frame (mixins: {@code MixinPocketSubLevelEvents_ShatterPortals},
 * {@code MixinPocketedEntities_Portals}).
 */
public final class IPShipPortalPocketCases {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** A case being unpacked dropped a portal: its portal blocks are left in the ship's plot. */
    private static boolean droppedPortal;

    private IPShipPortalPocketCases() {
    }

    /** Pocket is about to save {@code ship} into a case: past its own refusals (welds, joints), before saving. */
    public static void onPacking(final ServerSubLevel ship) {
        IPShipPortalAnchors.shatterCarriedPortals(ship.getUniqueId(), "its ship is being packed into a Create: Pocket Sized case");
    }

    /**
     * Pym is about to take {@code ship} out of the world as a build (put on a hat or a hat stand: {@code Detached.adopt}):
     * no portal is kept on those ({@link IPShipPortalAnchors}), and once taken Sable no longer finds the ship.
     */
    public static void onBecomingHeldBuild(final ServerSubLevel ship) {
        IPShipPortalAnchors.shatterCarriedPortals(ship.getUniqueId(), HELD_BUILD);
    }

    /**
     * Whether no portal may be lit at {@code pos} because it is in the plot of one of Pym's builds (a hat or hat stand
     * build, or a ship riding one). Refused before any portal block is placed: breaking a portal there afterwards
     * changes the build's blocks, which Pocket takes for the ship being broken off the hat.
     */
    public static boolean refusesPortalAt(final net.minecraft.server.level.ServerLevel level, final BlockPos pos) {
        if (!qouteall.imm_ptl.core.compat.sable_integration.IPPymCompat.isLoaded()) {
            return false;
        }
        final dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer container =
            dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null || !container.inBounds(pos)) {
            return false;
        }
        final dev.ryanhcode.sable.sublevel.plot.LevelPlot plot = container.getPlot(new net.minecraft.world.level.ChunkPos(pos));
        final dev.ryanhcode.sable.sublevel.SubLevel ship = plot == null ? null : plot.getSubLevel();
        if (ship == null || !qouteall.imm_ptl.core.compat.sable_integration.IPPymCompat.isHeldBuild(ship)) {
            return false;
        }
        LOGGER.info("[IPShipPortal] no portal lit at {} on ship {}: {}", pos.toShortString(), ship.getUniqueId(), HELD_BUILD);
        return true;
    }

    /** Why a portal on one of Pym's builds shatters. */
    public static final String HELD_BUILD = "its ship is a Create: Pocket Sized hat or hat stand build";

    /** A portal loaded from a case being unpacked (one packed before its portals shattered at packing). */
    public static void onUnpackedPortal(final Portal portal) {
        IPShipPortalAnchors.dropFromCase(portal);
        droppedPortal = true;
    }

    /**
     * After a case's entities are back: if a portal was dropped, its portal blocks are still on the ship, where they
     * would keep the frame from being lit again. Pocket may have moved the ship to another plot, so the portal's own
     * block positions are stale: clear every portal block in the ship's plot (none there belongs to a live portal yet).
     */
    public static void afterUnpacking(final ServerLevel level, final ServerSubLevel ship) {
        if (!droppedPortal) {
            return;
        }
        droppedPortal = false;
        final BoundingBox3ic box = ship.getPlot().getBoundingBox();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int cleared = 0;
        for (int x = box.minX(); x <= box.maxX(); x++) {
            for (int y = box.minY(); y <= box.maxY(); y++) {
                for (int z = box.minZ(); z <= box.maxZ(); z++) {
                    if (level.getBlockState(pos.set(x, y, z)).is(PortalPlaceholderBlock.instance)) {
                        level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                        cleared++;
                    }
                }
            }
        }
        LOGGER.info("[IPShipPortal] cleared {} portal blocks left on ship {} by its case", cleared, ship.getUniqueId());
    }
}
