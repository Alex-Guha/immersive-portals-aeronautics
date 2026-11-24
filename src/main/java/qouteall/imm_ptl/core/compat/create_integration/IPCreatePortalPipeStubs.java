/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalWorldEffect;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalSectionPieces;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalPieceKind;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalPieceWalk;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalFrame;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalPieceTargets;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalShipFramesClient;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllShapes;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.FluidTransportBehaviour.AttachmentTypes.ComponentPartials;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import java.util.Collection;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.Vec3i;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.mixin_create.IPLevelRendererSectionInvoker;


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
 * {@code IPPortalPieceWalk.SCAN_TICKS} ticks for the rest (portals and chunks coming and going). The far section is re-meshed with the
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
    private record ShipStub(BlockPos pos, Direction toward, IPPortalFrame ship, @Nullable VisualEmbedding embedding) {
    }

    /** A stub's shape, by the way it runs out of the plane: the pipe's arm ({@link IPPortalPieceTargets}). */
    private static final VoxelShape[] PIECES = IPPortalPieceTargets.halves(d -> AllShapes.EIGHT_VOXEL_POLE.get(d.getAxis()));

    /** Per client world, the effect drawing its ship stubs (weak: client worlds come and go). */
    private static final Map<ClientLevel, ShipStubs> SHIP_EFFECTS = new WeakHashMap<>();

    /** The stubs in the world's chunk mesh, by section. */
    private static final IPPortalSectionPieces<Stub> STUBS = IPPortalSectionPieces.create(IPCreatePortalPipeStubs::render);
    /**
     * Pipe faces at a portal block (world or ship plot) whose space a belt or shaft across the portal owns, by section: not drawn, but
     * the pipe's own model changes with them ({@link IPCreatePortalPipeModel}).
     */
    private static final IPPortalSectionPieces<Stub> OUTRANKED_FACES = IPPortalSectionPieces.create(null);
    /** The same as keys ({@link #faceKey}), for the pipe model on Sodium's worker threads. */
    private static volatile Set<Long> outrankedKeys = Set.of();
    private IPCreatePortalPipeStubs() {
    }

    /** The stubs found in a walk. */
    static final class PipeScan implements IPPortalPieceWalk.Scan {
        private final Map<ResourceKey<Level>, Map<Long, List<Stub>>> found = new HashMap<>();
        private final Map<ClientLevel, List<ShipStub>> onShips = new IdentityHashMap<>();
        private final Map<ResourceKey<Level>, Map<Long, List<Stub>>> outranked = new HashMap<>();
        /** Every stub, for the crosshair. */
        private final Map<ResourceKey<Level>, Map<BlockPos, IPPortalPieceTargets.Piece>> targets = new HashMap<>();

        @Override
        public boolean wants(final IPPortalPieceWalk.Side side) {
            return side.farWorld() != null;
        }

        @Override
        public void cell(final IPPortalPieceWalk.Side side, final BlockPos cell, final Vec3 worldCentre) {
            final ClientLevel world = side.world();
            final Direction front = side.front();
            final Direction behind = front.getOpposite();
            if (!opensTowards(world, cell.relative(front), behind)) {
                return;
            }
            final IPPortalPieceWalk.FarCell far = side.far(worldCentre);
            if (far == null) {
                return;
            }
            final ClientLevel farWorld = far.world();
            final BlockPos farCell = far.cell();
            final Direction farOut = far.out();
            if (opensTowards(farWorld, far.end(), farOut.getOpposite())) {
                return; // the far pipe draws the rest itself
            }
            if (IPPortalPieceKind.outranked(farWorld, far.end(), farOut.getOpposite(), IPCreatePortalEnds.PIPE)) {
                // a belt or shaft there owns the space between: no stub, and the pipe here draws no filler (in the
                // world or on a ship: a ship's plot sections are re-meshed through the world's renderer too)
                final BlockPos end = cell.relative(front).immutable();
                IPPortalSectionPieces.add(outranked, world.dimension(), end, new Stub(end, behind));
                return;
            }
            if (far.frame() == null) {
                IPPortalSectionPieces.add(found, farWorld.dimension(), farCell, new Stub(farCell, farOut));
            } else {
                onShips.computeIfAbsent(farWorld, w -> new ArrayList<>()).add(new ShipStub(farCell, farOut, far.frame(), null));
            }
            targets.computeIfAbsent(farWorld.dimension(), d -> new HashMap<>())
                .put(farCell, new IPPortalPieceTargets.Piece(PIECES[farOut.ordinal()], AllBlocks.FLUID_PIPE.asItem()));
        }

        @Override
        public void finish(final Collection<ClientLevel> worlds, final boolean important) {
            STUBS.publish(found, important);
            IPPortalPieceTargets.set(IPCreatePortalEnds.PIPE, targets);
            final Set<Long> keys = new HashSet<>();
            outranked.values().forEach(sections -> sections.values().forEach(list -> list.forEach(face -> keys.add(faceKey(face.pos(), face.toward())))));
            outrankedKeys = keys;
            OUTRANKED_FACES.publish(outranked, important);

            for (final ClientLevel world : worlds) {
                final List<ShipStub> here = onShips.getOrDefault(world, List.of());
                final ShipStubs effect = IPPortalWorldEffect.of(SHIP_EFFECTS, world, !here.isEmpty(), ShipStubs::new);
                if (effect == null) {
                    continue;
                }
                final VisualizationManagerImpl manager = effect.attach();
                final List<ShipStub> drawn = new ArrayList<>(here.size());
                for (final ShipStub stub : here) {
                    final VisualEmbedding embedding = manager == null ? null : IPPortalShipFramesClient.embeddingOf(manager, stub.ship());
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
        IPPortalSectionPieces.putModel(context, poses, model, state, ModelData.EMPTY, RANDOM, light, null);
        poses.popPose();
    }

    // ---------------------------------------------------------------------------------------------------------------
    // Far halves on ships

    /** The ship stubs of one client world, as one Flywheel effect in that world's visualization. */
    private static final class ShipStubs extends IPPortalWorldEffect {
        volatile List<ShipStub> stubs = List.of();

        ShipStubs(final ClientLevel level) {
            super(level);
        }

        @Override
        public EffectVisual<?> visualize(final VisualizationContext context, final float partialTick) {
            return new ShipStubsVisual(this);
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
            final long time = effect.world().getGameTime();
            if (Math.abs(time - litAt) >= LIGHT_TICKS) {
                litAt = time;
                for (int i = 0; i < instances.size(); i++) {
                    instances.get(i).light(LevelRenderer.getLightColor(effect.world(), owners.get(i).pos())).setChanged();
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
            litAt = effect.world().getGameTime();
        }

        private void add(final VisualEmbedding embedding, final ShipStub stub, final ComponentPartials part, final float x, final float y, final float z) {
            final TransformedInstance instance = embedding.instancerProvider()
                .instancer(InstanceTypes.TRANSFORMED, Models.partial(AllPartialModels.PIPE_ATTACHMENTS.get(part).get(stub.toward())))
                .createInstance();
            instance.setIdentityTransform().translate(x, y, z);
            instance.light(LevelRenderer.getLightColor(effect.world(), stub.pos()));
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
