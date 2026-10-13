/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.mixin.client.render.shader;

import com.mojang.blaze3d.shaders.Shader;
import com.mojang.blaze3d.shaders.Uniform;
import net.minecraft.client.renderer.ShaderInstance;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.render.ShaderCodeTransformation;

import java.util.List;

@Mixin(ShaderInstance.class)
public abstract class MixinShaderInstance implements IEShader {
    @Shadow
    @Nullable
    public abstract Uniform getUniform(String name);
    
    @Shadow
    @Final
    private List<Uniform> uniforms;
    @Shadow
    @Final
    private String name;
    
    @Nullable
    private Uniform ip_clippingEquation;
    
    @Inject(
        method = "Lnet/minecraft/client/renderer/ShaderInstance;updateLocations()V",
        at = @At("HEAD")
    )
    private void onLoadReferences(CallbackInfo ci) {
        Shader this_ = (Shader) (Object) this;
        
        if (ShaderCodeTransformation.shouldAddUniform(name)) {
            ip_clippingEquation = new Uniform(
                "iportal_ClippingEquation",
                7, 4, this_
            );
            uniforms.add(ip_clippingEquation);
        }
        
        // Immersive Aeronautics: the per-ship straddle clip plane (gl_ClipDistance[1]) of the vanilla terrain shaders
        // that draw Sable ships; ShaderCodeTransformation declares it in their GLSL (shader_transformation.yaml)
        if (ShaderCodeTransformation.TERRAIN_SHADERS.contains(name)) {
            Uniform shipClip = new Uniform("ipa_ShipClipEquation", 7, 4, this_);
            shipClip.set(0.0f, 0.0f, 0.0f, 1.0f);
            uniforms.add(shipClip);
        }
    }
    
    @Nullable
    @Override
    public Uniform ip_getClippingEquationUniform() {
        return ip_clippingEquation;
    }
}
