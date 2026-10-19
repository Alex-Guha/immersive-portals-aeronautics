/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.fluids.FluidTransportBehaviour;
import com.simibubi.create.content.fluids.FluidTransportBehaviour.AttachmentTypes;
import com.simibubi.create.content.fluids.PipeAttachmentModel;
import java.util.List;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.block.model.BakedQuad;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalPipeModel;

/** Pipe ends at a portal block: no rim, and a filler up to the portal plane ({@link IPCreatePortalPipeModel}). */
@Mixin(value = PipeAttachmentModel.class, remap = false)
public abstract class MixinPipeAttachmentModel_PortalPipes {
    @WrapOperation(
        method = "gatherModelData",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/fluids/FluidTransportBehaviour;getRenderedRimAttachment(Lnet/minecraft/world/level/BlockAndTintGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/Direction;)Lcom/simibubi/create/content/fluids/FluidTransportBehaviour$AttachmentTypes;"
        )
    )
    private AttachmentTypes ip$portalAttachment(
        FluidTransportBehaviour transport, BlockAndTintGetter world, BlockPos pos, BlockState state, Direction side,
        Operation<AttachmentTypes> original
    ) {
        return IPCreatePortalPipeModel.attachment(original.call(transport, world, pos, state, side), transport, world, pos, state, side);
    }

    @Inject(method = "gatherModelData", at = @At("RETURN"))
    private void ip$portalFaces(
        ModelData.Builder builder, BlockAndTintGetter world, BlockPos pos, BlockState state, ModelData blockEntityData,
        CallbackInfoReturnable<ModelData.Builder> cir
    ) {
        IPCreatePortalPipeModel.addPortalFaces(cir.getReturnValue(), world, pos, state);
    }

    @Inject(method = "getQuads", at = @At("RETURN"), cancellable = true)
    private void ip$portalFillers(
        BlockState state, Direction side, RandomSource rand, ModelData data, RenderType renderType,
        CallbackInfoReturnable<List<BakedQuad>> cir
    ) {
        List<BakedQuad> quads = IPCreatePortalPipeModel.withFillers(cir.getReturnValue(), state, side, rand, data, renderType);
        if (quads != null) {
            cir.setReturnValue(quads);
        }
    }
}
