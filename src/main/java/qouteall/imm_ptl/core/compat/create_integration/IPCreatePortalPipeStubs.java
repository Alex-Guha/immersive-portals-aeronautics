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
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.Effect;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.LevelAccessor;
import org.joml.Quaternionf;
import org.joml.Vector3f;
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
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.mixin_create.IPLevelRendererSectionInvoker;
import qouteall.imm_ptl.core.portal.Portal;

import static qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalEnds.SABLE;

/**
 * The far half of a pipe going into a portal when no pipe waits on the other side. {@link IPCreatePortalPipeModel}
 * fills the near half block up to the plane; past the plane the portal shows the far world, where a pipe there would
 * draw the rest. Without one, the pipe looked cut off at the plane. So the far portal block gets the rest of the pipe:
 * Create's connection piece from the plane to the block face and an open end's rim there, as the pipe would end if it
 * came out of the portal and stopped.
 * <p>
 * The portal blocks themselves are invisible (never meshed), so the piece is added to the far world's chunk mesh
 * through NeoForge's {@link AddSectionGeometryEvent} (Sodium runs it too). Which far blocks get one is found on the
 * client thread: for every pipe end at a portal block of a nether-style portal in any client world, the matching portal
 * block across the portal, when its own pipe spot has no pipe open towards it. Sections whose pieces changed are
 * re-meshed.
 * <p>
 * The search runs before the frame after a pipe or portal block changes in any client world, and every
 * {@link #SCAN_TICKS} ticks for the rest (portals and chunks coming and going). The far section is re-meshed with the
 * change's own priority: a block the player places or breaks is meshed in the frame it changes (vanilla and Sodium,
 * within 16 blocks of the camera), so the far half must be too, or one half of the pipe shows up before the other.
 * <p>
 * Ships: the portal blocks of a ship-mounted portal's ship side are in the ship's plot. A pipe there gets its far half
 * in the fixed side's world mesh as above. A far half on the ship (a pipe at the fixed side, none on the ship) is
 * drawn with Flywheel instead, as instances of the embedding Sable draws that ship with, so it moves with the ship:
 * Sable meshes ship sections with its own compiler, which runs no {@link AddSectionGeometryEvent}.
 * Mixins: {@code MixinClientLevel_PortalPipeStubs}, {@code IPLevelRendererSectionInvoker}.
 */
public final class IPCreatePortalPipeStubs {
    private static final int LIGHT_TICKS = 10;
    private static final RandomSource RANDOM = RandomSource.create();

    /** A portal block that gets the far half of a pipe running out of it towards {@code toward}. */
    private record Stub(BlockPos pos, Direction toward) {
    }

    /** The same on a ship: a portal block in {@code ship}'s plot, drawn with the ship's {@code embedding}. */
    private record ShipStub(BlockPos pos, Direction toward, IPCreatePortalShafts.Frame ship, @Nullable VisualEmbedding embedding) {
    }

    /** Per client world, the effect drawing its ship stubs (weak: client worlds come and go). */
    private static final Map<ClientLevel, ShipStubs> SHIP_EFFECTS = new WeakHashMap<>();

    /** Per dimension, per section ({@link SectionPos#asLong}), the stubs in it. Replaced whole by the scan. */
    private static volatile Map<ResourceKey<Level>, Map<Long, List<Stub>>> stubs = Map.of();
    /** World pipe faces at a portal block whose space a belt or shaft across the portal owns, by section. */
    private static volatile Map<ResourceKey<Level>, Map<Long, List<Stub>>> outrankedFaces = Map.of();
    /** The same as keys ({@link #faceKey}), for the pipe model on Sodium's worker threads. */
    private static volatile Set<Long> outrankedKeys = Set.of();
    private IPCreatePortalPipeStubs() {
    }

    /** The stubs found in a walk. */
    static final class PipeScan implements IPCreatePortalEndScan.Scan {
        private final Map<ResourceKey<Level>, Map<Long, List<Stub>>> found = new HashMap<>();
        private final Map<ClientLevel, List<ShipStub>> onShips = new IdentityHashMap<>();
        private final Map<ResourceKey<Level>, Map<Long, List<Stub>>> outranked = new HashMap<>();

        @Override
        public boolean wants(final IPCreatePortalEndScan.Side side) {
            return side.farWorld() != null;
        }

        @Override
        public void cell(final IPCreatePortalEndScan.Side side, final BlockPos cell, final Vec3 worldCentre) {
            final ClientLevel world = side.world();
            final Direction front = side.front();
            final Direction behind = front.getOpposite();
            if (!opensTowards(world, cell.relative(front), behind)) {
                return;
            }
            final Portal portal = side.portal();
            final IPCreatePortalShafts.Frame frame = side.frame();
            final ClientLevel farWorld = side.farWorld();
            final UUID farShip = side.farShip();
            final Quaternionf toFarWorld = side.toFarWorld();
            final Vec3 farCentre = portal.transformPoint(worldCentre);
            if (farShip == null) {
                final Map<Long, List<Stub>> outrankedHere = outranked.computeIfAbsent(world.dimension(), d -> new HashMap<>());
                stub(farWorld, null, farCentre, toFarWorld, behind, found, onShips,
                    frame == null ? new Stub(cell.relative(front).immutable(), behind) : null, outrankedHere);
            } else if (SABLE) {
                // the other side of a ship-mounted portal is on a ship
                for (final IPCreatePortalShafts.Frame ship : IPCreateShaftShips.shipsTouching(farWorld, AABB.ofSize(farCentre, 2, 2, 2))) {
                    if (ship.ship().equals(farShip)
                        && stub(farWorld, ship, farCentre, toFarWorld, behind, found, onShips, null, new HashMap<>())) {
                        break;
                    }
                }
            }
        }

