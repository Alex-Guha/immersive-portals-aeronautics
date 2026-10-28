/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.AllPartialModels;
import com.simibubi.create.content.fluids.FluidPropagator;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.FluidTransportBehaviour.AttachmentTypes;
import com.simibubi.create.content.fluids.FluidTransportBehaviour.AttachmentTypes.ComponentPartials;
import com.simibubi.create.content.fluids.pipes.FluidPipeBlock;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.Nullable;

/**
 * The look of a Create pipe end at a portal ({@link IPCreatePortalPipes}). Next to a portal block the pipe's end
 * stops half a block short of the portal plane, the middle of the portal block. Its rim (or drain) there is replaced
 * by the plain connection it would have towards another pipe, and Create's connection piece is repeated twice past
 * the block face, filling the gap up to the plane. The far side does the same, so the pipe runs through the portal
 * unbroken. The added pieces have no end caps, so nothing lies in the portal plane to z-fight with it.
 * <p>
 * Meshing runs on Sodium's worker threads with a block snapshot, so this goes by block alone: a placeholder block
 * whose plane faces the pipe ({@link IPCreatePortalPipes#isPlaceholderFacing}). Frameless portals get the fluid but
 * not the filler. A pipe whose space a belt or shaft across the portal owns keeps its own end
 * ({@link IPCreatePortalPipeStubs#isOutranked}). Mixin: {@code MixinPipeAttachmentModel_PortalPipes}.
 */
public final class IPCreatePortalPipeModel {
    /** Bit per {@link Direction#get3DDataValue()}: pipe faces that open onto a portal block. */
    private static final ModelProperty<Integer> PORTAL_FACES = new ModelProperty<>();
    /** Create's connection piece is 4 pixels long; two copies reach the plane 8 pixels past the block face. */
    private static final float[] FILLER_OFFSETS = {4 / 16f, 8 / 16f};
    private static final int CACHE_LIMIT = 4096;

    /** Per offset, Create's connection quads moved by it (filled while meshing, on worker threads). */
    @SuppressWarnings("unchecked")
    private static final Map<BakedQuad, BakedQuad>[] MOVED = new Map[]{new ConcurrentHashMap<>(), new ConcurrentHashMap<>()};

    private IPCreatePortalPipeModel() {
    }

    private static boolean opensOntoPortal(
        final BlockAndTintGetter world, final BlockPos pos, final BlockState state, final FluidTransportBehaviour transport, final Direction side
    ) {
        return transport.canHaveFlowToward(state, side) && IPCreatePortalPipes.isPlaceholderFacing(world.getBlockState(pos.relative(side)), side)
            && !IPCreatePortalPipeStubs.isOutranked(pos, side);
    }

    /** The attachment Create picked for a pipe face, with a rim or drain at a portal turned into a connection. */
    public static AttachmentTypes attachment(
        final AttachmentTypes picked, final FluidTransportBehaviour transport, final BlockAndTintGetter world,
        final BlockPos pos, final BlockState state, final Direction side
    ) {
        if (picked == AttachmentTypes.NONE || picked == AttachmentTypes.CONNECTION || picked == AttachmentTypes.DETAILED_CONNECTION
            || !opensOntoPortal(world, pos, state, transport, side)) {
            return picked;
        }
        if (!FluidPipeBlock.isPipe(state)) {
            return AttachmentTypes.NONE; // glass pipes, valves, smart pipes: their own model reaches the block face
        }
        return FluidPropagator.getStraightPipeAxis(state) == side.getAxis() ? AttachmentTypes.CONNECTION : AttachmentTypes.DETAILED_CONNECTION;
    }

    /** Records which faces of the pipe open onto a portal block, for {@link #withFillers}. */
    public static void addPortalFaces(final ModelData.Builder builder, final BlockAndTintGetter world, final BlockPos pos, final BlockState state) {
        final FluidTransportBehaviour transport = BlockEntityBehaviour.get(world, pos, FluidTransportBehaviour.TYPE);
        if (transport == null) {
            return;
        }
        int faces = 0;
        for (final Direction side : Direction.values()) {
            if (opensOntoPortal(world, pos, state, transport, side)) {
                faces |= 1 << side.get3DDataValue();
            }
        }
        if (faces != 0) {
            builder.with(PORTAL_FACES, faces);
        }
    }

    /** The pipe's quads plus the gap fillers of its portal faces, or null when it has none. */
    public static @Nullable List<BakedQuad> withFillers(
        final List<BakedQuad> quads, final BlockState state, final @Nullable Direction cullFace, final RandomSource rand,
        final ModelData data, final @Nullable RenderType renderType
    ) {
        final Integer faces = cullFace == null ? data.get(PORTAL_FACES) : null;
        if (faces == null) {
            return null;
        }
        final List<BakedQuad> out = new ArrayList<>(quads);
        for (final Direction side : Direction.values()) {
            if ((faces & (1 << side.get3DDataValue())) == 0) {
                continue;
            }
            final BakedModel connection = AllPartialModels.PIPE_ATTACHMENTS.get(ComponentPartials.CONNECTION).get(side).get();
            if (renderType != null && !connection.getRenderTypes(state, rand, data).contains(renderType)) {
                continue; // asked once per render type of the pipe; the piece belongs to its own only
            }
            final List<BakedQuad> piece = connection.getQuads(state, null, rand, data, renderType);
            for (int i = 0; i < FILLER_OFFSETS.length; i++) {
                final Map<BakedQuad, BakedQuad> cache = MOVED[i];
                if (cache.size() > CACHE_LIMIT) {
                    cache.clear(); // stale quads after resource reloads
                }
                final float offset = FILLER_OFFSETS[i];
                for (final BakedQuad quad : piece) {
                    out.add(cache.computeIfAbsent(quad, q -> moved(q, side, offset)));
                }
            }
        }
        return out;
    }

    /** {@code quad} moved {@code distance} blocks towards {@code side}. */
    private static BakedQuad moved(final BakedQuad quad, final Direction side, final float distance) {
        final int[] vertices = quad.getVertices().clone();
        final int stride = vertices.length / 4;
        final float dx = side.getStepX() * distance;
        final float dy = side.getStepY() * distance;
        final float dz = side.getStepZ() * distance;
        for (int v = 0; v < 4; v++) {
            final int base = v * stride;
            vertices[base] = Float.floatToRawIntBits(Float.intBitsToFloat(vertices[base]) + dx);
            vertices[base + 1] = Float.floatToRawIntBits(Float.intBitsToFloat(vertices[base + 1]) + dy);
            vertices[base + 2] = Float.floatToRawIntBits(Float.intBitsToFloat(vertices[base + 2]) + dz);
        }
        return new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), quad.getSprite(), quad.isShade(), quad.hasAmbientOcclusion());
    }
}
