/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllBlocks;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltRenderer;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.lib.model.baked.BakedModelBuilder;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import dev.engine_room.flywheel.lib.util.RendererReloadCache;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.createmod.catnip.animation.AnimationTickHolder;
import net.createmod.catnip.math.AngleHelper;
import net.createmod.catnip.render.SpriteShiftEntry;
import net.createmod.catnip.render.SuperBufferFactory;
import net.createmod.catnip.render.SuperByteBuffer;
import com.mojang.blaze3d.vertex.VertexConsumer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
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
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

import static qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalEnds.SABLE;

/**
 * The look of a belt at a portal ({@link IPCreatePortalBelts}). A belt's end cell is drawn with the loop around its
 * pulley, and a nether-style portal leaves half a block of portal block between it and the plane, so two belts meeting
 * through a portal looked like two belts with a block of air between them. A belt end facing a portal block runs
 * straight on instead: its end cell takes Create's straight part (as a pulley in the middle of a belt: the pulley
 * still turns in it), and the portal block gets the belt up to the plane, half of Create's straight part, scrolling
 * with the belt. The belt on the far side does the same from its plane. Belts own the space between the two sides
 * ({@link IPCreatePortalEnds}), so nothing else draws there. With no belt end across (nothing, a depot, a belt's side)
 * the belt runs on through the portal and ends in the far portal block: the far half of Create's end part, its loop,
 * drawn in the far world. Two belt ends across from each other that do not run as one belt (the other way, or at
 * another speed) each end in their own half: the cell's own end loop, moved half a block on ({@link Mode}).
 * <p>
 * Flywheel (Create's {@code BeltVisual}, {@code MixinBeltVisual_PortalBelts}) and Create's block entity renderer when
 * Flywheel's backend is off ({@code MixinBeltRenderer_PortalBelts}). Horizontal belts only. Belts on ships at their own
 * mounted portal too (2.6.12.8): the portal blocks on the ship are found in its plot, and a loop on the ship's side is
 * drawn through the ship's pose as drawn.
 */
public final class IPCreatePortalBeltLook {
    /** Create's part cut to the half on the side {@code away} points away from (model space). */
    private record Half(PartialModel partial, Direction away) {
    }

    private static final RendererReloadCache<Half, Model> HALF_MODEL =
        new RendererReloadCache<>(half -> new BakedModelBuilder(new HalfModel(half.partial().get(), half.away())).build());
    private static final RendererReloadCache<Half, SuperByteBuffer> HALF_BUFFER = new RendererReloadCache<>(
        half -> SuperBufferFactory.getInstance().createForBlock(new HalfModel(half.partial().get(), half.away()),
            AllBlocks.BELT.getDefaultState()));

    private IPCreatePortalBeltLook() {
    }

    /** The side a horizontal belt's end cell faces a portal block on, or null. */
    public static @Nullable Direction portalSide(final BlockGetter level, final BlockPos pos, final BlockState state) {
        if (!AllBlocks.BELT.has(state) || state.getValue(BeltBlock.SLOPE) != BeltSlope.HORIZONTAL) {
            return null;
        }
        final BeltPart part = state.getValue(BeltBlock.PART);
        if (part != BeltPart.START && part != BeltPart.END) {
            return null;
        }
        final Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        final Direction out = part == BeltPart.END ? facing : facing.getOpposite();
        return IPCreatePortalPipes.isPlaceholderFacing(level.getBlockState(pos.relative(out)), out) ? out : null;
    }

    /** Create's straight belt part, top or bottom. */
    public static PartialModel straight(final boolean bottom) {
        return BeltRenderer.getBeltPartial(false, false, false, bottom);
    }

    /**
     * The half of the straight part to put in the portal block next to the end, in model space: the half towards the
     * belt, for a model turned by {@code yRotDegrees} about the block's centre and placed in the portal block.
     */
    private static Half half(final PartialModel partial, final float yRotDegrees, final Direction out) {
        final Vector3f inModel = new Quaternionf().rotationY((float) Math.toRadians(yRotDegrees)).conjugate()
            .transform(new Vector3f(out.getStepX(), out.getStepY(), out.getStepZ()));
        return new Half(partial, Direction.getNearest(inModel.x, inModel.y, inModel.z));
    }

    /** The Flywheel model of the half in the portal block for an instance turned by {@code yRotDegrees}. */
    public static Model halfModel(final PartialModel partial, final float yRotDegrees, final Direction out) {
        return HALF_MODEL.get(half(partial, yRotDegrees, out));
    }