        @Override
        public void finish(final Collection<ClientLevel> worlds, final boolean important) {
            final Map<ResourceKey<Level>, Map<Long, List<Stub>>> previous = stubs;
            stubs = found;
            markChanged(previous, found, important);
            markChanged(found, previous, important);
            final Map<ResourceKey<Level>, Map<Long, List<Stub>>> previouslyOutranked = outrankedFaces;
            outrankedFaces = outranked;
            final Set<Long> keys = new HashSet<>();
            outranked.values().forEach(sections -> sections.values().forEach(list -> list.forEach(face -> keys.add(faceKey(face.pos(), face.toward())))));
            outrankedKeys = keys;
            markChanged(previouslyOutranked, outranked, important);
            markChanged(outranked, previouslyOutranked, important);

            for (final ClientLevel world : worlds) {
                final List<ShipStub> here = onShips.getOrDefault(world, List.of());
                ShipStubs effect = SHIP_EFFECTS.get(world);
                if (effect == null) {
                    if (here.isEmpty()) {
                        continue;
                    }
                    effect = new ShipStubs(world);
                    SHIP_EFFECTS.put(world, effect);
                }
                final VisualizationManagerImpl manager = effect.attach();
                final List<ShipStub> drawn = new ArrayList<>(here.size());
                for (final ShipStub stub : here) {
                    final VisualEmbedding embedding = manager == null ? null : IPCreateShaftShipsClient.embeddingOf(manager, stub.ship());
                    if (embedding != null) {
                        drawn.add(new ShipStub(stub.pos(), stub.toward(), stub.ship(), embedding));
                    }
                }
                if (!effect.stubs.equals(drawn)) {
                    effect.stubs = List.copyOf(drawn);
                }
            }
        }
    }

    /**
     * The far half for a pipe whose portal block maps to {@code farCentre} (in {@code frame}, null: the world), unless a
     * pipe there is open towards it, or a belt or shaft there owns the space between ({@link IPCreatePortalEnds}): then
     * {@code outrankedFace} (the near pipe's face, a world pipe's) is added to {@code outranked}. False if no far portal block is there.
     */
    private static boolean stub(
        final ClientLevel farWorld, final IPCreatePortalShafts.@Nullable Frame frame, final Vec3 farCentre, final Quaternionf toFarWorld,
        final Direction behind, final Map<ResourceKey<Level>, Map<Long, List<Stub>>> found, final Map<ClientLevel, List<ShipStub>> onShips,
        final @Nullable Stub outrankedFace, final Map<Long, List<Stub>> outranked
    ) {
        final Vec3 local = frame == null ? farCentre : frame.toLocal(farCentre);
        final BlockPos farCell = BlockPos.containing(local);
        if (frame != null && !frame.holds(farWorld, farCell)) {
            return false;
        }
        final Quaternionf toLocal = IPCreatePortalEnds.snap(
            frame == null ? new Quaternionf(toFarWorld) : new Quaternionf(frame.rotation()).conjugate().mul(toFarWorld));
        final Vector3f out = toLocal.transform(new Vector3f(behind.getStepX(), behind.getStepY(), behind.getStepZ()));
        final Direction farOut = Direction.getNearest(out.x, out.y, out.z);
        if (!IPCreatePortalPipes.isPlaceholderFacing(farWorld.getBlockState(farCell), farOut)) {
            return false;
        }
        if (opensTowards(farWorld, farCell.relative(farOut), farOut.getOpposite())) {
            return true; // the far pipe draws the rest itself
        }
        if (IPCreatePortalEnds.outranked(farWorld, farCell.relative(farOut), farOut.getOpposite(), IPCreatePortalEnds.Kind.PIPE)) {
            if (outrankedFace != null) {
                outranked.computeIfAbsent(SectionPos.asLong(outrankedFace.pos()), s -> new ArrayList<>()).add(outrankedFace);
            }
            return true; // a belt or shaft there owns the space between: no stub, and the near pipe draws no filler
        }
        if (frame == null) {
            found.computeIfAbsent(farWorld.dimension(), d -> new HashMap<>())
                .computeIfAbsent(SectionPos.asLong(farCell), s -> new ArrayList<>())
                .add(new Stub(farCell.immutable(), farOut));
        } else {
            onShips.computeIfAbsent(farWorld, w -> new ArrayList<>()).add(new ShipStub(farCell.immutable(), farOut, frame, null));
        }
        return true;
    }

