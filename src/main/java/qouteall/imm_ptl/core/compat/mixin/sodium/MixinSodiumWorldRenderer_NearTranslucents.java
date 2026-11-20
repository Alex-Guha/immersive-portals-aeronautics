/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.minecraft.client.renderer.RenderType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sodium_compatibility.IPNearTranslucents;

/** After a pass's translucent terrain: the player's world's translucent terrain in front of the portal, in its view. */
@Mixin(value = SodiumWorldRenderer.class, remap = false)
public class MixinSodiumWorldRenderer_NearTranslucents {
    @Inject(method = "drawChunkLayer", at = @At("TAIL"))
    private void ip_afterDrawChunkLayer(
        RenderType type, ChunkRenderMatrices matrices, double x, double y, double z, CallbackInfo ci
    ) {
        IPNearTranslucents.afterDrawChunkLayer((SodiumWorldRenderer) (Object) this, type, matrices, x, y, z);
    }
}
