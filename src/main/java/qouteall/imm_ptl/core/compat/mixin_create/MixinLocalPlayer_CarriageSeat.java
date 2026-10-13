/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateSeatedView;

/**
 * Client: a player seated on a carriage crossing a portal (the server's rider hand-over) is placed on the seat at once,
 * not at its next tick ({@link IPCreateSeatedView#onSeated}).
 */
@Mixin(LocalPlayer.class)
public class MixinLocalPlayer_CarriageSeat {
    @Inject(method = "startRiding", at = @At("RETURN"), require = 0, expect = 0)
    private void ip_placeOnCarriageSeat(final Entity vehicle, final boolean force, final CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() && vehicle instanceof final CarriageContraptionEntity carriage) {
            IPCreateSeatedView.onSeated((LocalPlayer) (Object) this, carriage);
        }
    }
}
