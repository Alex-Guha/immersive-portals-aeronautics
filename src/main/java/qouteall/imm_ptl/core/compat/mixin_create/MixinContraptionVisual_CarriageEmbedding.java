/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.render.ClientContraption;
import com.simibubi.create.content.contraptions.render.ContraptionVisual;
import dev.engine_room.flywheel.api.visual.DynamicVisual;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossingClient;

/**
 * Remembers which Flywheel embedding draws which carriage, so a carriage crossing a portal can be cut at the portal
 * plane ({@link IPCreateCarriageCrossingClient}). Also notes what a carriage's model is built for when it is actually
 * built ({@code setupStructure}: Create builds lazily, and a recreated visual builds at once), and every frame
 * rebuilds it if that differs from what it should show now (1.24.9: a model built with Create's cut at an unnoticed
 * moment stayed, purple glass and half the carriage hidden).
 */
@Mixin(value = ContraptionVisual.class, remap = false)
public class MixinContraptionVisual_CarriageEmbedding {
    @Shadow
    @Final
    protected VisualEmbedding embedding;

    @Unique
    private AbstractContraptionEntity ip_entity;

    @Inject(method = "<init>", at = @At("TAIL"), require = 0, expect = 0)
    private void ip_registerCarriageEmbedding(final VisualizationContext context, final AbstractContraptionEntity entity,
                                              final float partialTick, final CallbackInfo ci) {
        ip_entity = entity;
        IPCreateCarriageCrossingClient.registerEmbedding(embedding, entity);
    }

    @Inject(method = "setupStructure", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_noteStructureBuild(final ClientContraption clientContraption, final CallbackInfo ci) {
        IPCreateCarriageCrossingClient.onStructureBuilt(
            ((qouteall.imm_ptl.core.compat.mixin_create.IPClientContraptionAccessor) clientContraption).ip_getContraption());
    }

    @Inject(method = "beginFrame", at = @At("HEAD"), require = 0, expect = 0)
    private void ip_rebuildIfOutdated(final DynamicVisual.Context context, final CallbackInfo ci) {
        if (ip_entity != null) {
            qouteall.imm_ptl.core.compat.create_integration.IPCreateCrossingTrace.embedded(ip_entity, context.partialTick());
            IPCreateCarriageCrossingClient.rebuildIfOutdated(ip_entity);
        }
    }
}
