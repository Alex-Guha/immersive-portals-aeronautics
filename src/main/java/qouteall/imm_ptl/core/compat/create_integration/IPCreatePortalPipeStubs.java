/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.FluidTransportBehaviour.AttachmentTypes.ComponentPartials;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;

/**
 * The far half of a pipe going into a portal when no pipe waits on the other side. {@link IPCreatePortalPipeModel}
 * fills the near half block up to the plane; past the plane the portal shows the far world, where a pipe there would
 * draw the rest. Without one, the pipe looked cut off at the plane. So the far portal block gets the rest of the pipe:
 * Create's connection piece from the plane to the block face and an open end's rim there, as the pipe would end if it
 * came out of the portal and stopped.
 * <p>
 * The portal blocks themselves are invisible (never meshed), so the piece is added to the far world's chunk mesh
 * through NeoForge's {@link AddSectionGeometryEvent} (Sodium runs it too). Which far blocks get one is found on the
 * client thread every {@link #SCAN_TICKS} ticks: for every pipe end at a portal block of a nether-style portal in any
 * client world, the matching portal block across the portal, when its own pipe spot has no pipe open towards it.
 * Sections whose pieces changed are re-meshed.
 */
public final class IPCreatePortalPipeStubs {
    private static final int SCAN_TICKS = 10;
    /** Portal blocks walked per portal at most (a 64x64 portal). */
    private static final int MAX_CELLS = 64 * 64;
    private static final double PLANE_EPSILON = 1.0e-3;
    private static final RandomSource RANDOM = RandomSource.create();

    /** A portal block that gets the far half of a pipe running out of it towards {@code toward}. */
    private record Stub(BlockPos pos, Direction toward) {
    }

    /** Per dimension, per section ({@link SectionPos#asLong}), the stubs in it. Replaced whole by the scan. */
    private static volatile Map<ResourceKey<Level>, Map<Long, List<Stub>>> stubs = Map.of();
    private static int tick;

    private IPCreatePortalPipeStubs() {
    }

    public static void onClientTick(final IPGlobal.PostClientTickEvent event) {
        if (++tick % SCAN_TICKS != 0) {
            return;
        }
        final Map<ResourceKey<Level>, ClientLevel> worlds = new HashMap<>();
        for (final ClientLevel world : ClientWorldLoader.getClientWorlds()) {
            worlds.put(world.dimension(), world);
        }
        final Map<ResourceKey<Level>, Map<Long, List<Stub>>> found = new HashMap<>();
        for (final ClientLevel world : worlds.values()) {
            for (final Entity entity : world.entitiesForRendering()) {
                if (entity instanceof BreakablePortalEntity portal) {
                    scan(world, portal, worlds, found);
                }
            }
        }
        final Map<ResourceKey<Level>, Map<Long, List<Stub>>> previous = stubs;
        stubs = found;
        markChanged(previous, found);
        markChanged(found, previous);
    }

    private static void scan(
        final ClientLevel world, final Portal portal, final Map<ResourceKey<Level>, ClientLevel> worlds,
        final Map<ResourceKey<Level>, Map<Long, List<Stub>>> found
    ) {
        if (portal.isRemoved() || !portal.isTeleportable() || Math.abs(portal.getScale() - 1) > 1.0e-6
            || IPShipPortals.isShipMounted(portal)) {
            return;
        }
        final ClientLevel farWorld = worlds.get(portal.getDestDim());
        final Vec3 normal = portal.getNormal();
        final Direction front = Direction.getNearest(normal.x, normal.y, normal.z);
        if (farWorld == null || Vec3.atLowerCornerOf(front.getNormal()).dot(normal) < 1 - PLANE_EPSILON) {
            return;
        }
        final Vec3 inDir = portal.transformLocalVecNonScale(Vec3.atLowerCornerOf(front.getOpposite().getNormal()));
        final Direction farOut = Direction.getNearest(inDir.x, inDir.y, inDir.z);
        if (Vec3.atLowerCornerOf(farOut.getNormal()).dot(inDir) < 1 - PLANE_EPSILON) {
            return;
        }
        final AABB box = portal.getBoundingBox().deflate(PLANE_EPSILON);
        final BlockPos min = BlockPos.containing(box.minX, box.minY, box.minZ);
        final BlockPos max = BlockPos.containing(box.maxX, box.maxY, box.maxZ);
        if ((long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1) > MAX_CELLS) {
            return;
        }
        for (final BlockPos cell : BlockPos.betweenClosed(min, max)) {
            final Vec3 centre = Vec3.atCenterOf(cell);
            if (Math.abs(portal.getDistanceToPlane(centre)) > PLANE_EPSILON
                || !IPCreatePortalPipes.isPlaceholderFacing(world.getBlockState(cell), front)
                || !opensTowards(world, cell.relative(front), front.getOpposite())) {
                continue;
            }
            final BlockPos farCell = BlockPos.containing(portal.transformPoint(centre));
            if (!IPCreatePortalPipes.isPlaceholderFacing(farWorld.getBlockState(farCell), farOut)
                || opensTowards(farWorld, farCell.relative(farOut), farOut.getOpposite())) {
                continue;
            }
            found.computeIfAbsent(farWorld.dimension(), d -> new HashMap<>())
                .computeIfAbsent(SectionPos.asLong(farCell), s -> new ArrayList<>())
                .add(new Stub(farCell.immutable(), farOut));
        }
    }

