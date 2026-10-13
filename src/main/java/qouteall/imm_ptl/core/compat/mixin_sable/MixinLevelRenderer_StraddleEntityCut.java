/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;

/**
 * Entities placed on a straddling ship (Simulated's Contraption Diagram, item frames, ...) are not drawn past its
 * portal plane, like its blocks ({@link IPStraddleRender#cutsShipEntity}).
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_StraddleEntityCut {
    @Inject(method = "renderEntity", at = @At("HEAD"), cancellable = true, require = 0)
    private void ip_cutShipEntity(final Entity entity, final double cameraX, final double cameraY, final double cameraZ,
                                  final float partialTick, final PoseStack poseStack, final MultiBufferSource buffers,
                                  final CallbackInfo ci) {
        if (IPStraddleRender.cutsShipEntity(entity, partialTick)) {
            ci.cancel();
        }
    }
}
