/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.api.behaviour.movement.MovementBehaviour;
import com.simibubi.create.content.contraptions.behaviour.MovementContext;
import com.simibubi.create.content.contraptions.render.ContraptionEntityRenderer;
import com.simibubi.create.content.contraptions.render.ContraptionMatrices;
import com.simibubi.create.foundation.virtualWorld.VirtualRenderWorld;
import net.createmod.catnip.render.SuperByteBuffer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossingClient;

/**
 * Actors Create draws with its vanilla renderer (train controls, blaze burners) on a carriage crossing a portal: drawn
 * by the copy on whose side of the portal plane they are, and never by a copy that is not placed yet; it stood at
 * the server's spawn position, so the controls at a carriage's front showed ahead of it for a moment
 * ({@link IPCreateCarriageCrossingClient#drawsVanillaPart}).
 */
@Mixin(value = ContraptionEntityRenderer.class, remap = false)
public class MixinContraptionEntityRenderer_CarriageActors {
    /**
     * Create draws a contraption's whole block structure itself when Flywheel does not visualize the entity's level,
     * which Flywheel decides by {@code level == Minecraft.level}. With Flywheel running, a carriage reaching this path
     * is drawn in a pass of another level and is a duplicate of its Flywheel visual, drawn with Create's own cut
     * (purple glass) and none of the portal cuts. It is skipped and logged ({@link IPCreateCarriageCrossingClient#skipsVanillaStructure}).
     */
    @WrapOperation(
        method = "render(Lcom/simibubi/create/content/contraptions/AbstractContraptionEntity;FFLcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource;I)V",
        at = @At(
            value = "INVOKE",
            target = "Ldev/engine_room/flywheel/api/visualization/VisualizationManager;supportsVisualization(Lnet/minecraft/world/level/LevelAccessor;)Z"
        ),
        require = 0,
        expect = 0
    )
    private boolean ip_noVanillaCarriageStructure(final LevelAccessor level, final Operation<Boolean> original,
                                                  @Local(argsOnly = true) final AbstractContraptionEntity entity) {
        final boolean supported = original.call(level);
        return supported || IPCreateCarriageCrossingClient.skipsVanillaStructure(entity, "block structure");
    }

    /**
     * Without Flywheel (a shader pack) this builds the carriage's model. Unnoted, every client tick found it outdated
     * and threw it away ({@link IPCreateCarriageCrossingClient#rebuildIfOutdated}).
     */
    @Inject(method = "buildStructureBuffer", at = @At("HEAD"), require = 0)
    private static void ip_noteStructureBuilt(final CallbackInfoReturnable<SuperByteBuffer> cir,
                                              @Local(argsOnly = true) final Contraption contraption) {
        IPCreateCarriageCrossingClient.onStructureBuilt(contraption);
    }

    @WrapOperation(
        method = "renderActors",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/api/behaviour/movement/MovementBehaviour;renderInContraption(Lcom/simibubi/create/content/contraptions/behaviour/MovementContext;Lcom/simibubi/create/foundation/virtualWorld/VirtualRenderWorld;Lcom/simibubi/create/content/contraptions/render/ContraptionMatrices;Lnet/minecraft/client/renderer/MultiBufferSource;)V"
        ),
        require = 0,
        expect = 0
    )
    private static void ip_actorOnItsSide(final MovementBehaviour behaviour, final MovementContext context,
                                          final VirtualRenderWorld renderWorld, final ContraptionMatrices matrices,
                                          final MultiBufferSource buffer, final Operation<Void> original) {
        if (context.contraption == null || context.localPos == null
            || IPCreateCarriageCrossingClient.drawsVanillaPart(context.contraption.entity, context.localPos)) {
            original.call(behaviour, context, renderWorld, matrices, buffer);
        }
    }
}
