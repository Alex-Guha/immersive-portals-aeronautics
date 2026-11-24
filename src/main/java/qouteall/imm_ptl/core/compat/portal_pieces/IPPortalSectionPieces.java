/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.portal_pieces;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
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
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.mixin_create.IPLevelRendererSectionInvoker;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;

/**
 * Pieces of one kind drawn into the chunk mesh, by section: pieces that sit still in the world (pipe ends, track
 * halves). IP's portal blocks are never meshed ({@code RenderShape.INVISIBLE}), so a piece in one goes in through
 * NeoForge's {@link AddSectionGeometryEvent} (Sodium runs it too). A kind hands over what its walk found
 * ({@link #publish}); the sections whose pieces changed are re-meshed with the walk's priority: a block the player
 * places or breaks is meshed in the frame it changes (vanilla and Sodium, within 16 blocks of the camera), so the
 * piece must be too, or one half of the part shows up before the other. Ships' plots are meshed by Sable's own
 * compiler, which runs no such event: pieces on ships are Flywheel instances ({@link IPPortalWorldEffect}).
 */
public final class IPPortalSectionPieces<T> {
    /** Draws one piece into the section being meshed; {@code origin} is the section's lowest corner. Worker threads. */
    @FunctionalInterface
    public interface Draw<T> {
        void draw(AddSectionGeometryEvent.SectionRenderingContext context, BlockPos origin, T piece);
    }

    /** Every cull face and none: a piece draws all its faces. */
    public static final Direction[] SIDES = {null, Direction.DOWN, Direction.UP, Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST};
    private static final float FACE_EPSILON = 1.0e-4f;

    /** The kinds whose pieces are drawn, in the order they were made. */
    private static final List<IPPortalSectionPieces<?>> DRAWN = new CopyOnWriteArrayList<>();

    private final @Nullable Draw<T> draw;
    /** Per dimension, per section ({@link SectionPos#asLong}), the pieces in it. Replaced whole by {@link #publish}. */
    private volatile Map<ResourceKey<Level>, Map<Long, List<T>>> pieces = Map.of();

    private IPPortalSectionPieces(final @Nullable Draw<T> draw) {
        this.draw = draw;
    }

    /**
     * A kind of piece. {@code draw} null: nothing drawn, but the sections are re-meshed when their entries change (for a
     * part whose own model asks about them while meshing). Client setup or class init.
     */
    public static <T> IPPortalSectionPieces<T> create(final @Nullable Draw<T> draw) {
        final IPPortalSectionPieces<T> made = new IPPortalSectionPieces<>(draw);
        if (draw != null) {
            DRAWN.add(made);
        }
        return made;
    }

    /** Adds {@code piece}, in the portal block (or other block) at {@code pos} of {@code dimension}, to a walk's map. */
    public static <T> void add(final Map<ResourceKey<Level>, Map<Long, List<T>>> found, final ResourceKey<Level> dimension, final BlockPos pos, final T piece) {
        found.computeIfAbsent(dimension, d -> new HashMap<>()).computeIfAbsent(SectionPos.asLong(pos), s -> new ArrayList<>()).add(piece);
    }

    /** The pieces now, per dimension, per section. Any thread. */
    public Map<ResourceKey<Level>, Map<Long, List<T>>> current() {
        return pieces;
    }

    /** A walk's pieces replace the last walk's; changed sections are re-meshed, at once when {@code important}. Main thread. */
    public void publish(final Map<ResourceKey<Level>, Map<Long, List<T>>> found, final boolean important) {
        final Map<ResourceKey<Level>, Map<Long, List<T>>> previous = pieces;
        pieces = found;
        remesh(previous, found, important);
        remesh(found, previous, important);
    }

    /** Re-meshes the sections of {@code from} whose pieces differ in {@code to}, at once when {@code important}. */
    private static <T> void remesh(
        final Map<ResourceKey<Level>, Map<Long, List<T>>> from, final Map<ResourceKey<Level>, Map<Long, List<T>>> to, final boolean important
    ) {
        for (final Map.Entry<ResourceKey<Level>, Map<Long, List<T>>> dimension : from.entrySet()) {
            final Map<Long, List<T>> other = to.getOrDefault(dimension.getKey(), Map.of());
            LevelRenderer renderer = null;
            for (final Map.Entry<Long, List<T>> section : dimension.getValue().entrySet()) {
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

    /** Game bus: adds every kind's pieces in a section being meshed. */
    public static void onAddSectionGeometry(final AddSectionGeometryEvent event) {
        final long section = SectionPos.asLong(event.getSectionOrigin());
        for (final IPPortalSectionPieces<?> kind : DRAWN) {
            kind.addTo(event, section);
        }
    }

    private void addTo(final AddSectionGeometryEvent event, final long section) {
        final Map<Long, List<T>> sections = pieces.get(event.getLevel().dimension());
        final List<T> here = sections == null ? null : sections.get(section);
        if (here == null) {
            return;
        }
        final BlockPos origin = event.getSectionOrigin().immutable();
        final Draw<T> drawing = Objects.requireNonNull(draw);
        event.addRenderer(context -> {
            for (final T piece : here) {
                drawing.draw(context, origin, piece);
            }
        });
    }

    /**
     * All of {@code model}'s quads at the pose's top, each through {@code clip} first (null: as they are; a null result:
     * left out), shaded as the block renderer shades them.
     */
    public static void putModel(
        final AddSectionGeometryEvent.SectionRenderingContext context, final PoseStack poses, final BakedModel model,
        final BlockState state, final ModelData data, final RandomSource random, final int light, final @Nullable UnaryOperator<BakedQuad> clip
    ) {
        final BlockAndTintGetter region = context.getRegion();
        for (final RenderType renderType : model.getRenderTypes(state, random, data)) {
            final VertexConsumer consumer = context.getOrCreateChunkBuffer(renderType);
            for (final Direction side : SIDES) {
                for (final BakedQuad quad : model.getQuads(state, side, random, data, renderType)) {
                    final BakedQuad drawn = clip == null ? quad : clip.apply(quad);
                    if (drawn != null) {
                        final float shade = region.getShade(drawn.getDirection(), drawn.isShade());
                        consumer.putBulkData(poses.last(), drawn, shade, shade, shade, 1, light, OverlayTexture.NO_OVERLAY);
                    }
                }
            }
        }
    }

    /**
     * The part of {@code quad} (block-local) past the block's face towards {@code toward}, positions and texture
     * coordinates interpolated; null when none of it is. A block model cut to what reaches into the next block: the
     * piece of a part in the portal block beside it.
     */
    public static @Nullable BakedQuad pastFace(final BakedQuad quad, final Direction toward) {
        final int[] vertices = quad.getVertices();
        final int stride = vertices.length / 4;
        final int axis = toward.getAxis().ordinal(); // x, y, z are the first three ints of each vertex
        final boolean positive = toward.getAxisDirection() == Direction.AxisDirection.POSITIVE;
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
                kept.add(Arrays.copyOfRange(vertices, v * stride, v * stride + stride));
            }
            if (inside[v] != inside[next]) {
                final float t = (face - along[v]) / (along[next] - along[v]);
                final int[] vertex = Arrays.copyOfRange(vertices, v * stride, v * stride + stride);
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
