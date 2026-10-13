/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;

import java.util.List;
import java.util.function.Predicate;

/**
 * Portals whose bounding box touches a box, including large ones. Vanilla's {@code getEntitiesOfClass} only searches
 * the entity sections around the box, and an entity is filed under the section of its position: the centre of a portal.
 * A ship passing through a big nether portal away from its centre was never matched with it and went straight through
 * (1.19.14 in game). IP's own lookup widens the search by the largest normal portal's radius; global portals are added.
 */
public final class IPPortalLookup {
    private IPPortalLookup() {
    }

    public static List<Portal> touching(final Level level, final AABB box, final Predicate<Portal> predicate) {
        final List<Portal> portals = McHelper.findEntitiesByBox(Portal.class, level, box, IPGlobal.maxNormalPortalRadius + 1, predicate);
        for (final Portal global : GlobalPortalStorage.getGlobalPortals(level)) {
            if (global.getBoundingBox().intersects(box) && predicate.test(global) && !portals.contains(global)) {
                portals.add(global);
            }
        }
        return portals;
    }
}
