/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import java.awt.Color;
import java.util.Optional;
import net.minecraft.client.renderer.texture.SpriteContents;
import net.minecraft.client.renderer.texture.atlas.SpriteSource;
import net.minecraft.client.renderer.texture.atlas.SpriteSourceType;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.client.event.RegisterSpriteSourceTypesEvent;
import org.slf4j.Logger;

/**
 * Atlas sprite source ({@code assets/minecraft/atlases/blocks.json}): a gold copy of one of Create's purple portal-track
 * textures, recoloured from the installed Create's own texture when the block atlas is stitched. Create's assets are
 * All Rights Reserved, so we ship no copy of them, only this recolouring. Used by {@link IPCreatePortalTrackModel} for
 * tracks into glowstone-framed portals (the Aether's).
 */
public record IPCreateGoldPortalTrackSource(ResourceLocation resource, ResourceLocation sprite) implements SpriteSource {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final MapCodec<IPCreateGoldPortalTrackSource> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
        ResourceLocation.CODEC.fieldOf("resource").forGetter(IPCreateGoldPortalTrackSource::resource),
        ResourceLocation.CODEC.fieldOf("sprite").forGetter(IPCreateGoldPortalTrackSource::sprite)
    ).apply(instance, IPCreateGoldPortalTrackSource::new));

    /** Hue of the gold, in [0, 1). */
    private static final float GOLD_HUE = 0.12f;

    private static final SpriteSourceType TYPE = new SpriteSourceType(CODEC);

    /** Mod bus, client. Registered with or without Create: our atlas file names this type. */
    public static void onRegisterSpriteSourceTypes(final RegisterSpriteSourceTypesEvent event) {
        event.register(ResourceLocation.fromNamespaceAndPath("immersive_portals", "create_gold_portal_track"), TYPE);
    }

    @Override
    public void run(final ResourceManager resourceManager, final Output output) {
        final Optional<Resource> texture = resourceManager.getResource(TEXTURE_ID_CONVERTER.idToFile(resource));
        if (texture.isEmpty()) {
            if (!ModList.get().isLoaded(resource.getNamespace())) {
                return;
            }
            LOGGER.warn("[IPCreatePortalTracks] no texture {} to recolour into {}", resource, sprite);
            return;
        }
        output.add(sprite, loader -> {
            final SpriteContents contents = loader.loadSprite(sprite, texture.get());
            if (contents != null) {
                contents.getOriginalImage().applyToAllPixels(IPCreateGoldPortalTrackSource::toGold);
            }
            return contents;
        });
    }

    @Override
    public SpriteSourceType type() {
        return TYPE;
    }

    /** Turns the purple to magenta glow into gold; greys and Create's rail browns stay. Pixels are ABGR. */
    private static int toGold(final int abgr) {
        final int r = abgr & 0xFF;
        final int g = (abgr >> 8) & 0xFF;
        final int b = (abgr >> 16) & 0xFF;
        final float[] hsv = Color.RGBtoHSB(r, g, b, null);
        if (hsv[1] <= 0.06f || (hsv[0] <= 0.6f && hsv[0] >= 0.015f)) {
            return abgr;
        }
        final int rgb = Color.HSBtoRGB(GOLD_HUE, Math.min(1.0f, hsv[1] * 1.15f), hsv[2]);
        return (abgr & 0xFF000000) | ((rgb & 0xFF) << 16) | (rgb & 0xFF00) | ((rgb >> 16) & 0xFF);
    }
}
