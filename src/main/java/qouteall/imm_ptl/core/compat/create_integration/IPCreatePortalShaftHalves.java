/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalWorldEffect;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalPieceKind;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalPieceWalk;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalFrame;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalPieceTargets;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalShipFramesClient;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.AllPartialModels;
import com.simibubi.create.AllShapes;
import com.simibubi.create.content.kinetics.base.IRotate;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityRenderer;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.base.RotatingInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import dev.engine_room.flywheel.api.instance.Instancer;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visual.EffectVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.api.visualization.VisualizationManager;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.visual.SimpleDynamicVisual;
import java.lang.ref.WeakReference;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.render.SuperBufferFactory;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.compat.iris_integration.IPIrisCompat;
import qouteall.imm_ptl.core.compat.mixin_create.IPRotationPropagatorInvoker;

import static qouteall.imm_ptl.core.compat.portal_pieces.IPPortalFaces.SABLE;

/**
 * The shaft inside a portal block. A shaft going into a nether-style portal ends at the portal block's face, half a
 * block before the plane, and the far shaft starts half a block after the far plane: without more, the shaft looks cut
 * a block short. Each portal block next to a shaft end gets Create's half shaft from the plane to that end, turning
 * with it; with no shaft at the far end the far portal block gets the other half, from the far plane outwards, so the
 * shaft visibly goes through. A belt's end across the portal owns the space instead: no halves on either side
 * ({@link IPCreatePortalEnds}). Both are Flywheel instances of one effect per client world (they turn, so they cannot be
 * chunk geometry like the pipe stubs in {@link IPCreatePortalPipeStubs}). With Flywheel's backend off (always under a
 * shader pack) Create draws its shafts as block entities, and so do the halves ({@link #onRenderStage}).
 * <p>
 * Found on the client thread every 10 ticks across all client worlds, and before the next frame after a block entity
 * or portal block changes. Frameless portals get none (their plane may lie on the shaft's own face).
 * <p>
 * Ships: a portal mounted on a ship has its portal blocks in the ship's plot; half shafts there are instances of the
 * embedding Sable draws that ship with ({@link IPPortalShipFramesClient}), so they move with it. The fixed far side of
 * such a portal gets its half shafts in the world, and the half past the plane goes to the other side's frame.
 */
public final class IPCreatePortalShaftHalves {
    /** Game ticks between relights of the instances. */
    private static final int LIGHT_TICKS = 10;

    /**
     * A half shaft in a portal block: Create's {@code shaft_half} turned by {@code base} (it points south as modelled),
     * spinning about {@code axis} at the speed of {@code source}'s shaft on {@code sourceSide}, with Create's angle
     * offset for that shaft.
     */
    record Half(
        BlockPos cell, Quaternionf base, Vector3f axis, float offset, KineticBlockEntity source, Direction sourceSide,
        @Nullable IPPortalFrame ship, @Nullable VisualEmbedding embedding
    ) {
        Half withEmbedding(final VisualEmbedding embedding) {
            return new Half(cell, base, axis, offset, source, sourceSide, ship, embedding);
        }
    }

    /**
     * Create's half shaft without its face at the block centre: that face lies in the portal plane and z-fought with
     * the portal seen from its other face (as the portal tracks' rail ends did, docs/lessons/portal-rendering.md).
     * Rebuilt on a renderer reload, as Flywheel's own partial models are.
     */
    private static final RendererReloadCache<PartialModel, Model> OPEN_HALF =
        new RendererReloadCache<>(partial -> new BakedModelBuilder(new WithoutCentreFace(partial.get())).build());
    /** The same for Create's block entity renderers (Flywheel's backend off), as Create buffers its partials. */
    private static final RendererReloadCache<PartialModel, SuperByteBuffer> OPEN_HALF_BUFFER = new RendererReloadCache<>(
        partial -> SuperBufferFactory.getInstance().createForBlock(new WithoutCentreFace(partial.get()), KineticBlockEntityRenderer.shaft(Direction.Axis.Z))
    );

    /** Per client world, its effect (weak: client worlds come and go). */
    private static final Map<ClientLevel, Halves> EFFECTS = new WeakHashMap<>();
    /** A lone half's shape, by the direction it runs out of the plane ({@link IPPortalPieceTargets}). */
    private static final VoxelShape[] PIECES = IPPortalPieceTargets.halves(d -> AllShapes.SIX_VOXEL_POLE.get(d.getAxis()));

    private IPCreatePortalShaftHalves() {
    }

