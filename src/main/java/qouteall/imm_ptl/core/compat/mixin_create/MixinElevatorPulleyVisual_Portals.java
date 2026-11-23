/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyVisual;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPElevatorPortalsClient;

/**
 * An elevator pulley whose shaft runs through a portal: its belt ends at the portal plane and the magnet on top of the
 * car is hidden once past it (the far side draws them), instead of reaching into whatever lies behind the portal.
 */
@Mixin(value = ElevatorPulleyVisual.class, remap = false)
public class MixinElevatorPulleyVisual_Portals {
    @Shadow
    @Final
    private TransformedInstance magnet;

    @Unique
    private ElevatorPulleyBlockEntity ip$pulley() {
        return (ElevatorPulleyBlockEntity) ((IPBlockEntityVisualAccessor) this).ip_getBlockEntity();
    }

    @ModifyArg(method = "animate", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/contraptions/elevator/ElevatorPulleyVisual;animateBelt(F)V"))
    private float ip$beltToPlane(float offset) {
        return IPElevatorPortalsClient.beltOffset(ip$pulley(), offset);
    }

    @ModifyArg(method = "animate", at = @At(value = "INVOKE", target = "Lcom/simibubi/create/content/contraptions/elevator/ElevatorPulleyVisual;animateHalfBelt(F)V"))
    private float ip$halfBeltToPlane(float offset) {
        return IPElevatorPortalsClient.beltOffset(ip$pulley(), offset);
    }

    @Inject(method = "animateMagnet", at = @At("TAIL"))
    private void ip$hideMagnetPastPlane(float offset, CallbackInfo ci) {
        magnet.setVisible(!IPElevatorPortalsClient.magnetPastPlane(ip$pulley(), offset));
    }
}
