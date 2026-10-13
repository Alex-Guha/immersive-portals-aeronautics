/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.trains.bogey.BogeySizes;
import com.simibubi.create.content.trains.bogey.BogeyStyle;
import com.simibubi.create.content.trains.bogey.BogeyVisual;
import com.simibubi.create.content.trains.entity.CarriageContraption;
import com.simibubi.create.content.trains.entity.CarriageContraptionVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import net.minecraft.core.Vec3i;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossing;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossingClient;

/**
 * Create draws a carriage's bogeys with world-level Flywheel instances, outside the carriage's embedding, so no
 * per-embedding cut reaches them. They are created in an embedding of their own instead, with the identity transform
 * (the bogey instances are placed in world render space as before), which is cut with the carriage when it crosses a
 * portal ({@link IPCreateCarriageCrossingClient}).
 */
@Mixin(value = CarriageContraptionVisual.class, remap = false)
public class MixinCarriageContraptionVisual_BogeyEmbedding {
    @Shadow
    @Final
    private CarriageContraption contraption;

    @Unique
    @Nullable
    private VisualEmbedding ip_bogeyEmbedding;

    @WrapOperation(
        method = "checkCarriage",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/trains/bogey/BogeyStyle;createVisual(Lcom/simibubi/create/content/trains/bogey/BogeySizes$BogeySize;Ldev/engine_room/flywheel/api/visualization/VisualizationContext;FZ)Lcom/simibubi/create/content/trains/bogey/BogeyVisual;"
        ),
        require = 0,
        expect = 0
    )
    private BogeyVisual ip_bogeysInOwnEmbedding(final BogeyStyle style, final BogeySizes.BogeySize size,
                                                final VisualizationContext context, final float partialTick,
                                                final boolean inContraption, final Operation<BogeyVisual> original) {
        if (IPCreateCarriageCrossing.STOCK) {
            return original.call(style, size, context, partialTick, inContraption);
        }
        if (ip_bogeyEmbedding == null) {
            ip_bogeyEmbedding = context.createEmbedding(Vec3i.ZERO);
            IPCreateCarriageCrossingClient.registerEmbedding(ip_bogeyEmbedding, contraption.entity);
        }
        return original.call(style, size, ip_bogeyEmbedding, partialTick, inContraption);
    }

    @Inject(method = "_delete", at = @At("TAIL"), require = 0, expect = 0)
    private void ip_deleteBogeyEmbedding(final CallbackInfo ci) {
        if (ip_bogeyEmbedding != null) {
            ip_bogeyEmbedding.delete();
            ip_bogeyEmbedding = null;
        }
    }
}
