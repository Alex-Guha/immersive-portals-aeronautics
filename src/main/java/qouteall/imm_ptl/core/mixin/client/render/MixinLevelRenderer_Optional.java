/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0), with changes from
 * Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code and ItsSoBread's changes stay under the
 * Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ViewArea;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;
import net.minecraft.world.entity.Entity;

// avoid crashing with sodium
// the overwrite has priority of 1000
@Mixin(value = LevelRenderer.class, priority = 1300)
public class MixinLevelRenderer_Optional {
    @Shadow
    private ViewArea viewArea;
    
    @Shadow
    @Final
    private Minecraft minecraft;
    
    //avoid translucent sort while rendering portal
    @Redirect(
        method = "renderSectionLayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/RenderType;translucent()Lnet/minecraft/client/renderer/RenderType;",
            ordinal = 0
        ),
        require = 0
    )
    private RenderType redirectGetTranslucent() {
        if (PortalRendering.isRendering()) {
            return null;
        }
        return RenderType.translucent();
    }
    
    //the camera position is used for translucent sort
    //avoid messing it
    @Redirect(
        method = "Lnet/minecraft/client/renderer/LevelRenderer;setupRender(Lnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;ZZ)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/chunk/SectionRenderDispatcher;setCamera(Lnet/minecraft/world/phys/Vec3;)V"
        ),
        require = 0
    )
    private void onSetChunkBuilderCameraPosition(
        SectionRenderDispatcher chunkBuilder, Vec3 cameraPosition
    ) {
        if (PortalRendering.isRendering()) {
            if (minecraft.level.dimension() == RenderStates.originalPlayerDimension) {
                return;
            }
        }
        chunkBuilder.setCamera(cameraPosition);
    }
    
    @Inject(
        method = "renderSectionLayer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/ShaderInstance;apply()V"
        ),
        require = 0
    )
    private void onGetShaderInRenderingLayer(
        RenderType renderType, double x, double y, double z, Matrix4f projectionMatrix, Matrix4f frustrumMatrix, CallbackInfo ci
    ) {
        FrontClipping.updateClippingEquationUniformForCurrentShader(false);
    }
    
    // correct the position of updating ViewArea
    @Redirect(
        method = "setupRender",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getX()D"),
        require = 0
    )
    private double redirectGetXInSetupRender(LocalPlayer player) {
        if (WorldRenderInfo.isRendering()) {
            return WorldRenderInfo.getCameraPos().x;
        }
        // Immersive Aeronautics: follow the camera entity (e.g. a seat on a ship), not the player
        Entity cameraEntity = this.minecraft.getCameraEntity();
        return cameraEntity != null ? cameraEntity.getX() : player.getX();
    }
    
    // biolerplate
    @Redirect(
        method = "setupRender",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getY()D"),
        require = 0
    )
    private double redirectGetYInSetupRender(LocalPlayer player) {
        if (WorldRenderInfo.isRendering()) {
            return WorldRenderInfo.getCameraPos().y;
        }
        // Immersive Aeronautics: follow the camera entity (e.g. a seat on a ship), not the player
        Entity cameraEntity = this.minecraft.getCameraEntity();
        return cameraEntity != null ? cameraEntity.getY() : player.getY();
    }
    
    // biolerplate
    @Redirect(
        method = "setupRender",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/LocalPlayer;getZ()D"),
        require = 0
    )
    private double redirectGetZInSetupRender(LocalPlayer player) {
        if (WorldRenderInfo.isRendering()) {
            return WorldRenderInfo.getCameraPos().z;
        }
        // Immersive Aeronautics: follow the camera entity (e.g. a seat on a ship), not the player
        Entity cameraEntity = this.minecraft.getCameraEntity();
        return cameraEntity != null ? cameraEntity.getZ() : player.getZ();
    }
}
