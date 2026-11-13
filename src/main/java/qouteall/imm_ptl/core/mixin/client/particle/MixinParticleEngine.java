/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.mixin.client.particle;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.mojang.blaze3d.vertex.VertexConsumer;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleEngine;
import net.minecraft.client.renderer.LightTexture;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ducks.IEParticleManager;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

@SuppressWarnings("resource")
@Mixin(ParticleEngine.class)
public class MixinParticleEngine implements IEParticleManager {
    @Shadow
    protected ClientLevel level;
    
    // skip particle rendering for far portals
    @Inject(
        method = "render",
        at = @At("HEAD"),
        cancellable = true
    )
    private void onBeginRenderParticles(
        LightTexture lightTexture, Camera camera, float f, CallbackInfo ci
    ) {
        if (PortalRendering.isRendering()) {
            if (RenderStates.getRenderedPortalNum() > 4) {
                ci.cancel();
            }
        }
    }
    
    // maybe incompatible with sodium and iris
    @WrapWithCondition(
        method = "render(Lnet/minecraft/client/renderer/LightTexture;Lnet/minecraft/client/Camera;FLnet/minecraft/client/renderer/culling/Frustum;Ljava/util/function/Predicate;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/particle/Particle;render(Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/minecraft/client/Camera;F)V"
        )
    )
    private boolean redirectBuildGeometry(
        Particle instance, VertexConsumer vertexConsumer, Camera camera, float v
    ) {
        return RenderStates.shouldRenderParticle(instance);
    }
    
    // a lava ember particle can generate a smoke particle during ticking
    // avoid generating the particle into the wrong dimension
    @Inject(method = "Lnet/minecraft/client/particle/ParticleEngine;tickParticle(Lnet/minecraft/client/particle/Particle;)V", at = @At("HEAD"), cancellable = true)
    private void onTickParticle(Particle particle, CallbackInfo ci) {
        if (((IEParticle) particle).portal_getWorld() != Minecraft.getInstance().level) {
            ci.cancel();
        }
    }
    
    @Override
    public void ip_setWorld(ClientLevel world_) {
        level = world_;
    }
    
    // Immersive Aeronautics: see IEParticleManager
    @Shadow
    @org.spongepowered.asm.mixin.Final
    private java.util.Map<net.minecraft.client.particle.ParticleRenderType, java.util.Queue<Particle>> particles;
    
    @Shadow
    private void tickParticleList(java.util.Collection<Particle> particles) {
    }
    
    @Override
    public void ip_tickParticleLists() {
        for (java.util.Queue<Particle> queue : particles.values()) {
            tickParticleList(queue);
        }
    }
    
    @Override
    public java.util.Map<net.minecraft.client.particle.ParticleRenderType, java.util.Queue<Particle>> ip_getParticles() {
        return particles;
    }
    
}
