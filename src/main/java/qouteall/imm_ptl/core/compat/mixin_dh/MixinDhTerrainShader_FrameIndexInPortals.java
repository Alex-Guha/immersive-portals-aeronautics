/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram_neoforge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalView;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * With anti-aliasing on, DH's terrain shader advances an 8-frame index every time its uniforms are
 * filled and uses it for the temporal anti-aliasing sub-pixel pattern. A portal pass advanced it a
 * second time per frame, so the outer world's pattern no longer lined up with its anti-aliasing
 * resolve (jitter, worse with nested portals), and the portal pass itself drew the pattern with no
 * resolve at all (visible jitter inside the portal). In portal passes the advance is undone and the
 * shader gets the "no anti-aliasing" index.
 */
@Mixin(value = GlDhTerrainShaderProgram_neoforge.class, remap = false)
public class MixinDhTerrainShader_FrameIndexInPortals {
    @Shadow
    private int frameIndexMod8;

    @Shadow
    public int uFrameMod8;

    @Inject(
        method = "fillUniformData",
        at = @At("TAIL"),
        require = 0
    )
    private void ip_noTemporalPatternInPortalViews(DhApiRenderParam renderParameters, CallbackInfo ci) {
        if (IPDhPortalView.ENABLED && PortalRendering.isRendering() && this.frameIndexMod8 >= 0) {
            this.frameIndexMod8 = (this.frameIndexMod8 + 7) % 8;
            ((GlShaderProgram) (Object) this).setUniform(this.uFrameMod8, -1);
        }
    }
}
