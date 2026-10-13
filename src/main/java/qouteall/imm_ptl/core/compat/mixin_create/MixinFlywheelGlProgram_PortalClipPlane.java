/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import org.lwjgl.opengl.GL20;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPFlywheelPortalClip;
import qouteall.imm_ptl.core.compat.create_integration.IPFlywheelStraddleClip;

/** Feeds IP's front clipping plane to a Flywheel program whenever it is bound during a portal pass. */
@Mixin(value = GlProgram.class, remap = false)
public class MixinFlywheelGlProgram_PortalClipPlane {
    /** True while this program's uniform holds a real plane (so it is reset once outside portal passes). */
    @Unique
    private boolean ip_clipPlaneSet;

    @Inject(
        method = "bind",
        at = @At("TAIL"),
        require = 0,
        expect = 0
    )
    private void ip_setPortalClipPlane(CallbackInfo ci) {
        float[] plane = IPFlywheelPortalClip.activePlane();
        if (plane == null) {
            if (!ip_clipPlaneSet) {
                return;
            }
            plane = IPFlywheelPortalClip.noClipping();
            ip_clipPlaneSet = false;
        }
        else {
            ip_clipPlaneSet = true;
        }
        // Flywheel caches the location and logs once at debug if the program has no such uniform
        int location = ((GlProgram) (Object) this).getUniformLocation(IPFlywheelPortalClip.UNIFORM);
        if (location >= 0) {
            GL20.glUniform4f(location, plane[0], plane[1], plane[2], plane[3]);
        }
    }

    /** True while this program's straddle-cut uniform holds the far-half draw's reject plane. */
    @Unique
    private boolean ip_shipClipRejecting;

    /**
     * World-level draws take the straddle cut uniform as their plane: in a far-half draw (the ship's own dimension's
     * engine drawn in the destination) they are rejected; afterwards the uniform is reset. Embedded draws override it
     * per environment.
     */
    @Inject(
        method = "bind",
        at = @At("TAIL"),
        require = 0,
        expect = 0
    )
    private void ip_setShipClipDefault(CallbackInfo ci) {
        boolean farHalf = IPFlywheelStraddleClip.isFarHalfPass();
        if (!farHalf && !ip_shipClipRejecting) {
            return;
        }
        ip_shipClipRejecting = farHalf;
        int location = ((GlProgram) (Object) this).getUniformLocation(IPFlywheelStraddleClip.UNIFORM);
        if (location >= 0) {
            if (farHalf) {
                GL20.glUniform4f(location, 0f, 0f, 0f, -1f);
            }
            else {
                GL20.glUniform4f(location, 0f, 0f, 0f, 1f);
            }
        }
    }
}
