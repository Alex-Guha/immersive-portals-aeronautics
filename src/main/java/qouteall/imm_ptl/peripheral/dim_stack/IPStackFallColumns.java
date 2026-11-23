/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.peripheral.dim_stack;

import com.mojang.blaze3d.shaders.FogShape;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import org.joml.Matrix4fStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.extensions.common.IClientFluidTypeExtensions;
import net.neoforged.neoforge.common.NeoForge;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.compat.iris_integration.IPIrisCompat;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;
import net.minecraft.util.Mth;
import qouteall.imm_ptl.core.render.context_management.DimensionRenderHelper;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.imm_ptl.core.portal.global_portals.VerticalConnectingPortal;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;

/**
 * A fluid in the bottom layer of a stacked dimension has nothing below it (the block under it is outside the world, so
 * vanilla's spread into it fails and the column ends flat at the floor plane). Seen from the dimension below it hangs out
 * of the sky. This draws the fall on in the dimension below: the fluid's flowing sides, as vanilla draws a falling
 * column, fading out over {@link #FADE_LENGTH} blocks under the plane, or ending on whatever it meets first. Client only
 * and drawn only: nothing in either world changes.
 * <p>
 * The columns are found on the client tick ({@link #scan}) and drawn at the end of the translucent terrain layer of the
 * dimension below, in its own pass or a portal pass into it, from a buffer of our own (Iris's buffered source has been
 * flushed by then), as translucent terrain. Without a shader pack: in the upper world's light, lightmap and fog, fading
 * by alpha ({@link #drawAsUpperWorld}). With a pack: tagged with the fluid's block id, so the pack draws it as its own
 * water or lava; packs' water programs ignore vertex alpha (BSL, Complementary; their particle and entity programs,
 * tried before, drew it unlike the pack's water), so it fades by coverage alone, thinning out cell by cell in a fixed
 * pattern down its whole length, the same in every pack ({@link #buildPackMesh}). That is thousands of small quads, so
 * the mesh is kept in a vertex buffer around an origin and rebuilt only after a scan or when the camera has moved.
 */
public final class IPStackFallColumns {
    /** Blocks under the plane over which a column fades out. */
    private static final int FADE_LENGTH = 8;
    /** Chunks around the player in which fluid at a plane is looked for (at most; never past the render distance). */
    private static final int MAX_CHUNK_RADIUS = 12;
    /** Ticks between two walks over the planes' layers. */
    private static final int SCAN_INTERVAL = 10;
    /** At most this many columns per dimension (a drained lake over a wide hole). */
    private static final int MAX_COLUMNS = 4096;
    /** With a shader pack: bands per block, and cells across a band, of the dissolve. */
    private static final int PACK_BANDS_PER_BLOCK = 8;
    private static final int PACK_CELLS = 8;
    /** With a shader pack: how far the camera may move (blocks) before the kept mesh is sorted again. */
    private static final double PACK_RESORT_DISTANCE = 2.0;
    /** Vanilla's inset of a fluid's side faces. */
    private static final double INSET = 0.001;

