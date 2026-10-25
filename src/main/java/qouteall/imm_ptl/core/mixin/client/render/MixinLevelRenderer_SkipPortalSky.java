/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

/**
 * A portal view that draws no sky (fuse view: a dimension stack's floor and ceiling) draws none of any mod's either.
 * IP's HEAD injection in {@link MixinLevelRenderer} cancels {@code renderSky}, but other mods' HEAD injections may run
 * before it: Astral Sorcery draws the overworld's sky itself and cancels, and its below-horizon black half (tested
 * against the player's real eye height) covered everything below the horizon of a view into the overworld whenever
 * the player's eyes were below y 63 (from a void stacked above the overworld).
 * <p>
 * Covered: anything inside {@code renderSky} (injections, redirects, NeoForge's {@code DimensionSpecialEffects.renderSky},
 * Fabric API's sky renderers, which hook its HEAD) by the method wrapper, which is outside every injection; another
 * mod's wrapper of the whole method by the raised priority (MixinExtras applies wrappers in mixin order, the last
 * applied outermost); a mod that replaces the call in {@code renderLevel} (a redirect or wrapper of it) by the call
 * wrapper, which wraps those. Not covered: drawing from {@code RenderLevelStageEvent} AFTER_SKY, which other things
 * use too.
 */
@Mixin(value = LevelRenderer.class, priority = 2000)
public class MixinLevelRenderer_SkipPortalSky {
    @WrapMethod(method = "renderSky")
    private void ip_skipSkyInPortalView(
        Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick, Camera camera,
        boolean isFoggy, Runnable skyFogSetup, Operation<Void> original
    ) {
        if (ip_isSkylessPortalView()) {
            return;
        }
        original.call(frustumMatrix, projectionMatrix, partialTick, camera, isFoggy, skyFogSetup);
    }

    @WrapOperation(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;renderSky(Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;FLnet/minecraft/client/Camera;ZLjava/lang/Runnable;)V"
        )
    )
    private void ip_skipSkyCallInPortalView(
        LevelRenderer self, Matrix4f frustumMatrix, Matrix4f projectionMatrix, float partialTick, Camera camera,
        boolean isFoggy, Runnable skyFogSetup, Operation<Void> original
    ) {
        if (ip_isSkylessPortalView()) {
            return;
        }
        original.call(self, frustumMatrix, projectionMatrix, partialTick, camera, isFoggy, skyFogSetup);
    }

    private static boolean ip_isSkylessPortalView() {
        return WorldRenderInfo.isRendering() && !WorldRenderInfo.getTopRenderInfo().doRenderSky
            && !IrisInterface.invoker.isShaders();
    }
}
