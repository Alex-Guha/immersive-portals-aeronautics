/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.simulated_team.simulated.content.blocks.lasers.AbstractLaserBlockEntity;
import dev.simulated_team.simulated.content.blocks.lasers.AbstractLaserRenderer;
import dev.simulated_team.simulated.content.blocks.lasers.LaserBehaviour;
import net.minecraft.client.renderer.MultiBufferSource;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPLaserPortalsClient;

/**
 * A laser beam through a portal, in its block's own world: drawn whole (its length is the path length) and cut at the
 * first portal; the rest is drawn in the worlds past the portals ({@link IPLaserPortalsClient}). In IP's portal passes
 * the renderer's whole output is cut at the portal plane, which IP's own clip does not reach in Simulated's shaders.
 */
@Mixin(value = AbstractLaserRenderer.class, remap = false)
public abstract class MixinAbstractLaserRenderer_Portals {
    @WrapMethod(
        method = "renderSafe(Ldev/simulated_team/simulated/content/blocks/lasers/AbstractLaserBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        require = 0
    )
    private void ip_clipInPortalPass(final AbstractLaserBlockEntity blockEntity, final float partialTicks, final PoseStack pose,
                                     final MultiBufferSource buffer, final int light, final int overlay, final Operation<Void> original) {
        IPLaserPortalsClient.renderClippedInPortalPass(blockEntity, pose, buffer,
                clipped -> original.call(blockEntity, partialTicks, pose, clipped, light, overlay));
    }

    @Inject(
        method = "renderSafe(Ldev/simulated_team/simulated/content/blocks/lasers/AbstractLaserBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At("HEAD"),
        require = 0
    )
    private void ip_blockEntityPose(final AbstractLaserBlockEntity blockEntity, final float partialTicks, final PoseStack pose,
                                    final MultiBufferSource buffer, final int light, final int overlay, final CallbackInfo ci) {
        IPLaserPortalsClient.beforeRender(pose);
    }

    @WrapOperation(
        method = "renderSafe(Ldev/simulated_team/simulated/content/blocks/lasers/AbstractLaserBlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;II)V",
        at = @At(value = "INVOKE", target = "Ldev/simulated_team/simulated/content/blocks/lasers/AbstractLaserRenderer;createLaser(Lorg/joml/Vector4f;Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;FF)V"),
        require = 0
    )
    private void ip_cutAtPortal(final AbstractLaserRenderer<?> renderer, final Vector4f colors, final PoseStack pose,
                                final MultiBufferSource buffer, final float maxLength, final float length, final Operation<Void> original,
                                @Local(argsOnly = true) final AbstractLaserBlockEntity blockEntity, @Local final LaserBehaviour laser) {
        final MultiBufferSource clipped = IPLaserPortalsClient.clipToFirstLeg(blockEntity, laser, buffer);
        original.call(renderer, colors, pose, clipped, maxLength, length);
        IPLaserPortalsClient.flush(clipped);
    }
}
