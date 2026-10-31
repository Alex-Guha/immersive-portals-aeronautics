/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.iris_integration;

import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.mixin_render_tweaks.IEIrisRenderingPipeline_BlockIds;
import qouteall.imm_ptl.core.compat.voxy_integration.IPVoxyIrisPipelines;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Portal views draw through an Iris pipeline of their own wherever another view would share theirs.
 * <p>
 * Iris keeps one pipeline per dimension, and a pipeline carries state from one frame to the next: the render targets
 * a pack keeps between frames (TAA history, accumulated lighting and noise), the previous camera position and
 * matrices ({@code onNewFrame}, run by every {@code beginLevelRendering}), the smoothed centre depth. A portal into
 * another dimension draws through that dimension's pipeline, so its view and the player's never meet. A portal into
 * the player's own world drew through the player's pipeline: each frame's player pass took the portal's image and
 * camera as its previous frame and the portal pass the player's (the whole screen slightly blurred, ghosts of the
 * other view, LOD noise left unaccumulated), and the portal pass got the player's per-frame uniforms
 * ({@code cameraPosition}, {@code gbufferModelView}, ...): Iris refreshes them once per program per frame counter,
 * which moves only at {@code GameRenderer.render}, and the player's pass used every program first. Two views into one
 * other dimension did the same to each other (the camera positions a few blocks apart: that view blurred). Such a pass
 * now draws through a pipeline of its own, made by Iris's own factory ({@code MixinIrisPipelineManager_PortalViews})
 * and kept here. A view keeps its pipeline from frame to frame, its history being of that camera; which view gets which
 * goes by portal and layer, not by the order they are drawn in, so one portal leaving the screen does not hand another
 * a stranger's history.
 * <p>
 * Each pipeline holds a full set of the pack's render targets (at 1440p, Sundial Lite's formats work out to several
 * hundred MB of video memory), so {@link #MAX_VIEWS} views into a dimension get one. A view nested less deeply takes
 * the place of a more nested one, and any view left over shares with the most nested: the views the player looks at
 * directly fill the most screen and show a shared history the most.
 * <p>
 * Not while Voxy's LODs are bound to the player's pipeline: a Voxy render system binds one pipeline
 * ({@code IPVoxyIrisPipelines}), so another pipeline would have no LODs; those views keep sharing.
 */
public final class IPIrisPortalViewPipelines {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean VOXY = ModList.get().isLoaded("voxy");

    private static final int MAX_VIEWS = 3;

    /**
     * A view not drawn in the frame before this one gives up its pipeline. Any longer and every view shares for that
     * long after a crossing, when all of them are new at once.
     */
    private static final int FORGET_FRAMES = 1;

    /** Maps of the same shape as Iris's map of pipelines by dimension key, one for each view of a dimension. */
    public static final List<Map<NamespacedId, WorldRenderingPipeline>> PIPELINES =
        List.of(new HashMap<>(), new HashMap<>(), new HashMap<>());

    private static final Map<NamespacedId, View[]> VIEWS = new HashMap<>();

    private static final class View {
        final UUID portal;
        final int layer;
        int lastFrame;

        View(UUID portal, int layer, int lastFrame) {
            this.portal = portal;
            this.layer = layer;
            this.lastFrame = lastFrame;
        }
    }

    private IPIrisPortalViewPipelines() {
    }

    /**
     * In {@code PipelineManager.preparePipeline}: the map to find or make the current pass's pipeline in, null for
     * Iris's own. The player's pass holds Iris's own pipeline of the player's world; in any other world the first view
     * takes it.
     */
    public static @Nullable Map<NamespacedId, WorldRenderingPipeline> pipelinesOfPass(NamespacedId dimension) {
        if (!PortalRendering.isRendering() || Iris.getCurrentPack().isEmpty()) {
            return null;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || (VOXY && IPVoxyIrisPipelines.bindsVoxy(level))) {
            return null;
        }
        int view = viewOfPass(dimension);
        int index = level.dimension() == RenderStates.originalPlayerDimension ? view : view - 1;
        return index < 0 ? null : PIPELINES.get(index);
    }

    private static int viewOfPass(NamespacedId dimension) {
        Portal portal = PortalRendering.getRenderingPortal();
        int layer = PortalRendering.getPortalLayer();
        int frame = RenderStates.frameIndex;
        View[] views = VIEWS.computeIfAbsent(dimension, d -> new View[MAX_VIEWS]);
        int free = -1;
        int deepest = 0;
        for (int i = 0; i < views.length; i++) {
            View view = views[i];
            if (view != null && view.layer == layer && view.portal.equals(portal.getUUID())) {
                view.lastFrame = frame;
                return i;
            }
            if (free < 0 && (view == null || frame - view.lastFrame > FORGET_FRAMES)) {
                free = i;
            }
            if (view != null && views[deepest] != null && view.layer > views[deepest].layer) {
                deepest = i;
            }
        }
        if (free < 0) {
            if (layer >= views[deepest].layer) {
                return deepest;
            }
            free = deepest;
        }
        views[free] = new View(portal.getUUID(), layer, frame);
        return free;
    }

    /**
     * A pipeline Iris just made for a portal view. A new pipeline's first {@code beginLevelRendering} sets the pack's
     * block ids and reloads {@code Minecraft.levelRenderer}, here that world's: every chunk rebuilt for ids an earlier
     * pipeline set already, from the same pack. Marked as done instead.
     */
    public static void onCreated(NamespacedId dimension, WorldRenderingPipeline pipeline) {
        LOGGER.info("[IPIris] Made another Iris pipeline for portal views of {}", dimension);
        if (pipeline instanceof IrisRenderingPipeline) {
            ((IEIrisRenderingPipeline_BlockIds) pipeline).ip_setInitializedBlockIds(true);
        }
    }

    /** With the pipelines gone, no view holds one. */
    public static void onDestroyed() {
        PIPELINES.forEach(Map::clear);
        VIEWS.clear();
    }
}
