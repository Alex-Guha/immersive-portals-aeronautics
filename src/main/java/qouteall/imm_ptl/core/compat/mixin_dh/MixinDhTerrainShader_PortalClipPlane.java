/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.terrain.GlDhTerrainShaderProgram_neoforge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalClip;

/** Feeds IP's front clipping plane to DH's terrain (LOD) shader; the program is bound when this runs. */
@Mixin(value = GlDhTerrainShaderProgram_neoforge.class, remap = false)
public class MixinDhTerrainShader_PortalClipPlane {
    @Unique
    private int ip_clipUniformLocation = IPDhPortalClip.UNRESOLVED;

    @Inject(
        method = "fillUniformData",
        at = @At("TAIL"),
        require = 0
    )
    private void ip_setPortalClipPlane(DhApiRenderParam renderParameters, CallbackInfo ci) {
        ip_clipUniformLocation = IPDhPortalClip.setPlaneUniform(
            ((GlShaderProgram) (Object) this).id, ip_clipUniformLocation,
            IPDhPortalClip.WORLD_UNIFORM, IPDhPortalClip.worldPlane()
        );
    }
}
