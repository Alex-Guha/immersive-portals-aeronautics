/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.actors.trainControls.ControlsHandler;
import java.lang.ref.WeakReference;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateTrainControlsClient;

/**
 * Client: a train driver who went through a portal keeps the controls, on the carriage's copy in their new dimension
 * ({@link IPCreateTrainControlsClient#beforeTick}).
 */
@Mixin(value = ControlsHandler.class, remap = false)
public class MixinControlsHandler_FollowCarriage {
    @Shadow
    private static WeakReference<AbstractContraptionEntity> entityRef;
    @Shadow
    private static int packetCooldown;
    @Shadow
    private static BlockPos controlsPos;

    @Inject(method = "stopControlling", at = @At("HEAD"), require = 0, expect = 0)
    private static void ip_logStop(final CallbackInfo ci) {
        IPCreateTrainControlsClient.onStop(entityRef.get(), controlsPos);
    }

    @Inject(method = "tick", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private static void ip_followCarriage(final CallbackInfo ci) {
        final IPCreateTrainControlsClient.Action action = IPCreateTrainControlsClient.beforeTick(entityRef.get(), copy -> {
            entityRef = new WeakReference<>(copy);
            packetCooldown = 0;
        });
        if (action == IPCreateTrainControlsClient.Action.SKIP) {
            ci.cancel();
        }
    }
}
