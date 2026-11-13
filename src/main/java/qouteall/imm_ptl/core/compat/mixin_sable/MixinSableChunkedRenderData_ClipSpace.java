/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import qouteall.imm_ptl.core.compat.sable_straddle.IPShipScale;
import com.mojang.blaze3d.shaders.Uniform;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalClip;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.render.FrontClipping;

/**
 * Keeps IP's front clipping working on Sable ships rendered with the vanilla renderer.
 * Around {@code VanillaChunkedSubLevelRenderData.renderChunkedSubLevel}, rotates the active
 * clip-plane normal by the inverse of the sub-level's render orientation and uploads it to
 * the shader's {@code iportal_ClippingEquation} uniform, restoring the original plane at TAIL.
 * Note: only the normal is rotated; the D term is not adjusted for the ship's translation.
 * In a portal pass through a portal whose far side this ship carries, {@link IPShipPortalClip} may give the plane
 * without IP's 1 cm band instead.
 */
@Mixin(
    targets = "dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData",
    remap = false
)
public class MixinSableChunkedRenderData_ClipSpace {
    @Shadow
    private ClientSubLevel subLevel;

    @Unique
    private double[] ip_savedClipEquation;
    @Unique
    private Uniform ip_savedUniform;

    @Inject(
        method = "renderChunkedSubLevel",
        at = @At("HEAD"),
        require = 0
    )
    private void ip_rotateClipPlaneIntoSubLevelSpace(
        RenderType layer, ShaderInstance shader, Matrix4f modelView,
        double camX, double camY, double camZ, CallbackInfo ci
    ) {
        this.ip_savedClipEquation = null;
        this.ip_savedUniform = null;

        try {
            if (!FrontClipping.isClippingEnabled) {
                return;
            }

            if (shader == null || this.subLevel == null) {
                return;
            }

            // IP's own handle: an Iris shader pack's getUniform looks the name up with an "iris_" prefix
            Uniform clipUniform = ((IEShader) shader).ip_getClippingEquationUniform();
            if (clipUniform == null) {
                return;
            }

            double[] clipEquation = FrontClipping.getActiveClipPlaneEquationBeforeModelView();
            if (clipEquation == null || clipEquation.length < 4) {
                return;
            }

            this.ip_savedClipEquation = clipEquation;
            this.ip_savedUniform = clipUniform;
            // the carrier of the far portal side: exactly at the plane, without IP's 1 cm band
            double[] carrierEquation = IPShipPortalClip.equationFor(this.subLevel);
            if (carrierEquation != null) {
                clipUniform.set(
                    (float) carrierEquation[0], (float) carrierEquation[1], (float) carrierEquation[2],
                    (float) carrierEquation[3]
                );
                clipUniform.upload();
                return;
            }
            Vector3d rotatedNormal = IPShipScale.clipNormal(
                this.subLevel.renderPose(), clipEquation[0], clipEquation[1], clipEquation[2]
            );
            clipUniform.set(
                (float) rotatedNormal.x, (float) rotatedNormal.y, (float) rotatedNormal.z,
                (float) clipEquation[3]
            );
            clipUniform.upload();
        } catch (Throwable t) {
            this.ip_savedClipEquation = null;
            this.ip_savedUniform = null;
        }
    }

    @Inject(
        method = "renderChunkedSubLevel",
        at = @At("TAIL"),
        require = 0
    )
    private void ip_restoreClipPlane(
        RenderType layer, ShaderInstance shader, Matrix4f modelView,
        double camX, double camY, double camZ, CallbackInfo ci
    ) {
        try {
            if (this.ip_savedUniform != null && this.ip_savedClipEquation != null) {
                this.ip_savedUniform.set(
                    (float) this.ip_savedClipEquation[0],
                    (float) this.ip_savedClipEquation[1],
                    (float) this.ip_savedClipEquation[2],
                    (float) this.ip_savedClipEquation[3]
                );
                this.ip_savedUniform.upload();
            }
        } catch (Throwable ignored) {
        } finally {
            this.ip_savedClipEquation = null;
            this.ip_savedUniform = null;
        }
    }
}
