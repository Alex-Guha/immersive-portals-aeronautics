/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import dev.djefrey.colorwheel.engine.uniform.ClrwlFrameUniforms;
import dev.djefrey.colorwheel.engine.uniform.ClrwlGbuffersPassUniforms;
import dev.engine_room.flywheel.api.backend.RenderContext;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.resources.ResourceLocation;

/**
 * Colorwheel's side of a far-half draw ({@link IPFlywheelFarHalf}). Colorwheel keeps one set of frame and pass
 * uniforms for all engines (static buffers) and writes the frame's (camera position, which IP's portal clip reads as
 * {@code flw_cameraPos}) only once per engine frame ({@code ClrwlEngine.prepareFrame}), the pass's (view, projection,
 * frustum) only in its solid draw ({@code ClrwlEngine.preparePass}). So a far-half draw writes the frame's for its
 * mapped camera first, its own solid draw writes the pass's, and afterwards both are put back for the pass being
 * drawn, whose translucent draw and crumbling follow and read them. Loaded only when Colorwheel is installed.
 */
final class IPColorwheelFarHalf {
    private IPColorwheelFarHalf() {
    }

    /** Before the far-half draw: the frame uniforms for its mapped camera and source level. */
    static void begin(final RenderContext farHalfContext) {
        ClrwlFrameUniforms.update(farHalfContext);
    }

    /** After the far-half draw: the frame and pass uniforms of the pass it was drawn into. */
    static void end(final RenderContext passContext) {
        ClrwlFrameUniforms.update(passContext);
        Iris.getCurrentPack().ifPresent(pack -> {
            final ResourceLocation dimension = passContext.level().dimension().location();
            ClrwlGbuffersPassUniforms.update(passContext, pack, new NamespacedId(dimension.getNamespace(), dimension.getPath()));
        });
    }
}
