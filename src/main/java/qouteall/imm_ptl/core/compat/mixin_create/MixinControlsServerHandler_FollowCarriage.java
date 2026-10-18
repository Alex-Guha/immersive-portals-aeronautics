/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.actors.trainControls.ControlsServerHandler;
import net.minecraft.world.level.LevelAccessor;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainControls;

/**
 * Server: a train driver who went through a portal keeps the controls on the carriage's copy in their new dimension
 * ({@link IPCreateTrainControls#beforeTick}) instead of Create stopping them.
 */
@Mixin(value = ControlsServerHandler.class, remap = false)
public class MixinControlsServerHandler_FollowCarriage {
    @Inject(method = "tick", at = @At("HEAD"), require = 0, expect = 0)
    private static void ip_followCarriage(final LevelAccessor world, final CallbackInfo ci) {
        IPCreateTrainControls.beforeTick(world);
    }
}
