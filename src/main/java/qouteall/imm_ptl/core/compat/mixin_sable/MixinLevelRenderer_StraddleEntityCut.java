/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPPlaneClippingBufferSource;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;

/**
 * Entities placed on a straddling ship (Create contraptions, Simulated's Contraption Diagram, item frames, ...) are cut
 * at its portal plane like its blocks: not drawn when wholly past it, clipped when across it
 * ({@link IPStraddleRender#shipEntityCut}). The far half in the destination goes through here too.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_StraddleEntityCut {
    @WrapMethod(method = "renderEntity", require = 0)
    private void ip_cutShipEntity(final Entity entity, final double cameraX, final double cameraY, final double cameraZ,
                                  final float partialTick, final PoseStack poseStack, final MultiBufferSource buffers,
                                  final Operation<Void> original) {
        switch (IPStraddleRender.shipEntityCut(entity, partialTick)) {
            case SKIP -> {
            }
            case CLIP -> {
                final IPPlaneClippingBufferSource clipping =
                    IPStraddleRender.entityClippingSource(entity, cameraX, cameraY, cameraZ, poseStack, buffers);
                if (clipping == null) {
                    original.call(entity, cameraX, cameraY, cameraZ, partialTick, poseStack, buffers);
                    return;
                }
                try {
                    original.call(entity, cameraX, cameraY, cameraZ, partialTick, poseStack, clipping);
                } finally {
                    clipping.flush();
                }
            }
            default -> original.call(entity, cameraX, cameraY, cameraZ, partialTick, poseStack, buffers);
        }
    }
}
