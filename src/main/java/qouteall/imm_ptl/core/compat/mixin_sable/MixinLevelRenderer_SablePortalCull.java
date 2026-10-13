/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPSablePortalCull;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * While a portal pass is rendering with Sable's vanilla (non-Sodium) renderer, drives
 * Sable's sub-level culling/compilation for the portal-side world at the start of the
 * solid section layer, so ships are visible through portals.
 * Delegates to {@link IPSablePortalCull#cullForPortalSideWorld}.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_SablePortalCull {
    @Shadow
    private ClientLevel level;

    @Inject(
        method = "renderSectionLayer",
        at = @At("HEAD"),
        require = 0
    )
    private void ip_cullSableSubLevelsForPortalPass(
        RenderType renderType, double camX, double camY, double camZ,
        Matrix4f modelViewMatrix, Matrix4f projectionMatrix, CallbackInfo ci
    ) {
        if (this.level == null) {
            return;
        }
        if (!PortalRendering.isRendering()) {
            return;
        }
        if (renderType != RenderType.solid()) {
            return;
        }
        IPSablePortalCull.cullForPortalSideWorld(
            (LevelRenderer) (Object) this, this.level,
            camX, camY, camZ, modelViewMatrix, projectionMatrix
        );
    }
}
