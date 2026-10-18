/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.BlockModelShaper;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.client.renderer.texture.MissingTextureAtlasSprite;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.client.resources.model.ModelResourceLocation;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;
import net.minecraft.world.inventory.InventoryMenu;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.event.ModelEvent;
import net.neoforged.neoforge.client.model.BakedModelWrapper;
import net.neoforged.neoforge.client.model.data.ModelData;
import net.neoforged.neoforge.client.model.data.ModelProperty;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Create's portal-track model ({@code create:block/track/teleport}, shapes TN/TS/TE/TW), wrapped:
 * <ul>
 *     <li>It runs its rails half a block into the portal block, ending exactly in the middle of it, where IP puts the
 *     portal plane. The rails' end caps lie in the portal surface and z-fight with it when the portal's back face is
 *     looked at (bi-faced nether portals). Every quad lying in that plane is dropped; the rails stay open-ended there,
 *     which is invisible because the track continues in the portal view.</li>
 *     <li>Its rails glow purple towards the portal, which suits nether portals only. The glow follows the portal's
 *     frame ({@link Glow}): gold for glowstone (the Aether's), plain rails for anything else that is not obsidian.
 *     Found per block while the chunk is meshed ({@link #getModelData}).</li>
 * </ul>
 */
public final class IPCreatePortalTrackModel {
    private static final float PLANE_EPSILON = 1.0e-4f;
    /** Int offset of a vertex's texture coordinates in a block quad (after 3 position floats and the colour). */
    private static final int UV_OFFSET = 4;
    /**
     * Blocks below the portal block next to the track searched for the frame. Sodium's meshing region reaches 2 blocks
     * past the section and reads air beyond, so no further.
     */
    private static final int FRAME_SEARCH = 2;

    private static final ResourceLocation PORTAL_TRACK = create("portal_track");
    private static final ResourceLocation PORTAL_TRACK_MIP = create("portal_track_mip");

    private static final ModelProperty<Glow> GLOW = new ModelProperty<>();

    /** The look of the rails' glow towards the portal. */
    enum Glow {
        /** Create's own purple: nether portals (obsidian frames), end portals, and when no frame is found. */
        CREATE(Map.of()),
        /** Glowstone frames (the Aether): Create's texture recoloured gold ({@link IPCreateGoldPortalTrackSource}). */
        GOLD(Map.of(
            PORTAL_TRACK, ResourceLocation.fromNamespaceAndPath("immersive_portals", "block/create_portal_track_gold"),
            PORTAL_TRACK_MIP, ResourceLocation.fromNamespaceAndPath("immersive_portals", "block/create_portal_track_gold_mip")
        )),
        /** Any other frame, or none (wand portals): Create's ordinary rails, which share the portal texture's layout. */
        PLAIN(Map.of(
            PORTAL_TRACK, create("standard_track"),
            PORTAL_TRACK_MIP, create("standard_track_mip")
        ));

        /** Create's portal-track textures and what replaces them. */
        final Map<ResourceLocation, ResourceLocation> textures;

        Glow(final Map<ResourceLocation, ResourceLocation> textures) {
            this.textures = textures;
        }
    }

    private IPCreatePortalTrackModel() {
    }

    private static ResourceLocation create(final String texture) {
        return ResourceLocation.fromNamespaceAndPath("create", "block/" + texture);
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

    /** The glow for a track running into the portal block at {@code portalPos}: by the frame block below it. */
    static Glow glowFor(final BlockAndTintGetter level, final BlockPos portalPos) {
        final BlockState portal = level.getBlockState(portalPos);
        if (portal.is(Blocks.END_PORTAL)) {
            return Glow.CREATE;
        }
        final BlockPos.MutableBlockPos pos = portalPos.mutable();
        for (int i = 0; i < FRAME_SEARCH; i++) {
            final BlockState below = level.getBlockState(pos.move(Direction.DOWN));
            if (below.getBlock() == portal.getBlock()) {
                continue;
            }
            if (below.is(Blocks.GLOWSTONE)) {
                return Glow.GOLD;
            }
            // obsidian, crying obsidian, and other mods' obsidian frames (Netherstorm's polished obsidian)
            return BuiltInRegistries.BLOCK.getKey(below.getBlock()).getPath().contains("obsidian") ? Glow.CREATE : Glow.PLAIN;
        }
        return Glow.CREATE;
    }

    private static final class SeamCut extends BakedModelWrapper<BakedModel> {
        private final Direction portalSide;
        private final Direction.Axis axis;
        /** Block-local coordinate of the portal plane: the centre of the neighbouring portal block. */
        private final float plane;
        /** Per glow, Create's quads with their texture replaced (filled while meshing, on Sodium's worker threads). */
        private final Map<Glow, Map<BakedQuad, BakedQuad>> retextured = new EnumMap<>(Map.of(
            Glow.GOLD, new ConcurrentHashMap<>(), Glow.PLAIN, new ConcurrentHashMap<>()
        ));

        SeamCut(BakedModel original, Direction portalSide) {
            super(original);
            this.portalSide = portalSide;
            this.axis = portalSide.getAxis();
            this.plane = 0.5f + portalSide.getAxisDirection().getStep();
        }

        @Override
        public @NotNull ModelData getModelData(
            @NotNull BlockAndTintGetter level, @NotNull BlockPos pos, @NotNull BlockState state, @NotNull ModelData modelData
        ) {
            ModelData data = super.getModelData(level, pos, state, modelData);
            Glow glow = glowFor(level, pos.relative(portalSide));
            return glow == Glow.CREATE ? data : data.derive().with(GLOW, glow).build();
        }

        @Override
        public @NotNull List<BakedQuad> getQuads(
            @Nullable BlockState state, @Nullable Direction side, @NotNull RandomSource rand,
            @NotNull ModelData extraData, @Nullable RenderType renderType
        ) {
            List<BakedQuad> quads = super.getQuads(state, side, rand, extraData, renderType);
            Glow glow = extraData.get(GLOW);
            Map<BakedQuad, BakedQuad> swapped = glow == null ? null : retextured.get(glow);
            List<BakedQuad> kept = null;
            for (int i = 0; i < quads.size(); i++) {
                BakedQuad quad = quads.get(i);
                BakedQuad shown = liesInPlane(quad) ? null : swapped == null ? quad : swapped.computeIfAbsent(quad, q -> retexture(q, glow));
                if (shown != quad && kept == null) {
                    kept = new ArrayList<>(quads.subList(0, i));
                }
                if (kept != null && shown != null) {
                    kept.add(shown);
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

    /** @return {@code quad} drawn with {@code glow}'s texture in place of Create's portal-track one, or as it is */
    private static BakedQuad retexture(final BakedQuad quad, final Glow glow) {
        final TextureAtlasSprite from = quad.getSprite();
        final ResourceLocation replacement = glow.textures.get(from.contents().name());
        if (replacement == null) {
            return quad;
        }
        final TextureAtlas atlas = Minecraft.getInstance().getModelManager().getAtlas(InventoryMenu.BLOCK_ATLAS);
        final TextureAtlasSprite to = atlas.getSprite(replacement);
        if (to.contents().name().equals(MissingTextureAtlasSprite.getLocation())) {
            return quad;
        }
        final int[] vertices = quad.getVertices().clone();
        final int stride = vertices.length / 4;
        for (int v = 0; v < 4; v++) {
            final int u = v * stride + UV_OFFSET;
            vertices[u] = Float.floatToRawIntBits(to.getU(from.getUOffset(Float.intBitsToFloat(vertices[u]))));
            vertices[u + 1] = Float.floatToRawIntBits(to.getV(from.getVOffset(Float.intBitsToFloat(vertices[u + 1]))));
        }
        return new BakedQuad(vertices, quad.getTintIndex(), quad.getDirection(), to, quad.isShade(), quad.hasAmbientOcclusion());
    }
}
