/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.effect.HeatedCulledRenderRegion;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPBalloonOverlayClip;

/**
 * Draws each balloon's hot-air overlay through {@link IPBalloonOverlayClip}, which cuts the overlay of a straddling
 * ship at its portal plane.
 */
@Mixin(
    targets = "dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.effect.ClientBalloonEffectRenderer",
    remap = false
)
public class MixinAeronauticsBalloonEffect_StraddleCut {
    @Redirect(
        method = "renderBalloonEffects",
        at = @At(
            value = "INVOKE",
            target = "Ldev/eriksonn/aeronautics/content/blocks/hot_air/balloon/effect/HeatedCulledRenderRegion;render(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"
        ),
        require = 0
    )
    private static void ip_cutAtStraddlePlane(HeatedCulledRenderRegion region, Matrix4f modelView, Matrix4f projection) {
        IPBalloonOverlayClip.render(region, modelView, projection);
    }
}