    /** The half shafts found in a walk. */
    static final class ShaftScan implements IPPortalPieceWalk.Scan {
        private final Map<ClientLevel, List<Half>> found = new IdentityHashMap<>();
        private final Map<IPCreatePortalShaftPhase.Key, IPCreatePortalShaftPhase.Shift> shifts = IPCreatePortalShaftPhase.newFound();
        /** The lone halves, for the crosshair. */
        private final Map<ResourceKey<Level>, Map<BlockPos, IPPortalPieceTargets.Piece>> targets = new HashMap<>();

        @Override
        public boolean wants(final IPPortalPieceWalk.Side side) {
            return true;
        }

        @Override
        public void cell(final IPPortalPieceWalk.Side side, final BlockPos cell, final Vec3 worldCentre) {
            final ClientLevel world = side.world();
            final IPPortalFrame frame = side.frame();
            final Direction front = side.front();
            final Direction behind = front.getOpposite();
            final Direction.Axis axis = front.getAxis();
            final Vector3f axisVector = Direction.get(Direction.AxisDirection.POSITIVE, axis).step();
            final BlockPos endPos = cell.relative(front);
            final KineticBlockEntity source = shaftEnd(world, endPos, behind);
            if (source == null) {
                return;
            }
            final IPPortalPieceWalk.FarCell far = side.far(worldCentre);
            if (far != null && IPPortalPieceKind.at(far.world(), far.end(), far.out().getOpposite()).outranks(IPCreatePortalEnds.SHAFT)) {
                return; // a belt across the portal owns the space between ({@link IPCreatePortalEnds})
            }
            final float offset = KineticBlockEntityVisual.rotationOffset(source.getBlockState(), axis, endPos)
                + source.getRotationAngleOffset(axis);
            add(found, world, new Half(cell.immutable(), pointing(front), axisVector, offset, source, behind, frame, null));
            if (far != null) {
                stub(far, behind, axisVector, offset, source, found, shifts, targets);
            }
        }

        @Override
        public void finish(final Collection<ClientLevel> worlds, final boolean important) {
            IPCreatePortalShaftPhase.update(shifts);
            IPPortalPieceTargets.set(IPCreatePortalEnds.SHAFT, targets);
            for (final ClientLevel world : worlds) {
                final List<Half> halves = found.getOrDefault(world, List.of());
                final Halves effect = IPPortalWorldEffect.of(EFFECTS, world, !halves.isEmpty(), Halves::new);
                if (effect == null) {
                    continue;
                }
                if (!effect.all.equals(halves)) {
                    effect.all = List.copyOf(halves);
                }
                final VisualizationManagerImpl manager = effect.attach();
                final List<Half> drawn = new ArrayList<>(halves.size());
                for (final Half half : halves) {
                    if (half.ship() == null) {
                        drawn.add(half);
                        continue;
                    }
                    // drawn with the ship's own embedding, once Sable has made one
                    final VisualEmbedding embedding = manager == null || !SABLE ? null : IPPortalShipFramesClient.embeddingOf(manager, half.ship());
                    if (embedding != null) {
                        drawn.add(half.withEmbedding(embedding));
                    }
                }
                if (!effect.halves.equals(drawn)) {
                    effect.halves = List.copyOf(drawn);
                }
            }
        }
    }