    /** What a belt end at a portal block meets across the portal ({@link #search}). */
    public enum Mode {
        /** A belt end across running as one belt with this one (same speed, items flowing through): straight halves. */
        JOINED,
        /** No belt end across: straight to the plane here, the end loop in the far portal block ({@link FarLoop}). */
        ALONE,
        /**
         * A belt end across that is another belt: running the other way (both into the portal, or both out of it) or
         * at another speed (one stopped included): each ends in its own half.
         */
        APART
    }

    /**
     * The far half of an end loop: the near belt's end, drawn in the far world's portal block {@code cell} (in
     * {@code frame}'s coordinates: a ship's plot, or null for the world).
     */
    private record FarLoop(BlockPos cell, Direction out, BeltBlockEntity near, Direction nearOut, IPCreatePortalShafts.@Nullable Frame frame) {
    }

    /**
     * How far an end loop in the portal block stops short of the plane: its tip is a face across the belt, and lying in
     * the plane it showed through the portal's other side (as anything ending exactly in a portal plane does,
     * docs/lessons/portal-rendering.md), as IP keeps terrain 1 cm short of the plane.
     */
    public static final float LOOP_SHORT = 0.01f;
    /** Belt ends at portal blocks, by world and position, with what they meet. Read by Flywheel's workers too. */
    private static volatile Map<ResourceKey<Level>, Map<BlockPos, Mode>> modes = Map.of();
    /** End loops to draw, by the world they are drawn in. */
    private static volatile Map<ResourceKey<Level>, List<FarLoop>> loops = Map.of();

    /** What the belt end at {@code pos} meets across the portal (JOINED until it was looked at). */
    public static Mode mode(final Level level, final BlockPos pos) {
        final Map<BlockPos, Mode> here = modes.get(level.dimension());
        final Mode mode = here == null ? null : here.get(pos);
        return mode == null ? Mode.JOINED : mode;
    }

    /** Client block change: a portal block coming or going changes what the belts next to it meet at once. */
    static void onBlockChanged(final Level level, final BlockPos pos, final BlockState oldState, final BlockState newState) {
        final boolean portal = oldState.getBlock() == PortalPlaceholderBlock.instance || newState.getBlock() == PortalPlaceholderBlock.instance;
        if (portal && oldState.getBlock() != newState.getBlock()) {
            for (final Direction side : Direction.Plane.HORIZONTAL) {
                if (level.getBlockEntity(pos.relative(side)) instanceof BeltBlockEntity belt) {
                    VisualizationHelper.queueUpdate(belt);
                }
            }
        }
    }

    /**
     * Every belt end at a portal block in every client world, and what it meets past the portal. Ends whose answer
     * changed get their visual updated. Every 10 ticks (belts reverse without a block change) and after block changes.
     */
    static final class BeltScan implements IPCreatePortalEndScan.Scan {
        private final Map<ResourceKey<Level>, Map<BlockPos, Mode>> found = new HashMap<>();
        private final Map<ResourceKey<Level>, List<FarLoop>> farLoops = new HashMap<>();

        @Override
        public boolean wants(final IPCreatePortalEndScan.Side side) {
            return !side.front().getAxis().isVertical();
        }

        @Override
        public void cell(final IPCreatePortalEndScan.Side side, final BlockPos cell, final Vec3 worldCentre) {
            final ClientLevel world = side.world();
            final Portal portal = side.portal();
            final IPCreatePortalShafts.Frame frame = side.frame();
            final ClientLevel farWorld = side.farWorld();
            final UUID farShip = side.farShip();
            final Direction front = side.front();
            final Direction out = front.getOpposite();
            final BlockPos end = cell.relative(front);
            if (!(world.getBlockEntity(end) instanceof BeltBlockEntity near) || portalSide(world, end, near.getBlockState()) != out) {
                return;
            }
            Mode mode = Mode.ALONE;
            if (farWorld != null) {
                final Vec3 farCentre = portal.transformPoint(worldCentre);
                final Vec3 outWorld = frame == null ? Vec3.atLowerCornerOf(out.getNormal()) : frame.toWorldDirection(Vec3.atLowerCornerOf(out.getNormal()));
                final Vec3 farOutWorld = portal.transformLocalVecNonScale(outWorld);
                // the far portal block: in the world, or (the other side of a ship-mounted portal on a ship) on that ship
                IPCreatePortalShafts.Frame farFrame = null;
                if (farShip != null) {
                    if (!SABLE) {
                        return;
                    }
                    for (final IPCreatePortalShafts.Frame candidate : IPCreateShaftShips.shipsTouching(farWorld, AABB.ofSize(farCentre, 2, 2, 2))) {
                        if (candidate.ship().equals(farShip)) {
                            farFrame = candidate;
                        }
                    }
                    if (farFrame == null) {
                        return;
                    }
                }
                final Vec3 farLocal = farFrame == null ? farCentre : farFrame.toLocal(farCentre);
                final Vec3 farDir = farFrame == null ? farOutWorld : farFrame.toLocalDirection(farOutWorld);
                final BlockPos farCell = BlockPos.containing(farLocal);
                final Direction farOut = Direction.getNearest(farDir.x, farDir.y, farDir.z);
                if (farWorld.isLoaded(farCell) && IPCreatePortalPipes.isPlaceholderFacing(farWorld.getBlockState(farCell), farOut)
                    && (farFrame == null || farFrame.holds(farWorld, farCell))) {
                    final BlockPos farEnd = farCell.relative(farOut);
                    if (farWorld.getBlockEntity(farEnd) instanceof BeltBlockEntity far
                        && portalSide(farWorld, farEnd, far.getBlockState()) == farOut.getOpposite()) {
                        mode = apart(near, out, far, farOut.getOpposite()) ? Mode.APART : Mode.JOINED;
                    } else if (!farOut.getAxis().isVertical()) {
                        farLoops.computeIfAbsent(farWorld.dimension(), d -> new ArrayList<>())
                            .add(new FarLoop(farCell.immutable(), farOut, near, out, farFrame));
                    }
                }
            }
            found.computeIfAbsent(world.dimension(), d -> new HashMap<>()).put(end.immutable(), mode);
        }

