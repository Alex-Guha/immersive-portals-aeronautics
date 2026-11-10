/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Vanilla's "recompute on the next frame" flag, which {@code LightTexture.tick()} sets. IP sets it on every lightmap it
 * holds ({@code DimensionRenderHelper}), so they update every tick whatever cancels their {@code tick()}:
 * BadOptimizations' lightmap caching lets only one lightmap per game tick through.
 */
@Mixin(LightTexture.class)
public interface IELightTexture {
    @Accessor("updateLightTexture")
    void ip_setUpdateLightTexture(boolean update);
}
