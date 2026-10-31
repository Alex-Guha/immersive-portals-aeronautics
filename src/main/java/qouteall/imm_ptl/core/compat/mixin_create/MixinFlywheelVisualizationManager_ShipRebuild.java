/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.world.level.LevelAccessor;
import net.neoforged.fml.ModList;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipRenderWatchdog;

/**
 * A new Flywheel manager for a level (the first, or after a reset: the backend switched on or off with a shader pack,
 * F3+A, IP reloading a world's renderer) starts without block entity visuals; Flywheel gets them back as sections
 * compile. Ship sections Flywheel took a part out of keep their compiled state, so the part was drawn by neither
 * until the ship was synced again (BUGS #8): the watchdog rebuilds that level's ships
 * ({@link IPShipRenderWatchdog#onFlywheelManagerCreated}). Constructed on whichever thread first asks for it.
 */
@Mixin(value = VisualizationManagerImpl.class, remap = false)
public class MixinFlywheelVisualizationManager_ShipRebuild {
    @Unique
    private static final boolean IP_SABLE = ModList.get().isLoaded("sable");

    @Inject(method = "<init>", at = @At("RETURN"), require = 0, expect = 0)
    private void ip_rebuildShips(final LevelAccessor level, final CallbackInfo ci) {
        if (IP_SABLE) {
            IPShipRenderWatchdog.onFlywheelManagerCreated(level);
        }
    }
}
