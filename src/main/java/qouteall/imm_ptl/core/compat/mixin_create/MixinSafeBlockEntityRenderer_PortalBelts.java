/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.foundation.blockEntity.renderer.SafeBlockEntityRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalBeltsClient;

/**
 * A belt item drawn through a portal ({@link IPCreatePortalBeltsClient}) is never culled by Create's frustum test, which
 * would test its position in its own world against the drawn world's frustum.
 */
@Mixin(value = SafeBlockEntityRenderer.class, remap = false)
public abstract class MixinSafeBlockEntityRenderer_PortalBelts {
    @Inject(method = "shouldCullItem", at = @At("HEAD"), cancellable = true)
    private void ip$notThroughPortals(final Vec3 itemPos, final Level level, final CallbackInfoReturnable<Boolean> cir) {
        if (IPCreatePortalBeltsClient.isProjecting()) {
            cir.setReturnValue(false);
        }
    }
}
