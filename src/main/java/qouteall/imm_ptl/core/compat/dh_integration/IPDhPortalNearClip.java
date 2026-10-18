/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.dh_integration;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Where Distant Horizons' LODs may start inside a portal view.
 * <p>
 * DH discards LOD fragments closer to the camera than a clip distance derived from the vanilla render distance, and
 * its LODs show wherever vanilla drew only sky, so a clip that is too short makes nearby fences, stairs and slabs
 * show LODs through their gaps. In the player's own view DH's clip is right. Through a portal the destination's
 * terrain only reaches as far as IP loaded it around the far portal (often less than the render distance, less still
 * the farther the player stands from the portal), and DH's clip would leave a band of empty sky between that terrain
 * and the LODs.
 * <p>
 * {@link #coveredDistance} gives a distance from the portal camera within which everything visible through the portal
 * is covered by the destination's drawn terrain. Every point seen through the portal lies on a ray from the camera
 * through a point {@code X} of the portal, beyond {@code X}. With {@code P} the destination portal's centre, {@code r}
 * its radius and terrain drawn horizontally within {@code R} of {@code P}, a visible point {@code Q} outside that
 * terrain has {@code |Q - camera| = |X - camera| + |Q - X| >= (|P - camera| - r) + (R - r)}. {@code R} is measured
 * from the client's chunk map: loaded chunks along the four axes from {@code P}'s chunk, less the outermost ring
 * (Sodium and vanilla draw a chunk only once its neighbours are loaded) and the worst-case offsets of {@code P} and of
 * the far point within their chunks; it assumes the loaded area is at least a disc of that radius (IP and vanilla load
 * squares or discs).
 * <p>
 * Used for the Iris shader path (whose LOD programs IP cannot patch) and as the fallback when DH's own terrain
 * shader could not be patched with {@link IPDhPortalDrawnChunks}' exact cut. In practice it comes out well short of
 * where the terrain really ends (about 33 blocks standing at a nether portal, 2.3.19 test).
 * <p>
 * No DH types here; the {@code mixin_dh} near-clip mixins combine this with DH's own clip.
 */
public final class IPDhPortalNearClip {
    /** Chunks probed along each axis; beyond any render distance in practice. */
    private static final int MAX_PROBE_CHUNKS = 96;

    /** Worst-case offset of a point from its chunk's centre, counted once for {@code P} and once for the far point. */
    private static final double CHUNK_CENTRE_OFFSETS = 2 * 8 * Math.sqrt(2);

    private IPDhPortalNearClip() {
    }

    /**
     * In a portal pass, the distance in blocks from the portal camera within which the view through the innermost
     * portal shows the destination's drawn terrain (0 if that cannot be guaranteed at all). Outside portal passes,
     * {@link Double#POSITIVE_INFINITY}.
     */
    public static double coveredDistance() {
        if (!PortalRendering.isRendering()) {
            return Double.POSITIVE_INFINITY;
        }
        Portal portal = PortalRendering.getRenderingPortal();
        ClientLevel destWorld = ClientWorldLoader.getWorld(portal.getDestDim());
        if (destWorld == null) {
            return 0;
        }

        Vec3 dest = portal.getDestPos();
        double drawnRadius = (loadedChunksAlongAxes(destWorld.getChunkSource(), dest) - 1) * 16.0 - CHUNK_CENTRE_OFFSETS;
        double portalRadius = portal.getDestAreaRadiusEstimation();
        double cameraToPortal = Math.max(0, PortalRendering.getRenderingCameraPos().distanceTo(dest) - portalRadius);
        return Math.max(0, cameraToPortal + drawnRadius - portalRadius);
    }

    /** The fewest consecutive loaded chunks next to {@code pos}'s chunk along +x, -x, +z and -z. */
    private static int loadedChunksAlongAxes(ChunkSource chunks, Vec3 pos) {
        int centreX = SectionPos.blockToSectionCoord(pos.x);
        int centreZ = SectionPos.blockToSectionCoord(pos.z);
        int fewest = MAX_PROBE_CHUNKS;
        int[][] directions = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] direction : directions) {
            int count = 0;
            while (count < fewest
                && chunks.hasChunk(centreX + direction[0] * (count + 1), centreZ + direction[1] * (count + 1))
            ) {
                count++;
            }
            fewest = count;
        }
        return fewest;
    }
}