    /**
     * Draws the half shafts of the world being drawn while Flywheel cannot (Create's own test in
     * {@code KineticBlockEntityRenderer.renderSafe}): as Create then draws its shafts, at Create's angle
     * ({@code getAngleForBe}), and with a shader pack as Iris's block entities, so the pack draws them with its block
     * entity program and IP's clip plane. After the block entities, within IP's entity clip in portal passes; flushed
     * here without Iris (Iris flushes its batch later, clipped by {@code IPIrisBatchClip}). No shadows with a pack:
     * Iris's shadow pass runs no render stage.
     */
    public static void onRenderStage(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        final Halves effect = level == null ? null : EFFECTS.get(level);
        if (effect == null || effect.all.isEmpty() || VisualizationManager.supportsVisualization(level)) {
            return;
        }
        final SuperByteBuffer model = OPEN_HALF_BUFFER.get(AllPartialModels.SHAFT_HALF);
        final Vec3 camera = event.getCamera().getPosition();
        final MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        final MultiBufferSource source = IPIrisCompat.beginBlockEntity(buffers, KineticBlockEntityRenderer.shaft(Direction.Axis.Z));
        try {
            final VertexConsumer consumer = source.getBuffer(RenderType.solid());
            final PoseStack pose = new PoseStack();
            for (final Half half : effect.all) {
                // drawing space is the world's minus the camera, as for block entities
                final Matrix4d place = new Matrix4d().translation(-camera.x, -camera.y, -camera.z);
                if (half.ship() != null) {
                    final Matrix4d ship = SABLE ? IPPortalShipFramesClient.renderPose(half.ship()) : null;
                    if (ship == null) {
                        continue;
                    }
                    place.mul(ship);
                }
                place.translate(half.cell().getX(), half.cell().getY(), half.cell().getZ());
                final Matrix4f drawing = new Matrix4f(place);
                pose.last().pose().set(drawing);
                pose.last().normal().set(new Matrix3f(drawing).normal());
                final KineticBlockEntity be = half.source();
                final Level beLevel = be.getLevel();
                final float speed = be.isRemoved() || beLevel == null ? 0 : faceSpeed(be, half.sourceSide());
                final float degrees = speed == 0 ? half.offset()
                    : (AnimationTickHolder.getRenderTime(beLevel) * speed * 3f / 10 + half.offset()) % 360;
                model.center()
                    .rotate(new Quaternionf().rotationAxis((float) Math.toRadians(degrees), half.axis()))
                    .rotate(half.base())
                    .uncenter();
                model.light(LevelRenderer.getLightColor(level, half.cell()));
                model.renderInto(pose, consumer);
            }
        } finally {
            IPIrisCompat.endBlockEntity();
        }
        buffers.endBatch(RenderType.solid());
    }

    /** The half past the near plane, as the portal shows it, in the far portal block unless a shaft there draws its own. */
    private static void stub(
        final IPPortalPieceWalk.FarCell far, final Direction behind, final Vector3f axisVector, final float offset,
        final KineticBlockEntity source, final Map<ClientLevel, List<Half>> found,
        final Map<IPCreatePortalShaftPhase.Key, IPCreatePortalShaftPhase.Shift> shifts,
        final Map<ResourceKey<Level>, Map<BlockPos, IPPortalPieceTargets.Piece>> targets
    ) {
        final ClientLevel farWorld = far.world();
        final BlockPos farCell = far.cell();
        final Direction farOut = far.out();
        final Quaternionf toLocal = far.turn();
        final KineticBlockEntity farShaft = shaftEnd(farWorld, farCell.relative(farOut), farOut.getOpposite());
        if (farShaft != null) {
            // the far shaft draws its own half; the side driven through the portal is turned to line up
            phase(source, behind, axisVector, offset, toLocal, farShaft, farOut, shifts);
            return;
        }
        if (IPPortalPieceKind.outranked(farWorld, far.end(), farOut.getOpposite(), IPCreatePortalEnds.SHAFT)) {
            return; // a belt there owns the space between
        }
        final Quaternionf base = new Quaternionf(toLocal).mul(pointing(behind));
        final Vector3f farAxis = toLocal.transform(new Vector3f(axisVector));
        farAxis.set(Math.round(farAxis.x), Math.round(farAxis.y), Math.round(farAxis.z));
        add(found, farWorld, new Half(farCell, base, farAxis, offset, source, behind, far.frame(), null));
        targets.computeIfAbsent(farWorld.dimension(), d -> new HashMap<>())
            .put(farCell, new IPPortalPieceTargets.Piece(PIECES[farOut.ordinal()], AllBlocks.SHAFT.asItem()));
    }

