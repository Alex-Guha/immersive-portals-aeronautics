/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Follows IPSable 0.5.0's IplStraddleOutlineMixin by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddlePick;

/**
 * The outline of a targeted block on the far half of a straddling ship, drawn where the far half is
 * ({@link IPStraddlePick#renderOutline}), right after vanilla's own hit outline.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_StraddleOutline {
    @Shadow
    @Nullable
    private ClientLevel level;

    @Inject(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/debug/DebugRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD)V"),
        require = 0
    )
    private void ip_farHalfOutline(final DeltaTracker deltaTracker, final boolean renderBlockOutline, final Camera camera,
                                   final GameRenderer gameRenderer, final LightTexture lightTexture, final Matrix4f frustumMatrix,
                                   final Matrix4f projectionMatrix, final CallbackInfo ci) {
        if (renderBlockOutline) {
            IPStraddlePick.renderOutline(this.level, camera);
        }
    }
}
