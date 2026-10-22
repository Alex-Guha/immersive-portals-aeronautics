/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.mixin_create.IPLevelRendererSectionInvoker;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * The far half of a track's bridge into a portal when the other end has no track ({@link IPCreatePortalTracks}): a
 * track at a portal is a portal-shaped track whose rails run half a block into the portal block, up to the plane
 * ({@link IPCreatePortalTrackModel}); past the plane the portal shows the far world, where a track at the exit spot
 * would draw the other half. Without one the bridge looked cut off at the plane. So the far portal block gets that half:
 * the part of Create's portal-track model inside the portal block, as a portal track at the exit spot would draw it,
 * clipped at the portal block's face (Create's rails run as single quads from the track block to the plane).
 * <p>
 * As for pipes ({@link IPCreatePortalPipeStubs}): the portal blocks are never meshed, so the piece is added to the far
 * world's chunk mesh through NeoForge's {@link AddSectionGeometryEvent}; which far blocks get one is found on the client
 * thread before the frame after a block entity or portal block changes (a portal track has a block entity), and every
 * {@link #SCAN_TICKS} ticks for the rest. Not for ship-mounted portals (Create's portal tracks never link there).
 */
public final class IPCreatePortalTrackStubs {
    private static final int SCAN_TICKS = 10;
    /** Portal blocks walked per portal at most (a 64x64 portal). */
    private static final int MAX_CELLS = 64 * 64;
    private static final double PLANE_EPSILON = 1.0e-3;
    private static final float FACE_EPSILON = 1.0e-4f;
    private static final RandomSource RANDOM = RandomSource.create();
    /** Every cull face and none: the stub draws all its faces. */
    private static final Direction[] SIDES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};

    /**
     * A far portal block {@code cell} that gets the half of a bridge a portal track {@code track} at {@code spot} (the
     * exit spot, empty) would draw into it.
     */
    private record Stub(BlockPos cell, BlockPos spot, BlockState track) {
    }

    /** Per dimension, per section ({@link SectionPos#asLong}) of the far portal block, the stubs in it. */
    private static volatile Map<ResourceKey<Level>, Map<Long, List<Stub>>> stubs = Map.of();
    private static int tick;
    private static boolean changed;
    private static boolean changedImportant;

    private IPCreatePortalTrackStubs() {
    }

    /** Client block change in any world (main thread). Portal tracks have a block entity; portal blocks matter too. */
    public static void onBlockChanged(final BlockState oldState, final BlockState newState, final int flags) {
        if (oldState.getBlock() instanceof TrackBlock || newState.getBlock() instanceof TrackBlock
            || oldState.getBlock() == qouteall.imm_ptl.core.portal.PortalPlaceholderBlock.instance
            || newState.getBlock() == qouteall.imm_ptl.core.portal.PortalPlaceholderBlock.instance) {
            changed = true;
            changedImportant |= (flags & Block.UPDATE_IMMEDIATE) != 0;
        }
    }

    public static void onClientTick(final IPGlobal.PostClientTickEvent event) {
        if (++tick % SCAN_TICKS == 0) {
            search(false);
        }
    }

    /** Before each frame: the search for a block change, so both halves are meshed in the frame the track changes. */
    public static void onPreGameRender(final IPGlobal.PreGameRenderEvent event) {
        if (changed) {
            search(changedImportant);
        }
    }

    private static void search(final boolean important) {
        changed = false;
        changedImportant = false;
        final Map<ResourceKey<Level>, ClientLevel> worlds = new HashMap<>();
        for (final ClientLevel world : ClientWorldLoader.getClientWorlds()) {
            worlds.put(world.dimension(), world);
        }
        final Map<ResourceKey<Level>, Map<Long, List<Stub>>> found = new HashMap<>();
        for (final ClientLevel world : worlds.values()) {
            for (final Entity entity : world.entitiesForRendering()) {
                if (entity instanceof final Portal portal) {
                    scan(world, portal, worlds, found);
                }
            }
        }
        final Map<ResourceKey<Level>, Map<Long, List<Stub>>> previous = stubs;
        stubs = found;
        markChanged(previous, found, important);
        markChanged(found, previous, important);
    }

    /** The portal blocks of {@code portal} with a portal track in front of them on its front side. */
    private static void scan(
        final ClientLevel world, final Portal portal, final Map<ResourceKey<Level>, ClientLevel> worlds,
        final Map<ResourceKey<Level>, Map<Long, List<Stub>>> found
    ) {
        if (portal.isRemoved() || !portal.isTeleportable() || Math.abs(portal.getScale() - 1) > 1.0e-6
            || IPShipPortals.clusterMountOf(portal) != null) {
            return;
        }
        final Vec3 normal = portal.getNormal();
        final Direction front = Direction.getNearest(normal.x, normal.y, normal.z);
        if (front.getAxis().isVertical() || Vec3.atLowerCornerOf(front.getNormal()).dot(normal) < 1 - PLANE_EPSILON) {
            return;
        }
        final ClientLevel farWorld = worlds.get(portal.getDestDim());
        if (farWorld == null) {
            return;
        }
        final AABB cells = portal.getBoundingBox().deflate(PLANE_EPSILON);
        final BlockPos min = BlockPos.containing(cells.minX, cells.minY, cells.minZ);
        final BlockPos max = BlockPos.containing(cells.maxX, cells.maxY, cells.maxZ);
        if ((long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1) > MAX_CELLS) {
            return;
        }
        // a train going into the portal from the front side moves this way, and comes out of the far end that way
        final Direction in = front.getOpposite();
        final Vec3 outVector = portal.transformLocalVec(Vec3.atLowerCornerOf(in.getNormal()));
        final Direction out = Direction.getNearest(outVector.x, outVector.y, outVector.z);
        if (out.getAxis().isVertical()) {
            return;
        }
        for (final BlockPos cell : BlockPos.betweenClosed(min, max)) {
            final Vec3 centre = Vec3.atCenterOf(cell);
            if (Math.abs(portal.getDistanceToPlane(centre)) > PLANE_EPSILON
                || !IPCreatePortalPipes.isPlaceholderFacing(world.getBlockState(cell), front)) {
                continue;
            }
            final BlockState track = world.getBlockState(cell.relative(front));
            if (!(track.getBlock() instanceof TrackBlock) || track.getValue(TrackBlock.SHAPE) != TrackShape.asPortal(in)) {
                continue;
            }
            final BlockPos farCell = BlockPos.containing(portal.transformPoint(centre));
            if (!IPCreatePortalPipes.isPlaceholderFacing(farWorld.getBlockState(farCell), out)) {
                continue;
            }
            final BlockPos spot = farCell.relative(out);
            final BlockState there = farWorld.getBlockState(spot);
            final TrackShape farShape = TrackShape.asPortal(out.getOpposite());
            if (there.getBlock() instanceof TrackBlock && there.getValue(TrackBlock.SHAPE) == farShape) {
                continue; // the far end's own track draws its half
            }
            found.computeIfAbsent(farWorld.dimension(), d -> new HashMap<>())
                .computeIfAbsent(SectionPos.asLong(farCell), s -> new ArrayList<>())
                .add(new Stub(farCell.immutable(), spot.immutable(), track.setValue(TrackBlock.SHAPE, farShape)));
        }
    }

    /** Re-meshes the sections of {@code from} whose stubs differ in {@code to}, at once when {@code important}. */
    private static void markChanged(
        final Map<ResourceKey<Level>, Map<Long, List<Stub>>> from, final Map<ResourceKey<Level>, Map<Long, List<Stub>>> to,
        final boolean important
    ) {
        for (final Map.Entry<ResourceKey<Level>, Map<Long, List<Stub>>> dimension : from.entrySet()) {
            final Map<Long, List<Stub>> other = to.getOrDefault(dimension.getKey(), Map.of());
            LevelRenderer renderer = null;
            for (final Map.Entry<Long, List<Stub>> section : dimension.getValue().entrySet()) {
                if (Objects.equals(section.getValue(), other.get(section.getKey()))) {
                    continue;
                }
                if (renderer == null) {
                    if (ClientWorldLoader.getClientWorlds().stream().noneMatch(w -> w.dimension() == dimension.getKey())) {
                        break; // its world is gone, and with it the meshes
                    }
                    renderer = ClientWorldLoader.getWorldRenderer(dimension.getKey());
                }
                final long key = section.getKey();
                ((IPLevelRendererSectionInvoker) renderer).ip$setSectionDirty(
                    SectionPos.x(key), SectionPos.y(key), SectionPos.z(key), important
                );
            }
        }
    }

    /** Game bus: adds the stubs of a section being meshed. */
    public static void onAddSectionGeometry(final AddSectionGeometryEvent event) {
        final Map<Long, List<Stub>> sections = stubs.get(event.getLevel().dimension());
        final List<Stub> here = sections == null ? null : sections.get(SectionPos.asLong(event.getSectionOrigin()));
        if (here == null) {
            return;
        }
        final BlockPos origin = event.getSectionOrigin().immutable();
        event.addRenderer(context -> {
            for (final Stub stub : here) {
                render(context, origin, stub);
            }
        });
    }

    private static void render(final AddSectionGeometryEvent.SectionRenderingContext context, final BlockPos origin, final Stub stub) {
        final BlockAndTintGetter region = context.getRegion();
        final BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(stub.track());
        final ModelData data = model.getModelData(region, stub.spot(), stub.track(), ModelData.EMPTY);
        final Direction toPortal = Direction.getNearest(
            stub.cell().getX() - stub.spot().getX(), stub.cell().getY() - stub.spot().getY(), stub.cell().getZ() - stub.spot().getZ());
        final int light = LevelRenderer.getLightColor(region, stub.cell());
        final PoseStack poses = context.getPoseStack();
        poses.pushPose();
        poses.translate(stub.spot().getX() - origin.getX(), stub.spot().getY() - origin.getY(), stub.spot().getZ() - origin.getZ());
        for (final RenderType renderType : model.getRenderTypes(stub.track(), RANDOM, data)) {
            final VertexConsumer consumer = context.getOrCreateChunkBuffer(renderType);
            for (final Direction side : SIDES) {
                for (final BakedQuad quad : model.getQuads(stub.track(), side, RANDOM, data, renderType)) {
                    final BakedQuad inPortal = clip(quad, toPortal);
                    if (inPortal != null) {
                        final float shade = region.getShade(inPortal.getDirection(), inPortal.isShade());
                        consumer.putBulkData(poses.last(), inPortal, shade, shade, shade, 1, light, OverlayTexture.NO_OVERLAY);
                    }
                }
            }
        }
        poses.popPose();
    }

    /**
     * The part of {@code quad} (block-local) past the block's face towards {@code toPortal}, positions and texture
     * coordinates interpolated; null when none of it is.
     */
    private static @Nullable BakedQuad clip(final BakedQuad quad, final Direction toPortal) {
        final int[] vertices = quad.getVertices();
        final int stride = vertices.length / 4;
        final int axis = toPortal.getAxis().ordinal(); // x, y, z are the first three ints of each vertex
        final boolean positive = toPortal.getAxisDirection() == Direction.AxisDirection.POSITIVE;
        final float face = positive ? 1 : 0;
        final float[] along = new float[4];
        final boolean[] inside = new boolean[4];
        boolean all = true;
        boolean none = true;
        for (int v = 0; v < 4; v++) {
            along[v] = Float.intBitsToFloat(vertices[v * stride + axis]);
            inside[v] = positive ? along[v] >= face - FACE_EPSILON : along[v] <= face + FACE_EPSILON;
            all &= inside[v];
            none &= !inside[v];
        }
        if (all) {
            return quad;
        }
        if (none) {
            return null;
        }
        final List<int[]> kept = new ArrayList<>(5);
        for (int v = 0; v < 4; v++) {
            final int next = (v + 1) % 4;
            if (inside[v]) {
                kept.add(java.util.Arrays.copyOfRange(vertices, v * stride, v * stride + stride));
            }
            if (inside[v] != inside[next]) {
                final float t = (face - along[v]) / (along[next] - along[v]);
                final int[] vertex = java.util.Arrays.copyOfRange(vertices, v * stride, v * stride + stride);
                for (final int i : new int[]{0, 1, 2, 4, 5}) { // position, texture coordinates
                    final float a = Float.intBitsToFloat(vertices[v * stride + i]);
                    final float b = Float.intBitsToFloat(vertices[next * stride + i]);
                    vertex[i] = Float.floatToRawIntBits(a + (b - a) * t);
                }
                vertex[axis] = Float.floatToRawIntBits(face);
                kept.add(vertex);
            }
        }
        if (kept.size() < 3 || kept.size() > 4) {
            return null;
        }
        while (kept.size() < 4) {
            kept.add(kept.get(kept.size() - 1));
        }
        final int[] clipped = new int[stride * 4];
        for (int v = 0; v < 4; v++) {
            System.arraycopy(kept.get(v), 0, clipped, v * stride, stride);
        }
        return new BakedQuad(clipped, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
    }
}
