/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntityRenderer;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossingClient;

/** As for the block structure: no vanilla-drawn bogeys of a carriage Flywheel already draws. */
@Mixin(value = CarriageContraptionEntityRenderer.class, remap = false)
public class MixinCarriageContraptionEntityRenderer_NoVanillaBogeys {
    @WrapOperation(
        method = "lambda$render$1",
        at = @At(
            value = "INVOKE",
            target = "Ldev/engine_room/flywheel/api/visualization/VisualizationManager;supportsVisualization(Lnet/minecraft/world/level/LevelAccessor;)Z"
        ),
        require = 0,
        expect = 0
    )
    private static boolean ip_noVanillaBogeys(final LevelAccessor level, final Operation<Boolean> original,
                                              @Local(argsOnly = true) final CarriageContraptionEntity entity) {
        final boolean supported = original.call(level);
        return supported || IPCreateCarriageCrossingClient.skipsVanillaStructure(entity, "bogeys");
    }
}
