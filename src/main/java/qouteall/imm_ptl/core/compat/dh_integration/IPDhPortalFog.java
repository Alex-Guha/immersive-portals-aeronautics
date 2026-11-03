/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.dh_integration;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.DhApi;
import com.seibel.distanthorizons.api.enums.rendering.EDhApiFogFalloff;
import com.seibel.distanthorizons.api.methods.events.abstractEvents.DhApiBeforeFogRenderEvent;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiCancelableEventParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiFogRenderParam;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiMutableFogRenderParam;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.render.IPPortalViewDistance;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * DH's own LOD fog (no shader pack) in a portal pass whose LODs end in fog ({@link IPPortalViewDistance#currentLodFog}):
 * opaque at {@link IPPortalViewDistance#lodFogAnchor}, over the terrain fog's band before it, blended into the player's
 * own fog settings along the approach ramp. With a pack, Iris's {@code dhRenderDistance} does this instead.
 * <p>
 * DH's far fog percentages are of its LOD render distance ({@code uFogScale} is one over it, {@code GlDhFogShader}).
 */
public final class IPDhPortalFog extends DhApiBeforeFogRenderEvent {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean failed = false;

    private IPDhPortalFog() {
    }

    public static void init() {
        DhApi.events.bind(DhApiBeforeFogRenderEvent.class, new IPDhPortalFog());
    }

    @Override
    public void beforeRender(DhApiCancelableEventParam<EventParam> event) {
        if (failed || !PortalRendering.isRendering() || event.value == null) {
            return;
        }
        double lodFog = IPPortalViewDistance.currentLodFog();
        if (Double.isNaN(lodFog)) {
            return;
        }
        try {
            if (IrisInterface.invoker.isShaders()) {
                return;
            }
            Integer chunks = DhApi.Delayed.configs.graphics().chunkRenderDistance().getValue();
            if (chunks == null || chunks <= 0) {
                return;
            }
            double lodBlocks = chunks * 16.0;
            double end = IPPortalViewDistance.lodFogAnchor();
            if (end >= lodBlocks) {
                return;
            }
            double start = Math.max(0, end - IPPortalViewDistance.fadeBand());
            float own = (float) IPPortalViewDistance.smoothstep(lodFog);

            DhApiFogRenderParam original = event.value.getOriginalFogRenderParam();
            DhApiMutableFogRenderParam fog = event.value.getFogRenderParam();
            fog.setFarFogStartPercent(mix((float) (start / lodBlocks), original.getFarFogStartPercent(), own));
            fog.setFarFogEndPercent(mix((float) (end / lodBlocks), original.getFarFogEndPercent(), own));
            fog.setFarFogMaxThickness(mix(1f, original.getFarFogMaxThickness(), own));
            if (own < 0.5f) {
                fog.setFarFogFalloff(EDhApiFogFalloff.LINEAR);
            }
        } catch (Throwable e) {
            failed = true;
            LOGGER.error("[IPDhPortalFog] disabled", e);
        }
    }

    private static float mix(float a, float b, float t) {
        return a + (b - a) * t;
    }
}
