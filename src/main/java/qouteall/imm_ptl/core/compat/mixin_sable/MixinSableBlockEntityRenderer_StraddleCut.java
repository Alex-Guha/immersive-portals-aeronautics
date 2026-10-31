/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The approach (cutting ship block entities at the plane in renderSingleBE) follows IPSable 0.5.0's
 * SableVanillaSubLevelBERMixin by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderDispatcher;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPPlaneClippingBufferSource;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleClientSessions;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRopes;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleSprings;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Block entities of a straddling ship are cut at the portal plane: they are batched and drawn after the ship's terrain,
 * so they cannot share its per-ship clip plane. One well past the cut is skipped, one near it is drawn through a buffer
 * source that clips its quads and lines at the cut ({@link IPStraddleRender#blockEntityCut}). Rope connectors are cut
 * per rope segment ({@link IPStraddleRopes}), a spring through a portal along its whole length ({@link IPStraddleSprings}).
 * In IP portal passes, any ship's block entity whose centre is on the
 * clipped side of IP's portal plane is skipped too (some draw at once with their own shader); one drawing far beyond its
 * block (Shield Generators' field) is placed by its render box and clipped at IP's plane on the CPU when it crosses it
 * ({@link IPStraddleRender#portalPassCut}).
 */
@Mixin(value = VanillaSubLevelBlockEntityRenderer.class, remap = false)
public class MixinSableBlockEntityRenderer_StraddleCut {
    @Inject(method = "renderSingleBE", at = @At("HEAD"), cancellable = true, require = 0)
    private void ip_cutAtPortal(final BlockEntity blockEntity, final PoseStack poseStack, final float partialTick,
                                final double cameraX, final double cameraY, final double cameraZ, final CallbackInfo ci) {
        if (blockEntity.getLevel() == null || IPStraddleRopes.isRopeHolder(blockEntity)) {
            return;
        }
        final IPStraddleClientSessions.Session spring = IPStraddleSprings.crossingSession(blockEntity);
        if (spring != null) {
            if (IPStraddleSprings.hides(blockEntity, spring)) {
                ci.cancel();
            }
            return;
        }
        if (IPStraddleRender.blockEntityCut(blockEntity) == IPStraddleRender.BlockEntityCut.SKIP) {
            ci.cancel();
            return;
        }
        if (PortalRendering.isRendering()) {
            final SubLevel containing = Sable.HELPER.getContaining(blockEntity);
            if (containing != null && IPStraddleRender.portalPassCut(containing, blockEntity) == IPStraddleRender.BlockEntityCut.SKIP) {
                ci.cancel();
            }
        }
    }

    @WrapOperation(method = "renderSingleBE", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/blockentity/BlockEntityRenderDispatcher;render(Lnet/minecraft/world/level/block/entity/BlockEntity;FLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;)V"),
            require = 0)
    private void ip_clipAtPortal(final BlockEntityRenderDispatcher dispatcher, final BlockEntity blockEntity, final float partialTick,
                                 final PoseStack poseStack, final MultiBufferSource source, final Operation<Void> original) {
        if (IPStraddleRopes.isRopeHolder(blockEntity)) {
            original.call(dispatcher, blockEntity, partialTick, poseStack, source);
            return;
        }
        final IPStraddleClientSessions.Session spring = IPStraddleSprings.crossingSession(blockEntity);
        final boolean atCut = spring == null && IPStraddleRender.blockEntityCut(blockEntity) == IPStraddleRender.BlockEntityCut.CLIP;
        boolean atPortal = false;
        if (PortalRendering.isRendering()) {
            final SubLevel containing = Sable.HELPER.getContaining(blockEntity);
            atPortal = containing != null && IPStraddleRender.portalPassCut(containing, blockEntity) == IPStraddleRender.BlockEntityCut.CLIP;
        }
        if (spring == null && !atCut && !atPortal) {
            original.call(dispatcher, blockEntity, partialTick, poseStack, source);
            return;
        }
        final MultiBufferSource clipping = spring != null ? IPStraddleSprings.clippingSource(blockEntity, spring, poseStack, source)
                : IPStraddleRender.clippingSource(blockEntity, poseStack, source, atCut, atPortal);
        try {
            original.call(dispatcher, blockEntity, partialTick, poseStack, clipping);
        } finally {
            if (clipping instanceof final IPPlaneClippingBufferSource buffers) {
                buffers.flush();
            }
        }
    }
}
