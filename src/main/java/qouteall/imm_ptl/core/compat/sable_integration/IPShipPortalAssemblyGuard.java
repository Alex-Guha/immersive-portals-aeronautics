/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import qouteall.imm_ptl.core.compat.IPPortalLookup;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalAnchors;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;

/**
 * Portals and Sable's block moves. Called at the start of Sable's block move (every assembly and disassembly path,
 * including Create Aeronautics', goes through it), before anything moved, with the lit world portals around the moved
 * blocks. With ship-mounted portals off, any of them whose portal or frame blocks are among the moved blocks is broken
 * on both sides before Sable copies anything, which also removes the portal blocks so the sub-level keeps only the
 * frame. Without this the portal entity survived a few seconds in the world, the new sub-level's hull overlapped it and
 * the sub-level was teleported through its own portal (riders included) before both ends broke. With them on,
 * {@link IPShipPortalAnchors#onBlocksMoving} mounts a whole portal on the new ship instead, and moves portals already
 * mounted on a ship along with their blocks. Custom portals (wand, {@code /portal} commands) are never broken here: with
 * ship-mounted portals on they are mounted with the blocks they touch, otherwise they stay in the world.
 */
public final class IPShipPortalAssemblyGuard {
    private static final boolean CREATE = net.neoforged.fml.ModList.get().isLoaded("create");

    private IPShipPortalAssemblyGuard() {
    }

    public static void onBlocksMoving(ServerLevel level, SubLevelAssemblyHelper.AssemblyTransform transform, Iterable<BlockPos> blocks) {
        if (CREATE) {
            qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalTracks.beforeBlocksMoved(level, blocks, transform::apply, transform.getLevel());
        }
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;
        for (BlockPos block : blocks) {
            minX = Math.min(minX, block.getX());
            minY = Math.min(minY, block.getY());
            minZ = Math.min(minZ, block.getZ());
            maxX = Math.max(maxX, block.getX());
            maxY = Math.max(maxY, block.getY());
            maxZ = Math.max(maxZ, block.getZ());
        }
        if (minX == Integer.MAX_VALUE) {
            return;
        }

        List<Portal> worldPortals = new ArrayList<>();
        if (!IPSableCompatHelper.isPlotSpacePosition(minX, minZ)) {
            AABB bounds = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1).inflate(1.0);
            worldPortals.addAll(IPPortalLookup.touching(level, bounds,
                p -> p instanceof BreakablePortalEntity || IPShipPortalAnchors.isCustom(p)));
        }
        IPShipPortalAnchors.onBlocksMoving(level, transform, blocks, worldPortals);
    }

    /** Sable's block move is done: portal blocks the assembler left out are put back where their frame went. */
    public static void afterBlocksMoved() {
        IPShipPortalAnchors.afterBlocksMoved();
        if (CREATE) {
            qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalTracks.afterBlocksMoved();
        }
    }
}
