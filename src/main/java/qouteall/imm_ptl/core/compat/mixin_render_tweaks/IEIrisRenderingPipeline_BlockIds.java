/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A portal view's pipeline skips the block-id setup its first pass would do (IPIrisPortalViewPipelines). */
@Mixin(value = IrisRenderingPipeline.class, remap = false)
public interface IEIrisRenderingPipeline_BlockIds {
    @Accessor("initializedBlockIds")
    void ip_setInitializedBlockIds(boolean value);
}