        @Override
        public void finish(final Collection<ClientLevel> worlds, final boolean important) {
            final Map<ResourceKey<Level>, Map<BlockPos, Mode>> previous = modes;
            modes = found;
            loops = farLoops;
            for (final ClientLevel world : worlds) {
                final Map<BlockPos, Mode> before = previous.getOrDefault(world.dimension(), Map.of());
                final Map<BlockPos, Mode> now = found.getOrDefault(world.dimension(), Map.of());
                final Set<BlockPos> ends = new HashSet<>(before.keySet());
                ends.addAll(now.keySet());
                for (final BlockPos end : ends) {
                    if (before.get(end) != now.get(end) && world.getBlockEntity(end) instanceof BeltBlockEntity belt) {
                        VisualizationHelper.queueUpdate(belt);
                    }
                }
            }
        }
    }

    /**
     * Two belt ends facing each other through a portal do not run as one belt: at different speeds (one stopped
     * included; a belt driven through the portal by the other runs at its speed, {@link IPCreatePortalShafts}), or both
     * into the portal, or both away from it.
     */
    private static boolean apart(final BeltBlockEntity a, final Direction aOut, final BeltBlockEntity b, final Direction bOut) {
        final float sa = Math.abs(a.getSpeed());
        final float sb = Math.abs(b.getSpeed());
        if (Math.abs(sa - sb) > 1.0e-3f * Math.max(1, Math.max(sa, sb))) {
            return true;
        }
        return sa != 0 && (a.getMovementFacing() == aOut) == (b.getMovementFacing() == bOut);
    }

    /**
     * Create's block entity renderer, at its choice of a belt part ({@code bottom}: the lower strap): for an end cell at
     * a portal, draws its piece in the portal block (as Create scrolls its own parts) and returns the straight part.
     */
    public static @Nullable PartialModel drawVanilla(
        final BeltBlockEntity be, final boolean bottom, final PoseStack ms, final MultiBufferSource buffer, final int light
    ) {
        final Level level = be.getLevel();
        final BlockState state = be.getBlockState();
        final Direction out = level == null ? null : portalSide(level, be.getBlockPos(), state);
        if (out == null) {
            return null;
        }
        final Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        final float yRot = AngleHelper.horizontalAngle(facing);
        final boolean headOn = mode(level, be.getBlockPos()) == Mode.APART;
        final BeltPart part = state.getValue(BeltBlock.PART);
        final PartialModel partial = headOn ? BeltRenderer.getBeltPartial(false, part == BeltPart.START, part == BeltPart.END, bottom) : straight(bottom);
        // straight on to the plane, or (apart) the cell's own end loop moved half a block on, so it ends at the plane
        final SuperByteBuffer piece = HALF_BUFFER.get(half(partial, yRot, headOn ? out.getOpposite() : out));
        final double shift = headOn ? 0.5 - LOOP_SHORT : 1;
        drawScrolling(piece, level, facing, be.getSpeed(), be.color.orElse(null), bottom, headOn ? 0.5 : 0,
            new Vec3(out.getStepX() * shift, 0, out.getStepZ() * shift), yRot, ms, buffer.getBuffer(RenderType.solid()), light);
        return straight(bottom);
    }

