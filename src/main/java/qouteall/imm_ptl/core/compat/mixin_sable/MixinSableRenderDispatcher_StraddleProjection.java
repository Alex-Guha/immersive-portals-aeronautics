/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The approach (repeating Sable's draw calls per projection) follows IPSable 0.5.0's IplHostedSubLevelRenderMixin
 * by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexConsumer;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRopes;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleSprings;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher.BlockEntityRenderer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.dispatcher.VanillaSubLevelRenderDispatcher;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaSingleSubLevelRenderData;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;

import java.util.List;
import java.util.SequencedSet;

/**
 * Draws the far halves of ships straddling into the dimension being rendered: Sable's dispatcher draws a level's own
 * ships for each terrain layer and for block entities (called on every pass, also for levels without ships); right
 * after, the same call is repeated for each projected ship, with its mapped pose and the destination plane
 * ({@link IPStraddleRender#forEachProjection}). Projections are only drawn with a shader that can cut them.
 */
@Mixin(value = VanillaSubLevelRenderDispatcher.class, remap = false)
public abstract class MixinSableRenderDispatcher_StraddleProjection {
    @Shadow
    @Final
    private SequencedSet<RenderType> singleBlockLayers;

    @Inject(method = "renderSectionLayer", at = @At("TAIL"), require = 0)
    private void ip_drawProjections(final Iterable<ClientSubLevel> sublevels, final RenderType renderType,
                                    final ShaderInstance shader, final double cameraX, final double cameraY,
                                    final double cameraZ, final Matrix4f modelView, final Matrix4f projection,
                                    final float partialTicks, final CallbackInfo ci) {
        if (IPStraddleRender.isProjecting()) {
            return;
        }
        final boolean canCut = shader.getUniform(IPStraddleRender.CLIP_UNIFORM) != null;
        final VanillaSubLevelRenderDispatcher self = (VanillaSubLevelRenderDispatcher) (Object) this;
        IPStraddleRender.forEachProjection(ship -> {
            // a single-block ship only notes the layer here, drawn and cut in renderAfterSections
            if (canCut || !(ship.getRenderData() instanceof VanillaChunkedSubLevelRenderData)) {
                self.renderSectionLayer(List.of(ship), renderType, shader, cameraX, cameraY, cameraZ, modelView, projection, partialTicks);
            }
        });
    }

    /**
     * The far halves of single-block ships, which Sable draws from here for the level's own ships. Each nested call
     * empties the layers, which the level's own ships still need.
     */
    @Inject(method = "renderAfterSections", at = @At("HEAD"), require = 0)
    private void ip_drawProjectedSingleBlocks(final Iterable<ClientSubLevel> sublevels, final double cameraX, final double cameraY,
                                              final double cameraZ, final Matrix4f modelView, final Matrix4f projection,
                                              final float partialTicks, final CallbackInfo ci) {
        if (IPStraddleRender.isProjecting() || this.singleBlockLayers.isEmpty()) {
            return;
        }
        final List<RenderType> layers = List.copyOf(this.singleBlockLayers);
        final VanillaSubLevelRenderDispatcher self = (VanillaSubLevelRenderDispatcher) (Object) this;
        IPStraddleRender.forEachProjection(ship -> {
            if (ship.getRenderData() instanceof VanillaSingleSubLevelRenderData) {
                self.renderAfterSections(List.of(ship), cameraX, cameraY, cameraZ, modelView, projection, partialTicks);
                this.singleBlockLayers.addAll(layers);
            }
        });
    }

    /**
     * Single-block ships at a portal ({@link IPStraddleRender#drawSingleBlock}). Here rather than in the render data:
     * Simulated's Contraption Diagrams call {@code renderSingleBlock} too, for ships that are not in the world.
     */
    @WrapOperation(method = "renderAfterSections", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/render/vanilla/VanillaSingleSubLevelRenderData;renderSingleBlock(Lnet/minecraft/client/renderer/RenderType;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lorg/joml/Matrix4f;DDD)V"),
            require = 0)
    private void ip_cutSingleBlock(final VanillaSingleSubLevelRenderData data, final RenderType layer, final VertexConsumer consumer,
                                   final Matrix4f modelView, final double cameraX, final double cameraY, final double cameraZ,
                                   final Operation<Void> original) {
        IPStraddleRender.drawSingleBlock(data.getSubLevel(), consumer, modelView, new Vec3(cameraX, cameraY, cameraZ),
                clipped -> original.call(data, layer, clipped, modelView, cameraX, cameraY, cameraZ));
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
