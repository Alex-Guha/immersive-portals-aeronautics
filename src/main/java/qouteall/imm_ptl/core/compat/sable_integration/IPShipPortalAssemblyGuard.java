/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;

/**
 * Portals do not work on physics objects, so a lit portal whose blocks are assembled into a
 * sub-level is broken on the spot. Called at the start of Sable's block move (every assembly path,
 * including Create Aeronautics', goes through it): any breakable portal whose portal or frame
 * blocks are among the moved blocks is broken on both sides before Sable copies anything, which
 * also removes the portal blocks so the sub-level keeps only the frame. Without this the portal
 * entity survived a few seconds in the world, the new sub-level's hull overlapped it and the
 * sub-level was teleported through its own portal (riders included) before both ends broke.
 */
public final class IPShipPortalAssemblyGuard {
    private static final Logger LOGGER = LogUtils.getLogger();

    private IPShipPortalAssemblyGuard() {
    }

    public static void breakPortalsAmong(ServerLevel level, Iterable<BlockPos> blocks) {
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

        AABB bounds = new AABB(minX, minY, minZ, maxX + 1, maxY + 1, maxZ + 1).inflate(1.0);
        List<BreakablePortalEntity> portals = level.getEntitiesOfClass(BreakablePortalEntity.class, bounds);
        if (portals.isEmpty()) {
            return;
        }

        // the iterable may reuse a mutable position (BlockPos.betweenClosed), hence the copies
        Set<BlockPos> moved = new HashSet<>();
        for (BlockPos block : blocks) {
            moved.add(block.immutable());
        }

        for (BreakablePortalEntity portal : portals) {
            if (portal.isRemoved() || !touches(portal.blockPortalShape, moved)) {
                continue;
            }
            LOGGER.info("[IPShipPortal] breaking {} because its blocks are being assembled into a physics object", portal);
            portal.breakPortalNow();
        }
    }

    private static boolean touches(BlockPortalShape shape, Set<BlockPos> moved) {
        if (shape == null) {
            return false;
        }
        for (BlockPos pos : shape.area) {
            if (moved.contains(pos)) {
                return true;
            }
        }
        for (BlockPos pos : shape.frameAreaWithCorner) {
            if (moved.contains(pos)) {
                return true;
            }
        }
        return false;
    }
}
