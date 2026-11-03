/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.render;

import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Where a portal view takes its biome colours (fog, sky): the point of the far portal nearest the pass camera. The pass
 * camera lies behind the far portal, as far from it as the player is from the near one, and vanilla samples the biome
 * at the camera: the view took the colour of wherever that point fell, moving block for block with the player, and
 * plains (Overworld blue fog in the Nether) in chunks the client had not loaded. At the far portal the colours are
 * those of the place the portal shows, and the same the player gets on stepping through.
 */
public final class IPPortalBiomeSample {
    private IPPortalBiomeSample() {
    }

    /** The sample point of the current portal pass; call only while {@link PortalRendering#isRendering()}. */
    public static Vec3 samplePos() {
        Portal portal = PortalRendering.getRenderingPortal();
        Vec3 camera = PortalRendering.getRenderingCameraPos();
        return portal.transformPoint(portal.getNearestPointInPortal(portal.inverseTransformPoint(camera)));
    }
}
