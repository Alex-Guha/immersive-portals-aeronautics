/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.entity.Carriage;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateRiderHandOver;

/**
 * Server: once Create has placed a carriage's copies for the tick, riders of a carriage crossing an IP portal change
 * copy as their head crosses the plane ({@link IPCreateRiderHandOver}); Create only re-seats them when its cut moves.
 */
@Mixin(value = Carriage.class, remap = false)
public class MixinCarriage_RiderHandOver {
    @Inject(method = "manageEntities", at = @At("TAIL"), require = 0, expect = 0)
    private void ip_handOverRidersAtPlane(final Level level, final CallbackInfo ci) {
        IPCreateRiderHandOver.tick((Carriage) (Object) this, level);
    }
}