    /** A pipe at {@code pos} open towards {@code side}. */
    private static boolean opensTowards(final ClientLevel world, final BlockPos pos, final Direction side) {
        final FluidTransportBehaviour pipe = FluidPropagator.getPipe(world, pos);
        return pipe != null && pipe.canHaveFlowToward(world.getBlockState(pos), side);
    }

    /** Re-meshes the sections of {@code from} whose stubs differ in {@code to}. */
    private static void markChanged(
        final Map<ResourceKey<Level>, Map<Long, List<Stub>>> from, final Map<ResourceKey<Level>, Map<Long, List<Stub>>> to
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
                renderer.setSectionDirty(SectionPos.x(key), SectionPos.y(key), SectionPos.z(key));
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
        final BlockState pipe = AllBlocks.FLUID_PIPE.getDefaultState();
        final int light = LevelRenderer.getLightColor(region, stub.pos());
        final PoseStack poses = context.getPoseStack();
        poses.pushPose();
        poses.translate(stub.pos().getX() - origin.getX(), stub.pos().getY() - origin.getY(), stub.pos().getZ() - origin.getZ());
        // the rim and the arm under it at the block face, Create's open end; one more arm piece back to the plane
        emit(context, region, poses, partial(ComponentPartials.RIM, stub.toward()), pipe, light, null, 0);
        emit(context, region, poses, partial(ComponentPartials.RIM_CONNECTOR, stub.toward()), pipe, light, null, 0);
        emit(context, region, poses, partial(ComponentPartials.CONNECTION, stub.toward()), pipe, light, stub.toward().getOpposite(), 4 / 16f);
        poses.popPose();
    }

    private static BakedModel partial(final ComponentPartials partial, final Direction side) {
        return AllPartialModels.PIPE_ATTACHMENTS.get(partial).get(side).get();
    }

    /** All of {@code model}'s quads, moved {@code distance} blocks towards {@code shift} (none when null). */
    private static void emit(
        final AddSectionGeometryEvent.SectionRenderingContext context, final BlockAndTintGetter region, final PoseStack poses,
        final BakedModel model, final BlockState state, final int light, final @Nullable Direction shift, final float distance
    ) {
        poses.pushPose();
        if (shift != null) {
            poses.translate(shift.getStepX() * distance, shift.getStepY() * distance, shift.getStepZ() * distance);
        }
        for (final RenderType renderType : model.getRenderTypes(state, RANDOM, ModelData.EMPTY)) {
            final VertexConsumer consumer = context.getOrCreateChunkBuffer(renderType);
            for (final Direction side : SIDES) {
                for (final BakedQuad quad : model.getQuads(state, side, RANDOM, ModelData.EMPTY, renderType)) {
                    final float shade = region.getShade(quad.getDirection(), quad.isShade());
                    consumer.putBulkData(poses.last(), quad, shade, shade, shade, 1, light, OverlayTexture.NO_OVERLAY);
                }
            }
        }
        poses.popPose();
    }

    /** Every cull face and none: the stub draws all its faces. */
    private static final Direction[] SIDES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
}