    /**
     * The shift that lines up the two sides' half shafts in the far portal block: the half past the near plane
     * ({@link #stub}'s, at the near shaft's angle {@code offset}) and the far shaft's own half there. Create's kinetic
     * rotation is {@code quaternion(axis, offset + time * speed)} applied after the model's base rotation, and the far
     * speed is the near one mapped ({@code sigma}), so the time terms cancel and the two meet when
     * {@code farAngle = psi + sigma * nearAngle}, {@code psi} being the turn about the axis between the two halves' base
     * rotations. Whichever side is driven through the portal gets the shift.
     */
    private static void phase(
        final KineticBlockEntity source, final Direction behind, final Vector3f axisVector, final float offset, final Quaternionf toLocal,
        final KineticBlockEntity farShaft, final Direction farOut, final Map<IPCreatePortalShaftPhase.Key, IPCreatePortalShaftPhase.Shift> shifts
    ) {
        final Vector3f farAxis = Direction.get(Direction.AxisDirection.POSITIVE, farOut.getAxis()).step();
        final Vector3f mapped = toLocal.transform(new Vector3f(axisVector));
        final int sigma = mapped.dot(farAxis) >= 0 ? 1 : -1;
        final Quaternionf turn = new Quaternionf(toLocal).mul(pointing(behind)).mul(new Quaternionf(pointing(farOut)).conjugate());
        final float psi = (float) Math.toDegrees(2 * Math.atan2(turn.x * farAxis.x + turn.y * farAxis.y + turn.z * farAxis.z, turn.w));
        final Direction.Axis farAx = farOut.getAxis();
        final float farRaw = KineticBlockEntityVisual.rotationOffset(farShaft.getBlockState(), farAx, farShaft.getBlockPos())
            + IPCreatePortalShaftPhase.rawAngleOffset(farShaft, farAx);
        if (drivenThroughPortal(farShaft, farOut.getOpposite())) {
            put(shifts, farShaft, psi + sigma * offset - farRaw);
        } else if (drivenThroughPortal(source, behind)) {
            final Direction.Axis nearAx = behind.getAxis();
            final float nearRaw = KineticBlockEntityVisual.rotationOffset(source.getBlockState(), nearAx, source.getBlockPos())
                + IPCreatePortalShaftPhase.rawAngleOffset(source, nearAx);
            final float farAngle = farRaw + IPCreatePortalShaftPhase.extraOffset(farShaft);
            put(shifts, source, sigma * (farAngle - psi) - nearRaw);
        }
    }

    private static void put(final Map<IPCreatePortalShaftPhase.Key, IPCreatePortalShaftPhase.Shift> shifts, final KineticBlockEntity root, final float degrees) {
        final IPCreatePortalShaftPhase.Key key = IPCreatePortalShaftPhase.keyOf(root);
        if (key != null) {
            final int whole = Math.floorMod(Math.round(degrees), 360);
            shifts.put(key, new IPCreatePortalShaftPhase.Shift(whole > 180 ? whole - 360 : whole, new java.lang.ref.WeakReference<>(root)));
        }
    }

    /** Whether {@code be}'s Create source is the portal block on {@code side}: it is turned through the portal. */
    private static boolean drivenThroughPortal(final KineticBlockEntity be, final Direction side) {
        return be.source != null && be.source.equals(be.getBlockPos().relative(side));
    }

    private static void add(final Map<ClientLevel, List<Half>> found, final ClientLevel world, final Half half) {
        found.computeIfAbsent(world, w -> new ArrayList<>()).add(half);
    }

    /** {@code RotatingInstance.rotateToFace(SOUTH, side)}: Create's half shaft pointing to {@code side}. */
    private static Quaternionf pointing(final Direction side) {
        return new Quaternionf().rotateTo(0, 0, 1, side.getStepX(), side.getStepY(), side.getStepZ());
    }

    /** Create's speed of the shaft on that face, as drawn (0 while overstressed). */
    private static float faceSpeed(final KineticBlockEntity be, final Direction side) {
        final float speed = be.getSpeed();
        return speed == 0 ? 0 : speed * IPRotationPropagatorInvoker.ip$getAxisModifier(be, side);
    }

    /** The kinetic block entity at {@code pos} with a shaft on {@code side}, or null. */
    private static @Nullable KineticBlockEntity shaftEnd(final ClientLevel world, final BlockPos pos, final Direction side) {
        final BlockEntity be = world.getBlockEntity(pos);
        if (!(be instanceof KineticBlockEntity kinetic)) {
            return null;
        }
        final BlockState state = kinetic.getBlockState();
        return state.getBlock() instanceof IRotate rotate && rotate.hasShaftTowards(world, pos, state, side) ? kinetic : null;
    }

    /** A block model without the quads lying wholly in the plane z = 0.5 (model space, before any rotation). */
    private static final class WithoutCentreFace extends BakedModelWrapper<BakedModel> {
        WithoutCentreFace(final BakedModel model) {
            super(model);
        }

        @Override
        public List<BakedQuad> getQuads(final @Nullable BlockState state, final @Nullable Direction side, final RandomSource random) {
            return withoutCentreFace(super.getQuads(state, side, random));
        }

        @Override
        public List<BakedQuad> getQuads(
            final @Nullable BlockState state, final @Nullable Direction side, final RandomSource random, final ModelData data,
            final @Nullable RenderType renderType
        ) {
            return withoutCentreFace(super.getQuads(state, side, random, data, renderType));
        }

