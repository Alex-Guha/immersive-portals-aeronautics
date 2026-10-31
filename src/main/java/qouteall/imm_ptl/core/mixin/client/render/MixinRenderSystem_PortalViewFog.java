/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.render.IPPortalViewDistance;

/**
 * Portal view distance ({@link IPPortalViewDistance}): the terrain fog of a portal pass with a cut ends at the cut.
 * Clamped where every fog change ends, so it wins over NeoForge's fog event and other mods' {@code FogRenderer}
 * mixins (Distant Horizons pushing vanilla fog out, Voxy, Roxy, Cirrus).
 */
@Mixin(value = RenderSystem.class, remap = false)
public class MixinRenderSystem_PortalViewFog {
    @ModifyVariable(method = "setShaderFogStart", at = @At("HEAD"), argsOnly = true)
    private static float ip_clampPortalViewFogStart(float start) {
        return IPPortalViewDistance.clampFogStart(start);
    }

    @ModifyVariable(method = "setShaderFogEnd", at = @At("HEAD"), argsOnly = true)
    private static float ip_clampPortalViewFogEnd(float end) {
        return IPPortalViewDistance.clampFogEnd(end);
    }
}