    /**
     * Whether a belt or shaft across the portal owns the space in front of this pipe face ({@link IPCreatePortalEnds}):
     * the pipe then keeps its own end and draws no filler. Any thread. Goes by position only (not the dimension): a
     * pipe at the same spot in another dimension facing a portal block the same way would share the answer.
     */
    public static boolean isOutranked(final BlockPos pos, final Direction side) {
        final Set<Long> keys = outrankedKeys;
        return !keys.isEmpty() && keys.contains(faceKey(pos, side));
    }

    private static long faceKey(final BlockPos pos, final Direction side) {
        return pos.asLong() * 6 + side.ordinal();
    }

    /** A pipe at {@code pos} open towards {@code side}. */
    private static boolean opensTowards(final ClientLevel world, final BlockPos pos, final Direction side) {
        final FluidTransportBehaviour pipe = FluidPropagator.getPipe(world, pos);
        return pipe != null && pipe.canHaveFlowToward(world.getBlockState(pos), side);
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

    // ---------------------------------------------------------------------------------------------------------------
    // Far halves on ships

    /** The ship stubs of one client world, as one Flywheel effect in that world's visualization. */
    private static final class ShipStubs implements Effect {
        private final ClientLevel level;
        volatile List<ShipStub> stubs = List.of();
        private WeakReference<VisualizationManagerImpl> manager = new WeakReference<>(null);

        ShipStubs(final ClientLevel level) {
            this.level = level;
        }

        @Override
        public LevelAccessor level() {
            return level;
        }

        @Override
        public EffectVisual<?> visualize(final VisualizationContext context, final float partialTick) {
            return new ShipStubsVisual(this);
        }

        /**
         * Adds the effect to its world's visualization, again whenever Flywheel made a new one. Flywheel serves a world's
         * visualization only while it is {@code Minecraft.level}.
         */
        @Nullable VisualizationManagerImpl attach() {
            final Minecraft minecraft = Minecraft.getInstance();
            final ClientLevel current = minecraft.level;
            final VisualizationManagerImpl now;
            minecraft.level = level;
            try {
                now = VisualizationManagerImpl.get(level);
            } finally {
                minecraft.level = current;
            }
            if (now != null && now != manager.get()) {
                manager = new WeakReference<>(now);
                now.effects().queueAdd(this);
            }
            return now;
        }
    }

    /** Create's open pipe end (rim, the arm under it, one more arm piece back to the plane), as the chunk stub draws it. */
    private static final class ShipStubsVisual implements EffectVisual<ShipStubs>, SimpleDynamicVisual {
        private final ShipStubs effect;
        private List<ShipStub> shown = List.of();
        private final List<TransformedInstance> instances = new ArrayList<>();
        /** The stub of each instance in {@link #instances}. */
        private final List<ShipStub> owners = new ArrayList<>();
        private long litAt;

        ShipStubsVisual(final ShipStubs effect) {
            this.effect = effect;
        }

        @Override
        public void beginFrame(final DynamicVisual.Context frame) {
            final List<ShipStub> now = effect.stubs;
            if (now != shown) {
                rebuild(now);
            }
            final long time = effect.level.getGameTime();
            if (Math.abs(time - litAt) >= LIGHT_TICKS) {
                litAt = time;
                for (int i = 0; i < instances.size(); i++) {
                    instances.get(i).light(LevelRenderer.getLightColor(effect.level, owners.get(i).pos())).setChanged();
                }
            }
        }

        private void rebuild(final List<ShipStub> now) {
            delete();
            shown = now;
            for (final ShipStub stub : now) {
                final VisualEmbedding embedding = stub.embedding();
                if (embedding == null) {
                    continue;
                }
                final Vec3i origin = embedding.renderOrigin();
                final float x = stub.pos().getX() - origin.getX();
                final float y = stub.pos().getY() - origin.getY();
                final float z = stub.pos().getZ() - origin.getZ();
                final Direction back = stub.toward().getOpposite();
                add(embedding, stub, ComponentPartials.RIM, x, y, z);
                add(embedding, stub, ComponentPartials.RIM_CONNECTOR, x, y, z);
                add(embedding, stub, ComponentPartials.CONNECTION,
                    x + back.getStepX() * 4 / 16f, y + back.getStepY() * 4 / 16f, z + back.getStepZ() * 4 / 16f);
            }
            litAt = effect.level.getGameTime();
        }

        private void add(final VisualEmbedding embedding, final ShipStub stub, final ComponentPartials part, final float x, final float y, final float z) {
            final TransformedInstance instance = embedding.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(AllPartialModels.PIPE_ATTACHMENTS.get(part).get(stub.toward())))
                .createInstance();
            instance.setIdentityTransform().translate(x, y, z);
            instance.light(LevelRenderer.getLightColor(effect.level, stub.pos()));
            instance.setChanged();
            instances.add(instance);
            owners.add(stub);
        }

        @Override
        public void update(final float partialTick) {
        }

        @Override
        public void delete() {
            for (final TransformedInstance instance : instances) {
                try {
                    instance.delete();
                } catch (final RuntimeException alreadyGone) {
                    // its ship's embedding was deleted with the ship
                }
            }
            instances.clear();
            owners.clear();
        }
    }
}
