/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.dh_integration;

import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.config.EDhApiDepthRange;
import com.seibel.distanthorizons.api.interfaces.render.IDhApiRenderProxy;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeApplyShaderRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.api.objects.DhApiResult;
import com.seibel.distanthorizons.core.util.math.DhMat4f;

/**
 * Hands DH's depth texture and projection to {@link IPDhLodOcclusion} in every pass, right before DH applies its LODs
 * to Minecraft's buffer (its depth texture then holds this pass's LODs; a nested portal pass clears it).
 */
public final class IPDhLodOcclusionCapture extends DhApiBeforeApplyShaderRenderEvent {
    private IPDhLodOcclusionCapture() {
    }

    public static void init() {
        DhApi.events.bind(DhApiBeforeApplyShaderRenderEvent.class, new IPDhLodOcclusionCapture());
    }

    @Override
    public void beforeRender(DhApiCancelableEventParam<DhApiRenderParam> event) {
        if (!IPDhLodOcclusion.ENABLED) {
            return;
        }
        try {
            IDhApiRenderProxy proxy = DhApi.Delayed.renderProxy;
            DhApiRenderParam param = event.value;
            if (proxy == null || param == null || param.dhProjectionMatrix == null) {
                return;
            }
            DhApiResult<Integer> depthTexture = proxy.getDhDepthTextureGlId();
            if (!depthTexture.success || depthTexture.payload == null) {
                return;
            }
            boolean zeroToOne = proxy.getDepthRange() == EDhApiDepthRange.ZERO_TO_POS_ONE;
            IPDhLodOcclusion.capture(
                depthTexture.payload,
                DhMat4f.createJomlMatrix(param.dhProjectionMatrix),
                zeroToOne ? 1f : 2f,
                zeroToOne ? 0f : -1f,
                proxy.getDepthDirection().farDepth
            );
        } catch (Throwable e) {
            IPDhLodOcclusion.disable(e);
        }
    }
}
