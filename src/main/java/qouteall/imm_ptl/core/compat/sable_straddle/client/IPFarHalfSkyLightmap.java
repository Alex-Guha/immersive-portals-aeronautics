/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.client.renderer.texture.DynamicTexture;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL13;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.mixin.client.render.IELightTexture;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * The sky of the destination for the far half of a ship from a world without sky light (the nether). Such a ship's
 * blocks are meshed with sky light 0 (its world's light engine has no sky layer), which Sable's sky-light factor
 * ({@link IPStraddleRender#farHalfSkyLight}) cannot raise: past a portal into daylight it stayed dark until it was
 * through. While its far half is drawn, the lightmap unit holds a copy of the destination's lightmap whose every row is
 * the row of the sky light around the far half, so sky 0 reads as that sky. Block entities get the same sky through
 * {@code ClientSubLevel.scaleLightColor} ({@link #farHalfPackedLight}).
 */
public final class IPFarHalfSkyLightmap {
    private static final String LIGHTMAP_SAMPLER = "Sampler2";

    @Nullable
    private static DynamicTexture texture;
    @Nullable
    private static LightTexture filledFrom;
    private static int filledSky = -1;
    private static int filledFrame = -1;
    /** The unit our copy is bound to during a draw, or -1. */
    private static int boundUnit = -1;

    private IPFarHalfSkyLightmap() {
    }

    /**
     * @return the sky light the far half of {@code ship} drawn now takes in place of its own (0), or -1 when it keeps
     * its own: not a projection, a ship of a world with sky light, or drawn in a world without
     */
    public static int replacedSky(final ClientSubLevel ship) {
        if (!IPStraddleRender.isProjecting() || ship.getLevel().dimensionType().hasSkyLight()) {
            return -1;
        }
        final ClientLevel drawn = Minecraft.getInstance().level;
        if (drawn == null || !drawn.dimensionType().hasSkyLight()) {
            return -1;
        }
        final int sky = IPStraddleRender.farHalfSkyLight(ship);
        return sky > 0 ? sky : -1;
    }

    /**
     * @return {@code packed} (a block entity's light on the far half) with the replaced sky, or {@code packed}
     */
    public static int farHalfPackedLight(final ClientSubLevel ship, final int packed) {
        final int sky = replacedSky(ship);
        return sky < 0 ? packed : LightTexture.pack(LightTexture.block(packed), Math.max(sky, LightTexture.sky(packed)));
    }

    /**
     * Before Sable draws the terrain of {@code ship} with {@code shader} (already applied): binds the copy in place of
     * the lightmap when the far half takes the destination's sky. {@link #end} undoes it.
     */
    public static void begin(final ClientSubLevel ship, final ShaderInstance shader) {
        final int sky = replacedSky(ship);
        if (sky < 0 || !(shader instanceof final IEShader ext)) {
            return;
        }
        final int unit = ext.ip_samplerUnit(LIGHTMAP_SAMPLER);
        if (unit < 0) {
            return;
        }
        final int active = GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + unit);
        if (!fill(Minecraft.getInstance().gameRenderer.lightTexture(), sky)) {
            RenderSystem.activeTexture(active);
            return;
        }
        RenderSystem.bindTexture(texture.getId());
        RenderSystem.activeTexture(active);
        boundUnit = unit;
    }

    /** After that draw: the lightmap back on its unit. */
    public static void end() {
        if (boundUnit < 0) {
            return;
        }
        final int active = GlStateManager._getActiveTexture();
        RenderSystem.activeTexture(GL13.GL_TEXTURE0 + boundUnit);
        RenderSystem.bindTexture(RenderSystem.getShaderTexture(2));
        RenderSystem.activeTexture(active);
        boundUnit = -1;
    }

    /** Fills (once per frame, lightmap and sky) and uploads the copy, bound on the active unit. */
    private static boolean fill(final LightTexture lightmap, final int sky) {
        final NativeImage source = ((IELightTexture) lightmap).ip_getLightPixels();
        if (source == null) {
            return false;
        }
        if (texture == null) {
            texture = new DynamicTexture(16, 16, false);
        }
        if (filledFrom == lightmap && filledSky == sky && filledFrame == RenderStates.frameIndex) {
            return true;
        }
        final NativeImage pixels = texture.getPixels();
        if (pixels == null) {
            return false;
        }
        for (int block = 0; block < 16; block++) {
            final int color = source.getPixelRGBA(block, sky);
            for (int row = 0; row < 16; row++) {
                pixels.setPixelRGBA(block, row, color);
            }
        }
        texture.upload();
        filledFrom = lightmap;
        filledSky = sky;
        filledFrame = RenderStates.frameIndex;
        return true;
    }
}
