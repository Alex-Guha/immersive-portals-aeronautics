/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.commonImpl.VoxyCommon;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.List;

/**
 * Voxy shuts its instance down ({@code /voxy reload}, Voxy switched off in its settings, leaving the world) after
 * shutting down {@code Minecraft.levelRenderer}'s render system only. IP's level renderers of other dimensions kept
 * theirs, holding their worlds: Voxy force-closed the worlds under them ("Not all worlds shutdown, force closing
 * worlds") and the game hung. Every IP level renderer's render system is shut down first; after a reload Voxy calls
 * the player's renderer's {@code allChanged}, which reloads IP's others ({@code ClientWorldLoader
 * ._onWorldRendererReloaded}) and so makes theirs again.
 */
@Mixin(value = VoxyCommon.class, remap = false)
public class MixinVoxyCommon_ShutdownAllRenderers {
    @Inject(method = "shutdownInstance", at = @At("HEAD"), require = 0)
    private static void ip_shutdownEveryDimension(CallbackInfo ci) {
        for (LevelRenderer levelRenderer : List.copyOf(ClientWorldLoader.WORLD_RENDERER_MAP.values())) {
            ((IGetVoxyRenderSystem) levelRenderer).voxy$shutdownRenderer();
        }
    }
}
