/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.mojang.blaze3d.vertex.MeshData;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPLaserIrisPortals;

/**
 * Create Aeronautics' laser beam with a shader pack: its geometry is recorded for IP's portal rendering
 * ({@link IPLaserIrisPortals}); the draw itself is left as it is.
 */
@Mixin(RenderType.class)
public abstract class MixinRenderType_LaserIrisPortals {
    @Inject(method = "draw", at = @At("HEAD"))
    private void ip$recordLaser(MeshData meshData, CallbackInfo ci) {
        if (IPLaserIrisPortals.isLaserType((RenderType) (Object) this)) {
            IPLaserIrisPortals.beforeDraw(meshData);
        }
    }
}
