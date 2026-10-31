/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.engine_room.flywheel.lib.util.LevelAttached;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPFlywheelSectionWorld;

/**
 * While a section compile decides whether a block entity becomes a visual ({@link IPFlywheelSectionWorld}), a client
 * world counts as supported when it already has a Flywheel manager (it was drawn with the backend on since the last
 * reset), whichever world {@code Minecraft.level} names at that moment. A world without one yet stays as Flywheel says
 * (no manager is made from a worker thread); its ships are rebuilt once its manager is made
 * ({@code MixinFlywheelVisualizationManager_ShipRebuild}).
 */
@Mixin(value = VisualizationManagerImpl.class, remap = false)
public class MixinFlywheelVisualizationManager_SectionWorld {
    @Shadow
    @Final
    private static LevelAttached<VisualizationManagerImpl> MANAGERS;

    @Inject(method = "supportsVisualization", at = @At("RETURN"), cancellable = true, require = 0, expect = 0)
    private static void ip_sectionWorld(LevelAccessor level, CallbackInfoReturnable<Boolean> cir) {
        if (!cir.getReturnValueZ()
            && IPFlywheelSectionWorld.isDeciding()
            && level instanceof ClientLevel
            && ((IPLevelAttachedAccessor) (Object) MANAGERS).ip_getCache().getIfPresent(level) != null
        ) {
            cir.setReturnValue(true);
        }
    }
}
