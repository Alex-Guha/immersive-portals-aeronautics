/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.kinetics.belt.transport.BeltMovementHandler;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Create stops carrying an entity on a belt (and zeroes its motion every tick) while any entity is just ahead of it.
 * An IP portal is an entity, so a rider was held a little before every portal a belt runs into, and walking on there
 * was as slow as through cobwebs. Portals never block.
 */
@Mixin(value = BeltMovementHandler.class, remap = false)
public abstract class MixinBeltMovementHandler_PortalRiders {
    @Inject(method = "shouldIgnoreBlocking", at = @At("HEAD"), cancellable = true)
    private static void ip$portalsNeverBlock(final Entity me, final Entity other, final CallbackInfoReturnable<Boolean> cir) {
        if (other instanceof Portal) {
            cir.setReturnValue(true);
        }
    }
}
