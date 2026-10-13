/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Create's portal-track model ({@code create:block/track/teleport}, shapes TN/TS/TE/TW) runs its rails half a block
 * into the portal block, ending exactly in the middle of it, where IP puts the portal plane. The rails' end caps lie in
 * the portal surface and z-fight with it when the portal's back face is looked at (bi-faced nether portals). This
 * drops every quad lying in that plane; the rails stay open-ended there, which is invisible because the track continues
 * in the portal view.
 */
public final class IPCreatePortalTrackModel {
    private static final float PLANE_EPSILON = 1.0e-4f;

    private IPCreatePortalTrackModel() {
    }

    /** Mod bus, client, {@code EventPriority.LOWEST}: wraps whatever Create put there ({@code TrackModel}). */
    public static void onModifyBakingResult(ModelEvent.ModifyBakingResult event) {
        Map<ModelResourceLocation, BakedModel> models = event.getModels();
        for (Block block : BuiltInRegistries.BLOCK) {
            if (!(block instanceof TrackBlock)) {
                continue;
            }
            for (BlockState state : block.getStateDefinition().getPossibleStates()) {
                Direction portalSide = portalSide(state.getValue(TrackBlock.SHAPE));
                if (portalSide == null) {
                    continue;
                }
                ModelResourceLocation location = BlockModelShaper.stateToModelLocation(state);
                BakedModel model = models.get(location);
                if (model != null && !(model instanceof SeamCut)) {
                    models.put(location, new SeamCut(model, portalSide));
                }
            }
        }
    }

    private static @Nullable Direction portalSide(TrackShape shape) {
        return switch (shape) {
            case TN -> Direction.NORTH;
            case TS -> Direction.SOUTH;
            case TE -> Direction.EAST;
            case TW -> Direction.WEST;
            default -> null;
        };
    }

    private static final class SeamCut extends BakedModelWrapper<BakedModel> {
        private final Direction.Axis axis;
        /** Block-local coordinate of the portal plane: the centre of the neighbouring portal block. */
        private final float plane;

        SeamCut(BakedModel original, Direction portalSide) {
            super(original);
            this.axis = portalSide.getAxis();
            this.plane = 0.5f + portalSide.getAxisDirection().getStep();
        }

        @Override
        public @NotNull List<BakedQuad> getQuads(
            @Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand,
            @NotNull ModelData extraData, @Nullable RenderType renderType
        ) {
            List<BakedQuad> quads = super.getQuads(state, side, rand, extraData, renderType);
            List<BakedQuad> kept = null;
            for (int i = 0; i < quads.size(); i++) {
                BakedQuad quad = quads.get(i);
                if (liesInPlane(quad)) {
                    if (kept == null) {
                        kept = new ArrayList<>(quads.subList(0, i));
                    }
                } else if (kept != null) {
                    kept.add(quad);
                }
            }
            return kept == null ? quads : kept;
        }

        private boolean liesInPlane(BakedQuad quad) {
            int[] vertices = quad.getVertices();
            int stride = vertices.length / 4;
            int offset = axis.ordinal(); // x, y, z are the first three ints of each vertex
            for (int v = 0; v < 4; v++) {
                float coordinate = Float.intBitsToFloat(vertices[v * stride + offset]);
                if (Math.abs(coordinate - plane) > PLANE_EPSILON) {
                    return false;
                }
            }
            return true;
        }
    }
}
