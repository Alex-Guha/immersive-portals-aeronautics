/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkVertexType;
import net.irisshaders.iris.pipeline.programs.SodiumPrograms;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.iris_integration.IPIrisViewPack;

/**
 * While the small view pack's pipeline is built ({@link IPIrisViewPack}), the chunk vertex format stays the player's
 * pack's: Iris derives it from the attributes the pack's terrain programs read, and a changed format rebuilds every
 * chunk. The small pack's programs draw the player's pack's meshes (attributes are bound by name; unused ones are
 * ignored).
 */
@Mixin(value = SodiumPrograms.class, remap = false)
public class MixinIrisSodiumPrograms_ViewPack {
    @WrapWithCondition(
        method = "<init>",
        at = @At(value = "INVOKE", target = "Lnet/irisshaders/iris/shaderpack/materialmap/WorldRenderingSettings;setVertexFormat(Lnet/caffeinemc/mods/sodium/client/render/chunk/vertex/format/ChunkVertexType;)V")
    )
    private boolean ip_keepVertexFormat(WorldRenderingSettings settings, ChunkVertexType format) {
        return !IPIrisViewPack.isBuilding();
    }
}