    /**
     * Draws a piece of belt as Create's block entity renderer draws a horizontal belt facing {@code facing} at
     * {@code speed}: turned by {@code yRot} about the centre of the block at {@code offset} from the pose, scrolling,
     * {@code phase} turns of the texture (it repeats once a block) later than Create's own parts.
     */
    private static void drawScrolling(
        final SuperByteBuffer piece, final Level level, final Direction facing, final float beltSpeed, final @Nullable DyeColor color,
        final boolean bottom, final double phase, final Vec3 offset, final float yRot, final PoseStack ms, final VertexConsumer consumer,
        final int light
    ) {
        // BeltRenderer.renderSafe's scroll, for a horizontal belt
        final SpriteShiftEntry shift = BeltRenderer.getSpriteShiftEntry(color, false, bottom);
        float speed = beltSpeed;
        double scroll = phase;
        if (speed != 0 || color != null) {
            final float time = AnimationTickHolder.getRenderTime(level) * facing.getAxisDirection().getStep();
            if (facing.getAxis() == Direction.Axis.X) {
                speed = -speed;
            }
            scroll += speed * time / 504.0 + (bottom ? 0.5 : 0.0);
        }
        if (scroll != 0) {
            final float spriteSize = shift.getTarget().getV1() - shift.getTarget().getV0();
            scroll -= Math.floor(scroll);
            piece.shiftUVScrolling(shift, (float) (scroll * spriteSize * 0.5));
        }
        final PoseStack place = new PoseStack();
        place.translate(offset.x + 0.5, offset.y + 0.5, offset.z + 0.5);
        place.mulPose(com.mojang.math.Axis.YP.rotationDegrees(yRot));
        place.translate(-0.5, -0.5, -0.5);
        piece.light(light);
        piece.transform(place);
        piece.renderInto(ms, consumer);
    }

    /**
     * Draws the far halves of the end loops that belong in the world being drawn (main and portal passes): Create's end
     * part of a belt facing out of the far portal block, its loop half, scrolling as the near belt moves its items.
     */
    public static void onRenderStage(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        final List<FarLoop> here = level == null ? null : loops.get(level.dimension());
        if (here == null || here.isEmpty()) {
            return;
        }
        final Vec3 camera = event.getCamera().getPosition();
        final MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        final MultiBufferSource source = IPIrisCompat.beginBlockEntity(buffers, AllBlocks.BELT.getDefaultState());
        try {
            final VertexConsumer consumer = source.getBuffer(RenderType.solid());
            final PoseStack pose = new PoseStack();
            for (final FarLoop loop : here) {
                final BeltBlockEntity near = loop.near();
                if (near.isRemoved() || near.getLevel() == null || !level.isLoaded(loop.cell())) {
                    continue;
                }
                final Matrix4d place = new Matrix4d().translation(-camera.x, -camera.y, -camera.z);
                if (loop.frame() != null) {
                    final Matrix4d ship = IPCreateShaftShipsClient.renderPose(loop.frame());
                    if (ship == null) {
                        continue;
                    }
                    place.mul(ship); // a loop on a ship moves with it
                }
                place.translate(loop.cell().getX(), loop.cell().getY(), loop.cell().getZ());
                final Matrix4f drawing = new Matrix4f(place);
                pose.setIdentity();
                pose.last().pose().set(drawing);
                pose.last().normal().set(new Matrix3f(drawing).normal());
                final float speed = loopSpeed(near, loop);
                final float yRot = AngleHelper.horizontalAngle(loop.out());
                final int light = LevelRenderer.getLightColor(level, loop.cell());
                for (final boolean bottom : new boolean[]{true, false}) {
                    final SuperByteBuffer piece = HALF_BUFFER.get(half(BeltRenderer.getBeltPartial(false, false, true, bottom), yRot, loop.out().getOpposite()));
                    drawScrolling(piece, near.getLevel(), loop.out(), speed, near.color.orElse(null), bottom, 0, Vec3.ZERO, yRot, pose,
                        consumer, light);
                }
            }
        } finally {
            IPIrisCompat.endBlockEntity();
        }
        buffers.endBatch(RenderType.solid());
    }

