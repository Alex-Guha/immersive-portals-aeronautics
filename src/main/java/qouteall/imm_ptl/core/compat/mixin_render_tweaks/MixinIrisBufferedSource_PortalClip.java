/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.irisshaders.batchedentityrendering.impl.FullyBufferedMultiBufferSource;
import net.irisshaders.batchedentityrendering.impl.TransparencyType;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.iris_integration.IPIrisBatchClip;

/** Re-enables IP's front clipping around Iris's deferred entity/block-entity flush in portal passes. */
@Mixin(value = FullyBufferedMultiBufferSource.class, remap = false)
public class MixinIrisBufferedSource_PortalClip {
    @WrapMethod(method = "endBatch()V", require = 0)
    private void ip_clipDeferredFlush(Operation<Void> original) {
        IPIrisBatchClip.aroundFlush(() -> original.call());
    }

    @WrapMethod(method = "endBatchWithType", require = 0)
    private void ip_clipDeferredTypedFlush(TransparencyType type, Operation<Void> original) {
        IPIrisBatchClip.aroundFlush(() -> original.call(type));
    }
}
