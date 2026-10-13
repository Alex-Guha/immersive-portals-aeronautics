/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Follows IPSable 0.5.0's SableSourceClipMixin by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;

/**
 * Cuts a straddling ship at its portal plane while its terrain is drawn (gl_ClipDistance[1], next to IP's portal clip
 * on [0] handled by {@link MixinSableChunkedRenderData_ClipSpace}): on its own side the part before the plane is
 * kept, in a projection draw the part past the destination plane.
 */
@Mixin(value = VanillaChunkedSubLevelRenderData.class, remap = false)
public class MixinSableChunkedRenderData_StraddleClip {
    @Shadow
    @Final
    private ClientSubLevel subLevel;

    @Unique
    private boolean ip_clipEnabled;

    @Inject(method = "renderChunkedSubLevel", at = @At("HEAD"), require = 0)
    private void ip_beginStraddleClip(final RenderType layer, final ShaderInstance shader, final Matrix4f modelView,
                                      final double camX, final double camY, final double camZ, final CallbackInfo ci) {
        this.ip_clipEnabled = shader != null && IPStraddleRender.beginShipDraw(this.subLevel, shader, camX, camY, camZ);
    }

    @Inject(method = "renderChunkedSubLevel", at = @At("RETURN"), require = 0)
    private void ip_endStraddleClip(final RenderType layer, final ShaderInstance shader, final Matrix4f modelView,
                                    final double camX, final double camY, final double camZ, final CallbackInfo ci) {
        if (this.ip_clipEnabled) {
            this.ip_clipEnabled = false;
            IPStraddleRender.endShipDraw(shader);
        }
    }
}
