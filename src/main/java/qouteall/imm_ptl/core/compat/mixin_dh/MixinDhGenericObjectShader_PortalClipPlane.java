/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_dh;

import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.common.render.openGl.generic.GlGenericObjectShaderProgram;
import com.seibel.distanthorizons.common.render.openGl.glObject.shader.GlShaderProgram;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.dh_integration.IPDhPortalClip;

/**
 * Feeds IP's front clipping plane (in clip space) to DH's generic object shaders, which draw DH's
 * clouds and beacons. {@code bind(DhApiRenderParam)} binds the program once per pass.
 */
@Mixin(value = GlGenericObjectShaderProgram.class, remap = false)
public class MixinDhGenericObjectShader_PortalClipPlane {
    @Unique
    private int ip_clipUniformLocation = IPDhPortalClip.UNRESOLVED;

    @Inject(
        method = "bind(Lcom/seibel/distanthorizons/api/methods/events/sharedParameterObjects/DhApiRenderParam;)V",
        at = @At("TAIL"),
        require = 0
    )
    private void ip_setPortalClipPlane(DhApiRenderParam renderParam, CallbackInfo ci) {
        ip_clipUniformLocation = IPDhPortalClip.setPlaneUniform(
            ((GlShaderProgram) (Object) this).id, ip_clipUniformLocation,
            IPDhPortalClip.CLIP_SPACE_UNIFORM, IPDhPortalClip.clipSpacePlane(renderParam)
        );
    }
}
