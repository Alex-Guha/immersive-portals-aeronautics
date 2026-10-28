/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltRenderer;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.minecraft.client.renderer.MultiBufferSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalBeltLook;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalBeltsClient;

/**
 * A belt's items near a portal at an end are cut at its plane ({@link IPCreatePortalBeltsClient}); with Flywheel's
 * backend off, a belt end at a portal runs straight on into the portal block ({@link IPCreatePortalBeltLook}).
 */
@Mixin(value = BeltRenderer.class, remap = false)
public abstract class MixinBeltRenderer_PortalBelts {
    @ModifyVariable(method = "renderItems", at = @At("HEAD"), argsOnly = true)
    private MultiBufferSource ip$cutAtPortal(
        final MultiBufferSource buffer, @Local(argsOnly = true) final BeltBlockEntity be, @Local(argsOnly = true) final PoseStack ms
    ) {
        return IPCreatePortalBeltsClient.clipNear(be, ms, buffer);
    }

    @WrapOperation(
        method = "renderSafe",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/belt/BeltRenderer;getBeltPartial(ZZZZ)Ldev/engine_room/flywheel/lib/model/baked/PartialModel;"
        )
    )
    private PartialModel ip$straightAtPortal(
        final boolean diagonal, final boolean start, final boolean end, final boolean bottom, final Operation<PartialModel> original,
        @Local(argsOnly = true) final BeltBlockEntity be, @Local(argsOnly = true) final PoseStack ms,
        @Local(argsOnly = true) final MultiBufferSource buffer, @Local(argsOnly = true, ordinal = 0) final int light
    ) {
        final PartialModel straight = IPCreatePortalBeltLook.drawVanilla(be, bottom, ms, buffer, light);
        return straight != null ? straight : original.call(diagonal, start, end, bottom);
    }

    @Inject(method = "renderItems", at = @At("RETURN"))
    private void ip$flushCut(final CallbackInfo ci) {
        IPCreatePortalBeltsClient.afterRenderItems();
    }
}