        private static List<BakedQuad> withoutCentreFace(final List<BakedQuad> quads) {
            final List<BakedQuad> kept = new ArrayList<>(quads.size());
            for (final BakedQuad quad : quads) {
                if (!inCentrePlane(quad)) {
                    kept.add(quad);
                }
            }
            return kept;
        }

        private static boolean inCentrePlane(final BakedQuad quad) {
            final int[] vertices = quad.getVertices();
            final int stride = vertices.length / 4;
            for (int i = 0; i < 4; i++) {
                if (Math.abs(Float.intBitsToFloat(vertices[i * stride + 2]) - 0.5f) > 1.0e-4f) {
                    return false;
                }
            }
            return true;
        }
    }

    /** The half shafts of one client world, as one Flywheel effect in that world's visualization. */
    private static final class Halves extends IPPortalWorldEffect {
        /** What Flywheel draws: the world's halves and the ship halves whose embedding exists. */
        volatile List<Half> halves = List.of();
        /** Every half found in the world, for {@link #onRenderStage}. */
        List<Half> all = List.of();

        Halves(final ClientLevel level) {
            super(level);
        }

        @Override
        public EffectVisual<?> visualize(final VisualizationContext context, final float partialTick) {
            return new HalvesVisual(context, this);
        }
    }

    private static final class HalvesVisual implements EffectVisual<Halves>, SimpleDynamicVisual {
        private final VisualizationContext context;
        private final Halves effect;
        private List<Half> shown = List.of();
        private final List<RotatingInstance> instances = new ArrayList<>();
        private float[] speeds = new float[0];
        private long litAt;

        HalvesVisual(final VisualizationContext context, final Halves effect) {
            this.context = context;
            this.effect = effect;
        }

        @Override
        public void beginFrame(final DynamicVisual.Context frame) {
            final List<Half> now = effect.halves;
            if (now != shown) {
                rebuild(now);
            }
            final long time = effect.world().getGameTime();
            final boolean relight = Math.abs(time - litAt) >= LIGHT_TICKS;
            if (relight) {
                litAt = time;
            }
            for (int i = 0; i < instances.size(); i++) {
                final Half half = shown.get(i);
                final RotatingInstance instance = instances.get(i);
                final float speed = half.source().isRemoved() ? 0 : faceSpeed(half.source(), half.sourceSide()) * RotatingInstance.SPEED_MULTIPLIER;
                boolean dirty = false;
                if (speed != speeds[i]) {
                    speeds[i] = speed;
                    instance.setRotationalSpeed(speed);
                    dirty = true;
                }
                if (relight) {
                    instance.light(LevelRenderer.getLightColor(effect.world(), half.cell()));
                    dirty = true;
                }
                if (dirty) {
                    instance.setChanged();
                }
            }
        }

            private void rebuild(final List<Half> now) {
            deleteInstances();
            shown = now;
            speeds = new float[now.size()];
            Arrays.fill(speeds, Float.NaN);
            if (now.isEmpty()) {
                return;
            }
            final Model model = OPEN_HALF.get(AllPartialModels.SHAFT_HALF);
            final Map<VisualizationContext, Instancer<RotatingInstance>> instancers = new IdentityHashMap<>();
            for (final Half half : now) {
                // a ship's half shafts are drawn with the ship's embedding, in its plot's coordinates
                final VisualizationContext target = half.embedding() != null ? half.embedding() : context;
                final Instancer<RotatingInstance> instancer =
                    instancers.computeIfAbsent(target, t -> t.instancerProvider().instancer(AllInstanceTypes.ROTATING, model));
                final Vec3i origin = target.renderOrigin();
                final RotatingInstance instance = instancer.createInstance();
                instance.rotation.set(half.base());
                instance.setRotationAxis(half.axis())
                    .setRotationOffset(half.offset())
                    .setPosition(half.cell().getX() - origin.getX(), half.cell().getY() - origin.getY(), half.cell().getZ() - origin.getZ());
                instance.light(LevelRenderer.getLightColor(effect.world(), half.cell()));
                instance.setChanged();
                instances.add(instance);
            }
            litAt = effect.world().getGameTime();
        }

        private void deleteInstances() {
            for (final RotatingInstance instance : instances) {
                try {
                    instance.delete();
                } catch (final RuntimeException alreadyGone) {
                    // its ship's embedding was deleted with the ship
                }
            }
            instances.clear();
        }

        @Override
        public void update(final float partialTick) {
        }

        @Override
        public void delete() {
            deleteInstances();
        }
    }
}