    /** The four sides in the upper dimension, in the order of the footprint's edges: corner i to corner i + 1. */
    private static final Direction[] SIDES = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};

    /**
     * A falling column below the plane, in the lower dimension's coordinates: the footprint's corners on the plane,
     * {@code segments} one-block segments down {@code down}, each with its light and the sides that show.
     */
    private record Column(Vec3[] corners, Vec3 down, int segments, int[] light, int[] sideMasks, Vec3[] normals,
                          Direction[] shadeDirections, int tint, TextureAtlasSprite sprite, AABB bounds, long seed,
                          int upperLight, float[] upperShades, FluidState fluid, BlockPos lowerPos, BlockPos upperPos) {
    }

    /** Columns per lower dimension. Client thread only. */
    private static final Map<ResourceKey<Level>, List<Column>> COLUMNS = new HashMap<>();
    private static int ticksToScan;
    /** Dimensions with a stack floor (their bottom layer's fluids draw no bottom face). Read by chunk builders. */
    private static volatile Set<ResourceKey<Level>> stackFloors = Set.of();
    /** The world above each lower world with columns. Client thread only. */
    private static final Map<ResourceKey<Level>, ResourceKey<Level>> UPPER_OF = new HashMap<>();
    /** The last fog each world with a stack floor was drawn with (without a pack). Render thread only. */
    private static final Map<ResourceKey<Level>, Fog> FOGS = new HashMap<>();
    /** With a pack: the kept mesh per lower world. Render thread only. */
    private static final Map<ResourceKey<Level>, PackMesh> PACK_MESHES = new HashMap<>();

    private static ByteBufferBuilder bytes;

    private IPStackFallColumns() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(IPGlobal.PostClientTickEvent.class, event -> tick());
        NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, IPStackFallColumns::onStage);
    }

    private static void tick() {
        final Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            COLUMNS.clear();
            PACK_MESHES.values().forEach(PackMesh::close);
            PACK_MESHES.clear();
            return;
        }
        if (!ClientWorldLoader.getIsInitialized()) {
            // joining a world: a client tick can come before IP sets up its client worlds
            return;
        }
        if (--ticksToScan > 0) {
            return;
        }
        ticksToScan = SCAN_INTERVAL;
        COLUMNS.clear();
        UPPER_OF.clear();
        updateStackFloors(mc);

        final ClientLevel playerLevel = mc.level;
        final Vec3 playerPos = mc.player.getEyePosition();
        final double reach = mc.options.getEffectiveRenderDistance() * 16.0;
        final int chunkRadius = Math.min(MAX_CHUNK_RADIUS, mc.options.getEffectiveRenderDistance());

        // the player's own floor: falls into the dimension below
        final Portal ownFloor = connector(playerLevel, VerticalConnectingPortal.ConnectorType.floor);
        if (ownFloor != null && playerPos.y - ownFloor.getOriginPos().y < reach) {
            scan(ownFloor, playerPos, chunkRadius);
        }
        // the player's ceiling: falls from the dimension above into the player's
        final Portal ceiling = connector(playerLevel, VerticalConnectingPortal.ConnectorType.ceil);
        if (ceiling != null && ceiling.getOriginPos().y - playerPos.y < reach) {
            final ClientLevel upper = ClientWorldLoader.getOptionalWorld(ceiling.getDestDim());
            final Portal upperFloor = upper == null ? null : connector(upper, VerticalConnectingPortal.ConnectorType.floor);
            if (upperFloor != null && upperFloor.getDestDim() == playerLevel.dimension()) {
                scan(upperFloor, ceiling.transformPoint(playerPos), chunkRadius);
            }
        }
    }

    /** Whether a fluid at height {@code y} of {@code level} sits on a stack floor. Any thread. */
    public static boolean hidesBottomFace(final Level level, final int y) {
        return y == level.getMinBuildHeight() && stackFloors.contains(level.dimension());
    }

    /**
     * The dimensions whose floor is a stack's (going down). When that changes, the bottom sections around the player of
     * the dimensions concerned are rebuilt: sections built before (the global portals come after the first chunks) keep
     * their fluids' bottom faces otherwise.
     */
    private static void updateStackFloors(final Minecraft mc) {
        final Set<ResourceKey<Level>> found = new HashSet<>();
        for (final ClientLevel world : ClientWorldLoader.getClientWorlds()) {
            final Portal floor = connector(world, VerticalConnectingPortal.ConnectorType.floor);
            if (floor != null && floor.transformLocalVecNonScale(new Vec3(0, -1, 0)).y < 0) {
                found.add(world.dimension());
            }
        }
        final Set<ResourceKey<Level>> old = stackFloors;
        if (found.equals(old)) {
            return;
        }
        stackFloors = Set.copyOf(found);
        final Set<ResourceKey<Level>> changed = new HashSet<>(found);
        changed.addAll(old);
        changed.removeIf(dimension -> found.contains(dimension) && old.contains(dimension));
        final int radius = mc.options.getEffectiveRenderDistance() + 1;
        final int centerX = mc.player.getBlockX() >> 4;
        final int centerZ = mc.player.getBlockZ() >> 4;
        for (final ResourceKey<Level> dimension : changed) {
            final ClientLevel world = ClientWorldLoader.getOptionalWorld(dimension);
            final LevelRenderer renderer = ClientWorldLoader.getWorldRenderer(dimension);
            if (world == null || renderer == null) {
                continue;
            }
            final int sectionY = world.getMinSection();
            for (int x = centerX - radius; x <= centerX + radius; x++) {
                for (int z = centerZ - radius; z <= centerZ + radius; z++) {
                    renderer.setSectionDirty(x, sectionY, z);
                }
            }
        }
    }

    private static Portal connector(final ClientLevel level, final VerticalConnectingPortal.ConnectorType type) {
        for (final Portal portal : GlobalPortalStorage.getGlobalPortals(level)) {
            if (portal instanceof VerticalConnectingPortal
                && (type == VerticalConnectingPortal.ConnectorType.floor ? portal.getNormal().y > 0 : portal.getNormal().y < 0)
            ) {
                return portal;
            }
        }
        return null;
    }

    /** The fluid blocks in the bottom layer of {@code floor}'s world around {@code center} (in that world). */
    private static void scan(final Portal floor, final Vec3 center, final int chunkRadius) {
        // an inverted stack (floor to floor) would send the fall up
        final Vec3 down = floor.transformLocalVecNonScale(new Vec3(0, -1, 0));
        if (down.y >= 0) {
            return;
        }
        final ClientLevel upper = (ClientLevel) floor.level();
        final ClientLevel lower = ClientWorldLoader.getOptionalWorld(floor.getDestDim());
        if (lower == null) {
            return;
        }
        final int y = McHelper.getMinY(upper);
        final int centerChunkX = ((int) Math.floor(center.x)) >> 4;
        final int centerChunkZ = ((int) Math.floor(center.z)) >> 4;
        final List<Column> found = new ArrayList<>();
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int cx = centerChunkX - chunkRadius; cx <= centerChunkX + chunkRadius; cx++) {
            for (int cz = centerChunkZ - chunkRadius; cz <= centerChunkZ + chunkRadius; cz++) {
                final LevelChunk chunk = upper.getChunkSource().getChunk(cx, cz, false);
                if (chunk == null) {
                    continue;
                }
                final LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                if (section.hasOnlyAir() || !section.maybeHas(state -> !state.getFluidState().isEmpty())) {
                    continue;
                }
                final int localY = y & 15;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        final BlockState state = section.getBlockState(lx, localY, lz);
                        if (!(state.getBlock() instanceof LiquidBlock) || state.getFluidState().isEmpty()) {
                            continue;
                        }
                        pos.set((cx << 4) + lx, y, (cz << 4) + lz);
                        final Column column = column(upper, lower, floor, down, pos.immutable(), state.getFluidState());
                        if (column != null) {
                            found.add(column);
                            if (found.size() >= MAX_COLUMNS) {
                                COLUMNS.put(lower.dimension(), found);
                                UPPER_OF.put(lower.dimension(), upper.dimension());
                                return;
                            }
                        }
                    }
                }
            }
        }
        if (!found.isEmpty()) {
            COLUMNS.put(lower.dimension(), found);
            UPPER_OF.put(lower.dimension(), upper.dimension());
        }
    }

    private static Column column(
        final ClientLevel upper, final ClientLevel lower, final Portal floor, final Vec3 down, final BlockPos pos,
        final FluidState fluid
    ) {
        // the sides facing another block of the same fluid are inside a wider fall
        int exposed = 0;
        for (int side = 0; side < 4; side++) {
            if (!upper.getFluidState(pos.relative(SIDES[side])).getType().isSame(fluid.getType())) {
                exposed |= 1 << side;
            }
        }
        if (exposed == 0) {
            return null;
        }

        final double x0 = pos.getX() + INSET, x1 = pos.getX() + 1 - INSET;
        final double z0 = pos.getZ() + INSET, z1 = pos.getZ() + 1 - INSET;
        final Vec3[] corners = {
            floor.transformPoint(new Vec3(x0, pos.getY(), z0)),
            floor.transformPoint(new Vec3(x1, pos.getY(), z0)),
            floor.transformPoint(new Vec3(x1, pos.getY(), z1)),
            floor.transformPoint(new Vec3(x0, pos.getY(), z1)),
        };
        final Vec3 middle = corners[0].add(corners[2]).scale(0.5);
        final boolean blockSized = Math.abs(floor.getScaling() - 1.0) < 1e-6;

        // down to whatever it meets first, at most FADE_LENGTH blocks
        final int[] light = new int[FADE_LENGTH];
        final int[] sideMasks = new int[FADE_LENGTH];
        final BlockState legacy = fluid.createLegacyBlock();
        final Vec3[] normals = new Vec3[4];
        final Direction[] shadeDirections = new Direction[4];
        for (int side = 0; side < 4; side++) {
            normals[side] = floor.transformLocalVecNonScale(Vec3.atLowerCornerOf(SIDES[side].getNormal()));
            shadeDirections[side] = Direction.getNearest(normals[side].x, normals[side].y, normals[side].z);
        }
        int segments = 0;
        final BlockPos.MutableBlockPos at = new BlockPos.MutableBlockPos();
        for (; segments < FADE_LENGTH; segments++) {
            at.set(BlockPos.containing(middle.add(down.scale(segments + 0.5))));
            if (!lower.hasChunkAt(at)) {
                break;
            }
            final BlockState state = lower.getBlockState(at);
            if (!state.getCollisionShape(lower, at).isEmpty() || !state.getFluidState().isEmpty()) {
                break;
            }
            light[segments] = LevelRenderer.getLightColor(lower, legacy, at);
            // a side against a full block of the lower dimension (a hole through its roof) is hidden there
            int mask = exposed;
            if (blockSized) {
                for (int side = 0; side < 4; side++) {
                    final BlockPos next = at.relative(shadeDirections[side]);
                    if ((mask & (1 << side)) != 0 && lower.getBlockState(next).isSolidRender(lower, next)) {
                        mask &= ~(1 << side);
                    }
                }
            }
            sideMasks[segments] = mask;
        }
        if (segments == 0) {
            return null;
        }

        final IClientFluidTypeExtensions extensions = IClientFluidTypeExtensions.of(fluid);
        final TextureAtlasSprite sprite = Minecraft.getInstance().getTextureAtlas(InventoryMenu.BLOCK_ATLAS)
            .apply(extensions.getFlowingTexture(fluid, upper, pos));
        final int tint = extensions.getTintColor(fluid, upper, pos);

        AABB bounds = new AABB(corners[0], corners[2]);
        bounds = bounds.minmax(bounds.move(down.scale(segments)));
        // without a pack the column is lit as the fluid above the plane is (vanilla's fluid light: the brighter of the
        // block and the one above, per channel), so the two meet without a step
        final int here = LevelRenderer.getLightColor(upper, pos), above = LevelRenderer.getLightColor(upper, pos.above());
        final int upperLight = Math.max(here & 0xFFFF, above & 0xFFFF) | Math.max(here >>> 16, above >>> 16) << 16;
        final float[] upperShades = new float[4];
        for (int side = 0; side < 4; side++) {
            upperShades[side] = upper.getShade(SIDES[side], true);
        }
        return new Column(
            corners, down, segments, light, sideMasks, normals, shadeDirections, tint, sprite, bounds, pos.asLong(),
            upperLight, upperShades, fluid, BlockPos.containing(middle.add(down.scale(0.5))), pos
        );
    }

    private static void onStage(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_TRANSLUCENT_BLOCKS) {
            return;
        }
        final ClientLevel level = Minecraft.getInstance().level;
        // Iris dispatches the stage in its shadow pass too: the columns cast no shadow
        if (level == null || IrisInterface.invoker.isRenderingShadowMap()) {
            return;
        }
        final Vec3 camera = event.getCamera().getPosition();
        final boolean pack = IPIrisCompat.isShaderPackInUse();
        if (!pack && stackFloors.contains(level.dimension())) {
            // this pass's fog of a world with a stack floor, for the columns drawn below its floor
            FOGS.put(level.dimension(), new Fog(
                RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(), RenderSystem.getShaderFogShape(),
                RenderSystem.getShaderFogColor().clone()
            ));
        }
        if (bytes == null) {
            bytes = new ByteBufferBuilder(1 << 16);
        }
        final List<Column> all = COLUMNS.get(level.dimension());
        if (all == null || all.isEmpty()) {
            return;
        }
        final List<Column> columns = drawnAbove(level, all, camera);
        if (pack) {
            drawPack(level, columns, camera);
            return;
        }
        final BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        for (final Column column : columns) {
            if (event.getFrustum().isVisible(column.bounds())) {
                draw(builder, column, camera);
            }
        }
        final MeshData mesh = builder.build();
        if (mesh != null) {
            mesh.sortQuads(bytes, RenderSystem.getVertexSorting());
            drawAsUpperWorld(mesh, UPPER_OF.get(level.dimension()));
        }
    }

    /**
     * The columns whose fluid above the plane is drawn through the ceiling, so an extension never hangs under fluid
     * that is not drawn: the upper world's section holding it is built by its renderer and within the distance its
     * pass through the ceiling draws, measured as Sodium's culler does from that pass's camera (the camera of this pass
     * mapped through the ceiling): the section's box grown by a block, its horizontal distance and its height
     * difference each under the render distance in blocks (a stack's ceiling has no portal view distance cut). Past that the fluid above is drawn by LODs or not at all.
     */
    private static List<Column> drawnAbove(final ClientLevel level, final List<Column> columns, final Vec3 camera) {
        final Portal ceiling = connector(level, VerticalConnectingPortal.ConnectorType.ceil);
        final ResourceKey<Level> upper = UPPER_OF.get(level.dimension());
        final ClientLevel upperLevel = upper == null ? null : ClientWorldLoader.getOptionalWorld(upper);
        if (ceiling == null || upperLevel == null) {
            return List.of();
        }
        final int chunks = Minecraft.getInstance().options.getEffectiveRenderDistance();
        final float reach = chunks * 16.0F;
        final Vec3 eye = ceiling.transformPoint(camera);
        final int eyeX = Mth.floor(eye.x), eyeY = Mth.floor(eye.y), eyeZ = Mth.floor(eye.z);
        final float fracX = (float) (eye.x - eyeX), fracY = (float) (eye.y - eyeY), fracZ = (float) (eye.z - eyeZ);
        final int sectionY = upperLevel.getMinSection();
        final List<Column> drawn = new ArrayList<>(columns.size());
        for (final Column column : columns) {
            final int sectionX = column.upperPos().getX() >> 4, sectionZ = column.upperPos().getZ() >> 4;
            final int ox = (sectionX << 4) - eyeX, oy = (sectionY << 4) - eyeY, oz = (sectionZ << 4) - eyeZ;
            final float dx = nearestToZero(ox - 1, ox + 17) - fracX;
            final float dy = nearestToZero(oy - 1, oy + 17) - fracY;
            final float dz = nearestToZero(oz - 1, oz + 17) - fracZ;
            if (dx * dx + dz * dz < reach * reach && Math.abs(dy) < reach
                && SodiumInterface.invoker.isSectionBuilt(upper, sectionX, sectionY, sectionZ)
            ) {
                drawn.add(column);
            }
        }
        return drawn;
    }

    /** Sodium's: the end of a range nearest to zero, or zero inside it. */
    private static int nearestToZero(final int min, final int max) {
        int result = 0;
        if (min > 0) {
            result = min;
        }
        if (max < 0) {
            result = max;
        }
        return result;
    }

    /** Without a pack: one column's sides, fading by alpha, in the upper world's light and side shades. */
    private static void draw(final BufferBuilder builder, final Column column, final Vec3 camera) {
        final TextureAtlasSprite sprite = column.sprite();
        // vanilla's falling side: the left half of the flowing texture, the top half per block
        final float u0 = sprite.getU(0.0F), u1 = sprite.getU(0.5F);
        final float v0 = sprite.getV(0.0F), v1 = sprite.getV(0.5F);
        final int r = (column.tint() >> 16) & 0xFF, g = (column.tint() >> 8) & 0xFF, b = column.tint() & 0xFF;
        final int tintAlpha = (column.tint() >>> 24) & 0xFF;
        // a tint given without alpha is opaque
        final float baseAlpha = tintAlpha == 0 ? 1.0F : tintAlpha / 255f;
        for (int side = 0; side < 4; side++) {
            final Vec3 a = column.corners()[side].subtract(camera);
            final Vec3 c = column.corners()[(side + 1) % 4].subtract(camera);
            final Vec3 normal = column.normals()[side];
            final float shade = column.upperShades()[side];
            for (int segment = 0; segment < column.segments(); segment++) {
                if ((column.sideMasks()[segment] & (1 << side)) == 0) {
                    continue;
                }
                final Vec3 top = column.down().scale(segment);
                final Vec3 bottom = column.down().scale(segment + 1);
                quad(builder, a.add(top), c.add(top), c.add(bottom), a.add(bottom),
                    color(r, g, b, shade, baseAlpha * fade(segment)), color(r, g, b, shade, baseAlpha * fade(segment + 1)),
                    u0, u1, v0, v1, column.upperLight(), (float) normal.x, (float) normal.y, (float) normal.z);
            }
        }
    }

    /** With a pack: the kept mesh of a lower world's columns, its origin, and where it was sorted from. */
    private static final class PackMesh {
        private VertexBuffer buffer;
        private List<Column> columns;
        private Vec3 origin;
        private Vec3 sortedFrom;
        private boolean empty;

        private void close() {
            if (buffer != null) {
                buffer.close();
                buffer = null;
            }
        }
    }

    /**
     * With a pack: the kept mesh, rebuilt after a scan or when the camera has moved {@link #PACK_RESORT_DISTANCE}, drawn
     * as translucent terrain with the model view moved to its origin (IP's clip plane for a stage draw follows that
     * model view, so it holds in portal passes too).
     */
    private static void drawPack(final ClientLevel level, final List<Column> columns, final Vec3 camera) {
        final PackMesh mesh = PACK_MESHES.computeIfAbsent(level.dimension(), dimension -> new PackMesh());
        if (!columns.equals(mesh.columns) || mesh.sortedFrom == null
            || mesh.sortedFrom.distanceToSqr(camera) > PACK_RESORT_DISTANCE * PACK_RESORT_DISTANCE
        ) {
            buildPackMesh(mesh, columns, camera);
        }
        if (mesh.empty || mesh.buffer == null || columns.isEmpty()) {
            return;
        }
        final Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.translate(
            (float) (mesh.origin.x - camera.x), (float) (mesh.origin.y - camera.y), (float) (mesh.origin.z - camera.z)
        );
        RenderSystem.applyModelViewMatrix();
        final RenderType type = RenderType.translucent();
        type.setupRenderState();
        mesh.buffer.bind();
        mesh.buffer.drawWithShader(RenderSystem.getModelViewMatrix(), RenderSystem.getProjectionMatrix(), RenderSystem.getShader());
        VertexBuffer.unbind();
        type.clearRenderState();
        modelView.popMatrix();
        RenderSystem.applyModelViewMatrix();
    }

    /**
     * With a pack: every column as the pack's own fluid (the fluid's block id, as Iris tags a fluid in a chunk), in the
     * upper world's light, without baked side shade (packs shade by the normal) and at full alpha: the pack decides the
     * fluid's transparency. It fades by coverage: {@link #PACK_BANDS_PER_BLOCK} bands a block, each of
     * {@link #PACK_CELLS} cells kept with the probability of the fade at that depth, by a fixed hash; runs of kept cells
     * are one quad. Built during the level's rendering, when Iris gives the buffer its terrain vertex format.
     */
    private static void buildPackMesh(final PackMesh mesh, final List<Column> columns, final Vec3 camera) {
        mesh.columns = columns;
        mesh.sortedFrom = camera;
        if (columns.isEmpty()) {
            mesh.empty = true;
            return;
        }
        mesh.origin = Vec3.atLowerCornerOf(BlockPos.containing(columns.get(0).corners()[0]));
        final BufferBuilder builder = new BufferBuilder(bytes, VertexFormat.Mode.QUADS, DefaultVertexFormat.BLOCK);
        for (final Column column : columns) {
            IPIrisCompat.beginFluid(builder, column.fluid(), column.lowerPos());
            drawDissolving(builder, column, mesh.origin);
            IPIrisCompat.endFluid(builder);
        }
        final MeshData data = builder.build();
        mesh.empty = data == null;
        if (data == null) {
            return;
        }
        final Vec3 eye = camera.subtract(mesh.origin);
        data.sortQuads(bytes, VertexSorting.byDistance((float) eye.x, (float) eye.y, (float) eye.z));
        if (mesh.buffer == null) {
            mesh.buffer = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
        mesh.buffer.bind();
        mesh.buffer.upload(data);
        VertexBuffer.unbind();
    }

    private static void drawDissolving(final BufferBuilder builder, final Column column, final Vec3 origin) {
        final TextureAtlasSprite sprite = column.sprite();
        final float u0 = sprite.getU(0.0F), u1 = sprite.getU(0.5F);
        final float v0 = sprite.getV(0.0F), v1 = sprite.getV(0.5F);
        final int color = 0xFF000000 | (column.tint() & 0xFFFFFF);
        final int light = column.upperLight();
        for (int side = 0; side < 4; side++) {
            final Vec3 a = column.corners()[side].subtract(origin);
            final Vec3 c = column.corners()[(side + 1) % 4].subtract(origin);
            final Vec3 normal = column.normals()[side];
            final float nx = (float) normal.x, ny = (float) normal.y, nz = (float) normal.z;
            for (int segment = 0; segment < column.segments(); segment++) {
                if ((column.sideMasks()[segment] & (1 << side)) == 0) {
                    continue;
                }
                for (int band = 0; band < PACK_BANDS_PER_BLOCK; band++) {
                    final double d0 = segment + band / (double) PACK_BANDS_PER_BLOCK;
                    final double d1 = segment + (band + 1) / (double) PACK_BANDS_PER_BLOCK;
                    final float keep = fade((float) ((d0 + d1) * 0.5));
                    final float bv0 = v0 + (v1 - v0) * band / PACK_BANDS_PER_BLOCK;
                    final float bv1 = v0 + (v1 - v0) * (band + 1) / PACK_BANDS_PER_BLOCK;
                    final Vec3 top = column.down().scale(d0);
                    final Vec3 bottom = column.down().scale(d1);
                    final int bandIndex = segment * PACK_BANDS_PER_BLOCK + band;
                    int cell = 0;
                    while (cell < PACK_CELLS) {
                        if (hash(column.seed(), side, bandIndex, cell) >= keep) {
                            cell++;
                            continue;
                        }
                        int end = cell + 1;
                        while (end < PACK_CELLS && hash(column.seed(), side, bandIndex, end) < keep) {
                            end++;
                        }
                        final double t0 = cell / (double) PACK_CELLS, t1 = end / (double) PACK_CELLS;
                        final Vec3 left = a.add(c.subtract(a).scale(t0)), right = a.add(c.subtract(a).scale(t1));
                        final float cu0 = u0 + (u1 - u0) * (float) t0, cu1 = u0 + (u1 - u0) * (float) t1;
                        quad(builder, left.add(top), right.add(top), right.add(bottom), left.add(bottom),
                            color, color, cu0, cu1, bv0, bv1, light, nx, ny, nz);
                        cell = end;
                    }
                }
            }
        }
    }

    /**
     * A side quad, top edge {@code p0 p1}, bottom edge {@code p3 p2}, drawn facing both ways as vanilla draws a fluid's
     * sides: through a falling column one sees its near side and the inside of its far side, two layers of fluid.
     */
    private static void quad(
        final BufferBuilder builder, final Vec3 p0, final Vec3 p1, final Vec3 p2, final Vec3 p3, final int topColor,
        final int bottomColor, final float u0, final float u1, final float v0, final float v1, final int light,
        final float nx, final float ny, final float nz
    ) {
        vertex(builder, p0, topColor, u0, v0, light, nx, ny, nz);
        vertex(builder, p1, topColor, u1, v0, light, nx, ny, nz);
        vertex(builder, p2, bottomColor, u1, v1, light, nx, ny, nz);
        vertex(builder, p3, bottomColor, u0, v1, light, nx, ny, nz);
        vertex(builder, p3, bottomColor, u0, v1, light, -nx, -ny, -nz);
        vertex(builder, p2, bottomColor, u1, v1, light, -nx, -ny, -nz);
        vertex(builder, p1, topColor, u1, v0, light, -nx, -ny, -nz);
        vertex(builder, p0, topColor, u0, v0, light, -nx, -ny, -nz);
    }

    /** A fixed number in [0, 1) for a cell of a column. */
    private static float hash(final long seed, final int side, final int band, final int cell) {
        long h = seed * 0x9E3779B97F4A7C15L + side * 0xC2B2AE3D27D4EB4FL + band * 0x165667B19E3779F9L + cell * 0x27D4EB2F165667C5L;
        h ^= h >>> 33;
        h *= 0xFF51AFD7ED558CCDL;
        h ^= h >>> 33;
        h *= 0xC4CEB9FE1A85EC53L;
        h ^= h >>> 33;
        return (h >>> 40) / (float) (1L << 24);
    }

    /**
     * Without a pack: {@code mesh} as translucent terrain with the upper world's lightmap and its last pass's fog, the
     * light and fog the fluid above the plane is drawn with.
     */
    private static void drawAsUpperWorld(final MeshData mesh, final ResourceKey<Level> upper) {
        final RenderType type = RenderType.translucent();
        type.setupRenderState();
        final DimensionRenderHelper helper = upper == null ? null : ClientWorldLoader.getDimensionRenderHelper(upper);
        if (helper != null) {
            helper.lightmapTexture.turnOnLightLayer();
        }
        final Fog fog = upper == null ? null : FOGS.get(upper);
        final Fog own = fog == null ? null : new Fog(
            RenderSystem.getShaderFogStart(), RenderSystem.getShaderFogEnd(), RenderSystem.getShaderFogShape(),
            RenderSystem.getShaderFogColor().clone()
        );
        if (fog != null) {
            fog.apply();
        }
        BufferUploader.drawWithShader(mesh);
        if (own != null) {
            own.apply();
        }
        type.clearRenderState();
        Minecraft.getInstance().gameRenderer.lightTexture().turnOnLightLayer();
    }

    /** A pass's fog parameters, as {@code RenderSystem} holds them. */
    private record Fog(float start, float end, FogShape shape, float[] color) {
        void apply() {
            RenderSystem.setShaderFogStart(start);
            RenderSystem.setShaderFogEnd(end);
            RenderSystem.setShaderFogShape(shape);
            RenderSystem.setShaderFogColor(color[0], color[1], color[2], color[3]);
        }
    }

    /** The column's opacity {@code depth} blocks under the plane, 1 at the plane and 0 at {@link #FADE_LENGTH}. */
    private static float fade(final float depth) {
        final float t = Math.min(1.0F, depth / (float) FADE_LENGTH);
        return 1.0F - t;
    }

    private static int color(final int r, final int g, final int b, final float shade, final float alpha) {
        return (Math.round(alpha * 255) & 0xFF) << 24 | Math.round(r * shade) << 16 | Math.round(g * shade) << 8 | Math.round(b * shade);
    }

    private static void vertex(final BufferBuilder builder, final Vec3 pos, final int color, final float u, final float v,
                               final int light, final float nx, final float ny, final float nz) {
        builder.addVertex((float) pos.x, (float) pos.y, (float) pos.z, color, u, v, 0, light, nx, ny, nz);
    }
}
