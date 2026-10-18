/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.dh_integration;

import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.SectionPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.ChunkSource;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlClientChunkMap;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * Distant Horizons' LODs cut away inside the chunks a portal view draws.
 * <p>
 * DH discards LOD fragments closer than a clip distance it derives from the vanilla render distance, and its LODs
 * show wherever vanilla drew only sky: with too short a clip, nearby fences, stairs and slabs show LODs through their
 * gaps; with too long a one, a band of empty sky separates the terrain from the LODs. In the player's own view DH's
 * clip is right. Through a portal no distance is: the destination's terrain only reaches as far as IP loaded it
 * around the far portal, which has nothing to do with the camera's distance.
 * <p>
 * So in portal passes DH's distance clip is turned off and the LOD terrain fragment shader instead discards
 * fragments lying in a chunk the pass draws (patched in by {@link IPDhPortalClip#patchShaderSource}): a bitmask of
 * {@value #GRID} x {@value #GRID} chunks centred on the camera's chunk. A chunk counts as drawn when it and its eight
 * neighbours are loaded on the client (Sodium and vanilla mesh a chunk only then), have been for
 * {@value #MESHING_GRACE_MS} ms (time to mesh), and the whole chunk is within the pass's render distance. A chunk
 * wrongly counted as drawn would show sky, one wrongly counted as not drawn only a few LOD fringes, so every test
 * errs towards "not drawn".
 * <p>
 * No DH types here: this class is loaded whether or not DH is present.
 */
public final class IPDhPortalDrawnChunks {
    /** Chunks along each side of the mask; covers render distances up to 32 around the camera. */
    public static final int GRID = 72;
    private static final int HALF = GRID / 2;
    public static final int MASK_WORDS = GRID * GRID / 32;

    private static final long MESHING_GRACE_MS = 1500;

    public static final String ENABLED_UNIFORM = "iportal_DhCutDrawnChunks";
    public static final String CAMERA_UNIFORM = "iportal_DhCutCamera";
    public static final String MASK_UNIFORM = "iportal_DhDrawnChunks";

    private static final String TERRAIN_FRAG = "shaders/terrain/gl/frag.frag";

    /** Per client world: when each chunk was last loaded (System.currentTimeMillis). Chunks absent count as old. */
    private static final Map<Level, Long2LongOpenHashMap> LOAD_TIMES = new WeakHashMap<>();

    private static final int[] MASK = new int[MASK_WORDS];
    private static final float[] CAMERA = new float[2];
    private static int maskFrame = -1;
    private static Vec3 maskCamera = null;
    private static Level maskWorld = null;
    private static int maskRenderDistance = -1;

    private IPDhPortalDrawnChunks() {
    }

    public static void init() {
        ImmPtlClientChunkMap.clientChunkLoadSignal.connect(chunk -> {
            synchronized (LOAD_TIMES) {
                LOAD_TIMES.computeIfAbsent(chunk.getLevel(), level -> new Long2LongOpenHashMap())
                    .put(chunk.getPos().toLong(), System.currentTimeMillis());
            }
        });
        ImmPtlClientChunkMap.clientChunkUnloadSignal.connect(chunk -> {
            synchronized (LOAD_TIMES) {
                Long2LongOpenHashMap times = LOAD_TIMES.get(chunk.getLevel());
                if (times != null) {
                    times.remove(chunk.getPos().toLong());
                }
            }
        });
    }

    /** Whether the LOD terrain shader should cut away the drawn chunks now (a portal pass). */
    public static boolean isActive() {
        return IPDhPortalView.ENABLED && PortalRendering.isRendering();
    }

    /**
     * The mask for the current portal pass, one bit per chunk, row-major from the grid's north-west corner
     * ({@code z * GRID + x}). Computed once per pass; call only when {@link #isActive()}.
     */
    public static int[] mask() {
        update();
        return MASK;
    }

    /** The camera's horizontal position relative to the grid's north-west corner, in blocks. */
    public static float[] cameraInGrid() {
        update();
        return CAMERA;
    }

    private static void update() {
        Portal portal = PortalRendering.getRenderingPortal();
        ClientLevel world = ClientWorldLoader.getWorld(portal.getDestDim());
        Vec3 camera = CHelper.getCurrentCameraPos();
        int renderDistance = WorldRenderInfo.getRenderDistance();
        if (maskFrame == RenderStates.frameIndex && world == maskWorld && camera.equals(maskCamera)
            && renderDistance == maskRenderDistance
        ) {
            return;
        }
        maskFrame = RenderStates.frameIndex;
        maskWorld = world;
        maskCamera = camera;
        maskRenderDistance = renderDistance;

        int originX = SectionPos.blockToSectionCoord(camera.x) - HALF;
        int originZ = SectionPos.blockToSectionCoord(camera.z) - HALF;
        CAMERA[0] = (float) (camera.x - originX * 16.0);
        CAMERA[1] = (float) (camera.z - originZ * 16.0);
        java.util.Arrays.fill(MASK, 0);
        if (world == null) {
            return;
        }

        // ready[x][z] over the grid plus a one-chunk border: loaded long enough to have been meshed
        int side = GRID + 2;
        boolean[] ready = new boolean[side * side];
        long now = System.currentTimeMillis();
        ChunkSource chunks = world.getChunkSource();
        synchronized (LOAD_TIMES) {
            Long2LongOpenHashMap times = LOAD_TIMES.get(world);
            for (int z = 0; z < side; z++) {
                for (int x = 0; x < side; x++) {
                    int chunkX = originX + x - 1;
                    int chunkZ = originZ + z - 1;
                    if (!chunks.hasChunk(chunkX, chunkZ)) {
                        continue;
                    }
                    long loadedAt = times == null ? 0 : times.getOrDefault(ChunkPos.asLong(chunkX, chunkZ), 0L);
                    ready[z * side + x] = now - loadedAt >= MESHING_GRACE_MS;
                }
            }
        }

        double maxDistance = renderDistance * 16.0;
        double maxDistanceSq = maxDistance * maxDistance;
        for (int z = 0; z < GRID; z++) {
            for (int x = 0; x < GRID; x++) {
                if (!allReady(ready, side, x + 1, z + 1)) {
                    continue;
                }
                // the chunk's farthest corner from the camera must be within the render distance
                double dx = Math.max(Math.abs(x * 16.0 - CAMERA[0]), Math.abs((x + 1) * 16.0 - CAMERA[0]));
                double dz = Math.max(Math.abs(z * 16.0 - CAMERA[1]), Math.abs((z + 1) * 16.0 - CAMERA[1]));
                if (dx * dx + dz * dz > maxDistanceSq) {
                    continue;
                }
                int bit = z * GRID + x;
                MASK[bit >> 5] |= 1 << (bit & 31);
            }
        }
    }

    private static boolean allReady(boolean[] ready, int side, int x, int z) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (!ready[(z + dz) * side + x + dx]) {
                    return false;
                }
            }
        }
        return true;
    }

    /** Patches DH's LOD terrain fragment shader with the cut; returns {@code source} unchanged for anything else. */
    public static String patchTerrainFragment(String normalizedPath, String source) {
        if (!normalizedPath.endsWith(TERRAIN_FRAG)) {
            return source;
        }
        int mainIndex = source.indexOf("void main");
        int bodyIndex = mainIndex < 0 ? -1 : source.indexOf('{', mainIndex);
        if (bodyIndex < 0 || !source.contains("in vec3 vertexWorldPos;") || !source.contains("flat in uint vNormalIndex;")) {
            return null;
        }
        // A face belongs to the block behind it: step half a block inwards so faces on chunk borders are not split.
        // Normal indices as in DH's blockFaceUv: 0 down, 1 up, 2 north, 3 south, 4 west, 5 east.
        String cut = """

                if (%1$s)
                {
                    vec2 ip_inward = vNormalIndex == 2u ? vec2(0.0, 0.5) : vNormalIndex == 3u ? vec2(0.0, -0.5)
                        : vNormalIndex == 4u ? vec2(0.5, 0.0) : vNormalIndex == 5u ? vec2(-0.5, 0.0) : vec2(0.0);
                    ivec2 ip_cell = ivec2(floor((vertexWorldPos.xz + ip_inward + %2$s) / 16.0));
                    if (all(greaterThanEqual(ip_cell, ivec2(0))) && all(lessThan(ip_cell, ivec2(%4$d))))
                    {
                        int ip_bit = ip_cell.y * %4$d + ip_cell.x;
                        if (((%3$s[ip_bit >> 5] >> uint(ip_bit & 31)) & 1u) != 0u)
                        {
                            discard;
                        }
                    }
                }
            """.formatted(ENABLED_UNIFORM, CAMERA_UNIFORM, MASK_UNIFORM, GRID);
        return source.substring(0, mainIndex)
            + "uniform bool " + ENABLED_UNIFORM + ";\n"
            + "uniform vec2 " + CAMERA_UNIFORM + ";\n"
            + "uniform uint " + MASK_UNIFORM + "[" + MASK_WORDS + "];\n\n"
            + source.substring(mainIndex, bodyIndex + 1)
            + cut
            + source.substring(bodyIndex + 1);
    }
}