    /**
     * The speed of a belt facing out of the far portal block that moves as the near belt does through the portal (into
     * the portal there: out of it here), as Create's {@code getMovementFacing} reads speeds.
     */
    private static float loopSpeed(final BeltBlockEntity near, final FarLoop loop) {
        final float speed = Math.abs(near.getSpeed());
        if (speed == 0) {
            return 0;
        }
        final Direction wanted = near.getMovementFacing() == loop.nearOut() ? loop.out() : loop.out().getOpposite();
        final Direction.Axis axis = loop.out().getAxis();
        final Direction positive = Direction.fromAxisAndDirection(axis,
            axis == Direction.Axis.X ? Direction.AxisDirection.NEGATIVE : Direction.AxisDirection.POSITIVE);
        return wanted == positive ? speed : -speed;
    }

    /** A block model keeping the half where (v - 0.5) . away <= 0, quads cut at the middle (position and UV). */
    private static final class HalfModel extends BakedModelWrapper<BakedModel> {
        private final Direction away;

        HalfModel(final BakedModel model, final Direction away) {
            super(model);
            this.away = away;
        }

        @Override
        public List<BakedQuad> getQuads(final @Nullable BlockState state, final @Nullable Direction side, final RandomSource random) {
            return cut(super.getQuads(state, side, random));
        }

        @Override
        public List<BakedQuad> getQuads(
            final @Nullable BlockState state, final @Nullable Direction side, final RandomSource random, final ModelData data,
            final @Nullable RenderType renderType
        ) {
            return cut(super.getQuads(state, side, random, data, renderType));
        }

        private List<BakedQuad> cut(final List<BakedQuad> quads) {
            final List<BakedQuad> kept = new ArrayList<>(quads.size());
            for (final BakedQuad quad : quads) {
                cutQuad(quad, kept);
            }
            return kept;
        }

        private float distance(final int[] vertices, final int base) {
            return (Float.intBitsToFloat(vertices[base]) - 0.5f) * away.getStepX()
                + (Float.intBitsToFloat(vertices[base + 1]) - 0.5f) * away.getStepY()
                + (Float.intBitsToFloat(vertices[base + 2]) - 0.5f) * away.getStepZ();
        }

        /** Sutherland-Hodgman against the middle plane; a quad, or two for a pentagon. Position at 0-2, UV at 4-5. */
        private void cutQuad(final BakedQuad quad, final List<BakedQuad> out) {
            final int[] vertices = quad.getVertices();
            final int stride = vertices.length / 4;
            final float[] d = new float[4];
            boolean any = false;
            boolean all = true;
            for (int i = 0; i < 4; i++) {
                d[i] = distance(vertices, i * stride);
                any |= d[i] <= 1.0e-5f;
                all &= d[i] <= 1.0e-5f;
            }
            if (all) {
                out.add(quad);
                return;
            }
            if (!any) {
                return;
            }
            final List<int[]> polygon = new ArrayList<>(5);
            for (int i = 0; i < 4; i++) {
                final int j = (i + 1) % 4;
                final boolean inI = d[i] <= 1.0e-5f;
                final boolean inJ = d[j] <= 1.0e-5f;
                if (inI) {
                    polygon.add(vertex(vertices, i * stride, stride));
                }
                if (inI != inJ) {
                    final float t = d[i] / (d[i] - d[j]);
                    final int[] v = vertex(vertices, i * stride, stride);
                    for (final int k : new int[]{0, 1, 2, 4, 5}) {
                        final float a = Float.intBitsToFloat(vertices[i * stride + k]);
                        final float b = Float.intBitsToFloat(vertices[j * stride + k]);
                        v[k] = Float.floatToRawIntBits(a + (b - a) * t);
                    }
                    polygon.add(v);
                }
            }
            if (polygon.size() < 3) {
                return;
            }
            emit(quad, stride, polygon.get(0), polygon.get(1), polygon.get(2), polygon.size() > 3 ? polygon.get(3) : polygon.get(2), out);
            if (polygon.size() == 5) {
                emit(quad, stride, polygon.get(0), polygon.get(3), polygon.get(4), polygon.get(4), out);
            }
        }

        private static int[] vertex(final int[] vertices, final int base, final int stride) {
            final int[] v = new int[stride];
            System.arraycopy(vertices, base, v, 0, stride);
            return v;
        }

        private static void emit(final BakedQuad like, final int stride, final int[] a, final int[] b, final int[] c, final int[] e,
                                 final List<BakedQuad> out) {
            final int[] vertices = new int[stride * 4];
            System.arraycopy(a, 0, vertices, 0, stride);
            System.arraycopy(b, 0, vertices, stride, stride);
            System.arraycopy(c, 0, vertices, stride * 2, stride);
            System.arraycopy(e, 0, vertices, stride * 3, stride);
            out.add(new BakedQuad(vertices, like.getTintIndex(), like.getDirection(), like.getSprite(), like.isShade(), like.hasAmbientOcclusion()));
        }
    }
}
