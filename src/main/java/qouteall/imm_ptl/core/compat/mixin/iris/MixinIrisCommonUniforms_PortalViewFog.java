/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.iris;

import net.irisshaders.iris.compat.dh.DHCompat;
import net.irisshaders.iris.gl.uniform.UniformHolder;
import net.irisshaders.iris.gl.uniform.UniformUpdateFrequency;
import net.irisshaders.iris.shaderpack.IdMap;
import net.irisshaders.iris.shaderpack.properties.PackDirectives;
import net.irisshaders.iris.uniforms.CommonUniforms;
import net.irisshaders.iris.uniforms.FrameUpdateNotifier;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.IPPortalViewDistance;

import java.util.function.DoubleSupplier;

/**
 * Portal view distance ({@link IPPortalViewDistance}): the band Complementary's patched border fog runs over in a
 * portal pass ({@code MixinIrisTransformPatcher_PortalViewFog}). Per-frame uniforms, refreshed per cut like
 * {@code far} ({@code MixinIrisProgramUniforms_PortalViewDistance}).
 */
@Mixin(value = CommonUniforms.class, remap = false)
public class MixinIrisCommonUniforms_PortalViewFog {
    @Inject(method = "addNonDynamicUniforms", at = @At("RETURN"))
    private static void ip_addPortalFogUniforms(
        UniformHolder uniforms, IdMap idMap, PackDirectives directives, FrameUpdateNotifier updateNotifier,
        CallbackInfo ci
    ) {
        uniforms.uniform1f(
            UniformUpdateFrequency.PER_FRAME, IPPortalViewDistance.PACK_FOG_START_UNIFORM,
            (DoubleSupplier) () -> IPPortalViewDistance.packFogStart(ip_packFogDistance())
        );
        uniforms.uniform1f(
            UniformUpdateFrequency.PER_FRAME, IPPortalViewDistance.PACK_FOG_END_UNIFORM,
            (DoubleSupplier) () -> IPPortalViewDistance.packFogEnd(ip_packFogDistance())
        );
    }

    /** Complementary's {@code renderDistance} in this pass: {@code dhRenderDistance} with DH, else {@code far}. */
    @Unique
    private static double ip_packFogDistance() {
        if (DHCompat.hasRenderingEnabled()) {
            return DHCompat.getRenderDistance();
        }
        return IPPortalViewDistance.capRenderDistanceBlocks(
            Minecraft.getInstance().options.getEffectiveRenderDistance() * 16
        );
    }
}
