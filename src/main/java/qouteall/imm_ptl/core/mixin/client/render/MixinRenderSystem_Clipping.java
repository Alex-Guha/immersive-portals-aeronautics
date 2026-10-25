/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.renderer.ShaderInstance;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.function.Supplier;

@Mixin(value = RenderSystem.class, remap = false)
public class MixinRenderSystem_Clipping {
    @Inject(
        method = "Lcom/mojang/blaze3d/systems/RenderSystem;setShader(Ljava/util/function/Supplier;)V",
        at = @At("RETURN")
    )
    private static void onSetShader(Supplier<ShaderInstance> supplier, CallbackInfo ci) {
        if (IPGlobal.enableClippingMechanism) {
            // Immersive Aeronautics: fed with a shader pack too. With Iris merely installed the vanilla shaders need
            // it, or entities and block entities (chests, beds, heads on ships) are not clipped in portal views; with
            // a pack, its entity and block entity programs carry the plane as well (shader_transformation.json), and
            // a pack program without it has no uniform to feed.
            if (CrossPortalEntityRenderer.isRenderingEntityNormally ||
                CrossPortalEntityRenderer.isRenderingEntityProjection
            ) {
                FrontClipping.updateClippingEquationUniformForCurrentShader(true);
            }
            else if (RenderStates.isRenderingPortalWeather) {
                FrontClipping.updateClippingEquationUniformForCurrentShader(false);
            }
            else {
                FrontClipping.unsetClippingUniform();
            }
        }
        
    }
}
