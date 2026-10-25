/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.voxy_integration.IPVoxyPortalView;

import java.util.function.Consumer;

/**
 * Roxy wraps each Voxy render-generation service's result consumer in a guard that keeps a built mesh only if the
 * service is the one Roxy tracks (the player's dimension, followed from {@code Minecraft.levelRenderer}); any other
 * service's meshes are discarded as stale and their positions requeued on the tracked one. With IP every dimension
 * loaded on the client has its own Voxy renderer, so a dimension seen through a portal never received a mesh. Its
 * results now go to Voxy directly, as in stock Voxy; the tracked service's keep Roxy's guard. Which one Roxy tracks
 * is checked per result, since it changes when the player changes dimension. Roxy's bookkeeping for the task is still
 * finished by its own call at the end of {@code processJob}.
 */
@Pseudo
@Mixin(targets = "me.cortex.voxy.client.core.rendering.building.RenderGenerationService", remap = false)
public class MixinVoxyRenderGenerationService_PortalSideResults {
    @SuppressWarnings("unchecked")
    @WrapOperation(
        method = "setResultConsumer",
        at = @At(
            value = "INVOKE",
            target = "Lnet/rasanovum/roxy/patch/RoxyVoxyRenderPatch;wrapResultConsumer(Ljava/util/function/Consumer;)Ljava/util/function/Consumer;"
        ),
        require = 0
    )
    private Consumer<?> ip_acceptPortalSideResults(Consumer<?> consumer, Operation<Consumer<?>> original) {
        Consumer<?> guarded = original.call(consumer);
        if (consumer == null || guarded == null) {
            return guarded;
        }
        Object service = this;
        Consumer<Object> direct = (Consumer<Object>) consumer;
        Consumer<Object> roxy = (Consumer<Object>) guarded;
        return result -> {
            if (IPVoxyPortalView.isRoxyActiveService(service)) {
                roxy.accept(result);
            }
            else {
                direct.accept(result);
            }
        };
    }
}
