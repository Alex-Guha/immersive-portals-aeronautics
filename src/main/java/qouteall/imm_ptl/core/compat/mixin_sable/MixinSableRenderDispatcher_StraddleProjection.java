/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The approach (repeating Sable's draw calls per projection) follows IPSable 0.5.0's IplHostedSubLevelRenderMixin
 * by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRopes;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleSprings;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher.BlockEntityRenderer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;

import java.util.List;

/**
 * Draws the far halves of ships straddling into the dimension being rendered: Sable's dispatcher draws a level's own
 * ships for each terrain layer and for block entities (called on every pass, also for levels without ships); right
 * after, the same call is repeated for each projected ship, with its mapped pose and the destination plane
 * ({@link IPStraddleRender#forEachProjection}). Projections are only drawn with a shader that can cut them.
 */
@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
public abstract class MixinSableRenderDispatcher_StraddleProjection {
    @Inject(method = "renderSectionLayer", at = @At("TAIL"), require = 0)
    private void ip_drawProjections(final Iterable<ClientSubLevel> sublevels, final RenderType renderType,
                                    final ShaderInstance shader, final double cameraX, final double cameraY,
                                    final double cameraZ, final Matrix4f modelView, final Matrix4f projection,
                                    final float partialTicks, final CallbackInfo ci) {
        if (IPStraddleRender.isProjecting() || shader.getUniform(IPStraddleRender.CLIP_UNIFORM) == null) {
            return;
        }
        final VanillaSubLevelRenderDispatcher self = (VanillaSubLevelRenderDispatcher) (Object) this;
        IPStraddleRender.forEachProjection(ship -> {
            // single-block ships are drawn in a later pass that has no clip plane; not projected
            if (ship.getRenderData() instanceof VanillaChunkedSubLevelRenderData) {
                self.renderSectionLayer(List.of(ship), renderType, shader, cameraX, cameraY, cameraZ, modelView, projection, partialTicks);
            }
        });
    }

    @Inject(method = "renderBlockEntities", at = @At("TAIL"), require = 0)
    private void ip_drawProjectedBlockEntities(final Iterable<ClientSubLevel> sublevels, final BlockEntityRenderer blockEntityRenderer,
                                               final double cameraX, final double cameraY, final double cameraZ,
                                               final float partialTick, final CallbackInfo ci) {
        if (IPStraddleRender.isProjecting()) {
            return;
        }
        final VanillaSubLevelRenderDispatcher self = (VanillaSubLevelRenderDispatcher) (Object) this;
        IPStraddleRender.forEachProjection(ship -> {
            self.renderBlockEntities(List.of(ship), blockEntityRenderer, cameraX, cameraY, cameraZ, partialTick);
            // global block entities (ropes) are not in the sections' lists; Sable draws them in the ship's own world only
            IPStraddleRopes.drawProjectedGlobalBlockEntities(ship, blockEntityRenderer, cameraX, cameraY, cameraZ, partialTick);
        });
        IPStraddleSprings.drawFarParts(blockEntityRenderer, cameraX, cameraY, cameraZ, partialTick);
    }
}
