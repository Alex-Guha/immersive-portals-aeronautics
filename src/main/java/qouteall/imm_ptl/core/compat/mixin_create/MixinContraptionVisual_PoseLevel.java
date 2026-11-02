/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPSablePlotPoses;

/**
 * Says which level's ship poses a contraption visual is about to read ({@link IPSablePlotPoses}). Before Sable's own
 * head of {@code setEmbeddingMatrices}, which reads them and cancels. {@code beginFrame} covers the carriage's
 * {@code animate}, which runs right after it on the same thread.
 */
@Mixin(value = ContraptionVisual.class, remap = false, priority = 900)
public abstract class MixinContraptionVisual_PoseLevel extends AbstractEntityVisual<AbstractContraptionEntity> {
    public MixinContraptionVisual_PoseLevel(final VisualizationContext context, final AbstractContraptionEntity entity, final float partialTick) {
        super(context, entity, partialTick);
    }

    @Inject(method = "setEmbeddingMatrices", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_noteLevel(final float partialTick, final CallbackInfo ci) {
        IPSablePlotPoses.enterVisual(this.entity.level());
    }

    @Inject(method = "beginFrame", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_noteLevelForFrame(final dev.engine_room.flywheel.api.visual.DynamicVisual.Context context, final CallbackInfo ci) {
        IPSablePlotPoses.enterVisual(this.entity.level());
    }
}
