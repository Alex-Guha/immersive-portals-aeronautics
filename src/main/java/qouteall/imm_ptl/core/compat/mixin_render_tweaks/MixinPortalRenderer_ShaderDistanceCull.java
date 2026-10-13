/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import java.util.function.Supplier;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.iris_integration.IPIrisCompat;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.renderer.PortalRenderer;

/**
 * Render tweak: when an Iris shader pack is active, skips rendering portals whose nearest
 * point is farther than 100 blocks from the camera
 * (portal passes are expensive with shaders). Injected at the head of
 * {@code PortalRenderer.shouldSkipRenderingPortal}.
 */
@Mixin(
    value = PortalRenderer.class,
    remap = false
)
public class MixinPortalRenderer_ShaderDistanceCull {
    @Unique
    private static final double ip_SHADER_PORTAL_RENDER_DISTANCE = 100.0;

    @Inject(
        method = "shouldSkipRenderingPortal",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void ip_hidePortalBeyondDistanceWithShaders(
        Portal portal, Supplier<Frustum> frustumSupplier, CallbackInfoReturnable<Boolean> cir
    ) {
        try {
            if (!IPIrisCompat.isShaderPackInUse()) {
                return;
            }

            Vec3 cameraPos = CHelper.getCurrentCameraPos();
            if (cameraPos == null) {
                return;
            }

            if (portal.getDistanceToNearestPointInPortal(cameraPos) > ip_SHADER_PORTAL_RENDER_DISTANCE) {
                cir.setReturnValue(true);
            }
        } catch (Throwable ignored) {
        }
    }
}
