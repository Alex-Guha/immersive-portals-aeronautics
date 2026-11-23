/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.iris_integration;

import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * The client option {@code portalViewShadows}: what a portal view draws into a shader pack's shadow map. Full draws
 * what the pack asks for (culled to the portal, {@link IPIrisShadowCull}); Terrain Only leaves out entities, the
 * player, block entities and Colorwheel's Flywheel visuals; Off draws nothing. The player's own view is never touched.
 * <p>
 * The shadow pass itself still runs, only its draws are switched off (through the pack's own switches, such as
 * {@code shadowEntities}): Iris clears the shadow depth at the start of every pass ({@code beginLevelRendering}) and
 * copies it for {@code shadowtex1} inside the pass ({@code copyPreTranslucentDepth}), so the map stays consistent and
 * shows no caster. Skipping the whole pass would leave {@code shadowtex1} with whatever the view's pipeline drew last.
 */
public final class IPPortalViewShadows {
    private IPPortalViewShadows() {
    }

    private static IPGlobal.PortalViewShadows mode() {
        IPGlobal.PortalViewShadows mode = IPConfig.getConfig().portalViewShadows;
        return mode != null ? mode : IPGlobal.PortalViewShadows.full;
    }

    /** Whether the shadow pass draws terrain (and with it ships and LODs), given the pack's own switch. */
    public static boolean drawsTerrain(boolean packDraws) {
        return packDraws && (!PortalRendering.isRendering() || mode() != IPGlobal.PortalViewShadows.off);
    }

    /** Whether the shadow pass draws entities, the player, block entities and Flywheel visuals. */
    public static boolean drawsEntities(boolean packDraws) {
        return packDraws && (!PortalRendering.isRendering() || mode() == IPGlobal.PortalViewShadows.full);
    }
}
