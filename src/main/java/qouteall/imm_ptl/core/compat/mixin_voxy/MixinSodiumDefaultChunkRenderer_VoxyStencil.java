/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkRenderMatrices;
import net.caffeinemc.mods.sodium.client.render.chunk.DefaultChunkRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.ChunkRenderListIterable;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.DefaultTerrainRenderPasses;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.voxy_integration.IPVoxyPortalView;

/**
 * Voxy draws its LODs at the end of Sodium's cutout pass and leaves the stencil test off, with its own stencil
 * function ({@code GL_EQUAL, 1}) behind. IP turns the test on once per frame ({@code
 * RendererUsingStencil.resetStencilForFrame}) and marks each portal's area in the stencil before the translucent
 * layer; with the test off nothing was marked and the portal's world was drawn across the whole screen. In a portal
 * pass the rest of the pass needs the portal's stencil limit back. The stencil settings are read at the start of the
 * cutout pass, while Minecraft's framebuffer is bound (Voxy itself runs inside Roxy's framebuffer, which has no
 * stencil buffer, and Mesa clamps the reference read there), and written back after it ({@link IPVoxyPortalView}).
 */
@Mixin(value = DefaultChunkRenderer.class, remap = false)
public class MixinSodiumDefaultChunkRenderer_VoxyStencil {
    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void ip_saveStencilBeforeVoxy(
        ChunkRenderMatrices matrices, CommandList commandList, ChunkRenderListIterable renderLists,
        TerrainRenderPass renderPass, CameraTransform camera, boolean indexedRenderingEnabled, CallbackInfo ci
    ) {
        if (renderPass == DefaultTerrainRenderPasses.CUTOUT) {
            IPVoxyPortalView.saveStencil();
        }
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void ip_restoreStencilAfterVoxy(CallbackInfo ci) {
        IPVoxyPortalView.restoreStencil();
    }
}
