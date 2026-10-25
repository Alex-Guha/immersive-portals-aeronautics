/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.compat.voxy_integration.IPVoxyPortalView;

/**
 * Voxy makes a level renderer's render system at the end of {@code allChanged} (IP's level renderers of other
 * dimensions included, when IP loads that dimension, and nested in the player's renderer's, which reloads the
 * others); the level is named for the Iris pipeline it binds to ({@code IPVoxyIrisPipelines}). The whole method is
 * wrapped: Roxy moves Voxy's injection to {@code order = 1100}, after a default-order RETURN injection, and IP's own
 * HEAD injection may cancel the method during portal rendering.
 */
@Mixin(LevelRenderer.class)
public class MixinLevelRenderer_VoxyCreationLevel {
    @Shadow
    private ClientLevel level;

    @WrapMethod(method = "allChanged")
    private void ip_voxyCreationLevel(Operation<Void> original) {
        IPVoxyPortalView.beginRendererCreation(level);
        try {
            original.call();
        }
        finally {
            IPVoxyPortalView.endRendererCreation();
        }
    }
}
