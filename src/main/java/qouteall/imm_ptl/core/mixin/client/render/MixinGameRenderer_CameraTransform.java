/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalBooleanRef;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Quaternionfc;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.render.TransformationManager;

/**
 * IP's camera transformation in {@code GameRenderer.renderLevel}: the camera's turn after crossing a rotated portal
 * and, in a portal pass, the portal's rotation, scale and mirroring ({@code WorldRenderInfo.applyAdditionalTransformations}).
 * Without it the world behind a rotated or scaled portal is drawn unrotated and unscaled, while crossing still turns
 * the player.
 * <p>
 * Other mods wrap the same {@code Matrix4f.rotation} call, and some build the matrix themselves without calling the
 * wrapped operation: Figura 0.1.6 does for a player with a scripted avatar, which dropped IP's wrapper when Figura's
 * was outermost (2.5.1). MixinExtras applies wrappers in mixin order and the last one applied is outermost, so the
 * raised priority puts this wrapper outside theirs, where it transforms the matrix they built. If a mod with a higher
 * priority still drops it, the fallback applies the transformation before the matrix is first used (frustum culling;
 * the same matrix then goes to {@code LevelRenderer.renderLevel}, {@code AFTER_LEVEL} and the hand).
 */
@Mixin(value = GameRenderer.class, priority = 1500)
public abstract class MixinGameRenderer_CameraTransform {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();
    @Unique
    private static boolean ip_reportedBypass = false;

    @Shadow
    @Final
    private Camera mainCamera;

    @WrapOperation(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lorg/joml/Matrix4f;rotation(Lorg/joml/Quaternionfc;)Lorg/joml/Matrix4f;",
            remap = false
        )
    )
    private Matrix4f ip_wrapCameraTransformation(
        Matrix4f instance, Quaternionfc quat, Operation<Matrix4f> original,
        @Share("ip_cameraTransformed") LocalBooleanRef transformed
    ) {
        Matrix4f r = original.call(instance, quat);
        transformed.set(true);
        return TransformationManager.processTransformation(this.mainCamera, r);
    }

    @WrapOperation(
        method = "renderLevel",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;prepareCullFrustum(Lnet/minecraft/world/phys/Vec3;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;)V"
        )
    )
    private void ip_cameraTransformationFallback(
        LevelRenderer instance, Vec3 cameraPos, Matrix4f modelView, Matrix4f projection, Operation<Void> original,
        @Share("ip_cameraTransformed") LocalBooleanRef transformed
    ) {
        if (!transformed.get()) {
            if (!ip_reportedBypass) {
                ip_reportedBypass = true;
                ip_LOGGER.warn("[IPCamera] another mod's wrapper of the camera rotation in GameRenderer.renderLevel "
                    + "skipped IP's; applying IP's camera transformation before frustum culling instead");
            }
            modelView.set(TransformationManager.processTransformation(this.mainCamera, modelView));
        }
        original.call(instance, cameraPos, modelView, projection);
    }
}
