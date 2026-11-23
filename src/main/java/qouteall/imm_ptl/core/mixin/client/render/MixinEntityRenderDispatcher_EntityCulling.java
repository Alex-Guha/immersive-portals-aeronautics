/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.client.renderer.entity.EntityRenderDispatcher;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.IPEntityCullingCompat;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * An entity in view in a portal pass is kept visible for EntityCulling ({@link IPEntityCullingCompat}), before
 * {@code LevelRenderer.renderEntity} where EntityCulling skips what it hid from the player's camera. Not in a shader
 * pack's shadow pass, which asks the same for everything between the view and the sun: marking those kept them from
 * EntityCulling everywhere, the player's own view included.
 */
@Mixin(EntityRenderDispatcher.class)
public class MixinEntityRenderDispatcher_EntityCulling {
    @Inject(method = "shouldRender", at = @At("RETURN"))
    private void ip_keepPortalViewEntityVisible(
        Entity entity, Frustum frustum, double camX, double camY, double camZ, CallbackInfoReturnable<Boolean> cir
    ) {
        if (cir.getReturnValueZ() && PortalRendering.isRendering()
            && !IrisInterface.invoker.isRenderingShadowMap()) {
            IPEntityCullingCompat.onEntityInPortalView(entity);
        }
    }
}
