/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;
import dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher;
import foundry.veil.api.client.render.VeilRenderBridge;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.PrioritizeChunkUpdates;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.chunk.RenderRegionCache;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.mixin_sable.IPMinecraftLevelRendererAccessor;
import qouteall.imm_ptl.core.compat.mixin_sable.IPVanillaChunkedSubLevelRenderDataAccessor;

/**
 * Drives Sable's vanilla-renderer culling for a portal-side world: on every solid-layer pass builds a
 * {@link Frustum} from the pass matrices, calls {@code preRenderChunks} / {@code updateCulling} on the
 * {@link SubLevelRenderDispatcher} and compiles nearby sections for every ship. Before that, ship render data
 * bound to a different {@link SectionRenderDispatcher} than the current {@link LevelRenderer}'s is rebuilt by
 * temporarily swapping {@code Minecraft.levelRenderer} (via {@link IPMinecraftLevelRendererAccessor}).
 */
public class IPSablePortalCullImpl {
    private static final Logger LOGGER = LogUtils.getLogger();

    static boolean cull(LevelRenderer levelRenderer, ClientLevel world, double camX, double camY, double camZ, Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        ClientSubLevelContainer container = SubLevelContainer.getContainer(world);
        if (container == null) {
            return false;
        }

        List<ClientSubLevel> subLevels = container.getAllSubLevels();
        if (subLevels == null || !subLevels.iterator().hasNext()) {
            return false;
        }

        Minecraft minecraft = Minecraft.getInstance();
        Camera camera = minecraft.gameRenderer.getMainCamera();
        SubLevelRenderDispatcher dispatcher = SubLevelRenderDispatcher.get();
        rebindRenderData(minecraft, levelRenderer, subLevels);
        Frustum frustum = new Frustum(modelViewMatrix, projectionMatrix);
        frustum.prepare(camX, camY, camZ);
        boolean spectator = minecraft.player != null && minecraft.player.isSpectator();
        dispatcher.preRenderChunks(camera);
        dispatcher.updateCulling(subLevels, camX, camY, camZ, VeilRenderBridge.create(frustum), spectator);
        RenderRegionCache regionCache = new RenderRegionCache();

        for (ClientSubLevel subLevel : subLevels) {
            subLevel.getRenderData().compileSections(PrioritizeChunkUpdates.NEARBY, regionCache, camera);
        }

        return true;
    }

    /**
     * Rebuilds the render data of every ship whose data is bound to a {@link SectionRenderDispatcher} other
     * than {@code levelRenderer}'s, with {@code Minecraft.levelRenderer} temporarily pointed at
     * {@code levelRenderer} so Sable picks up the right dispatcher.
     */
    private static void rebindRenderData(Minecraft minecraft, LevelRenderer levelRenderer, Iterable<ClientSubLevel> subLevels) {
        SectionRenderDispatcher currentDispatcher = levelRenderer.getSectionRenderDispatcher();
        if (currentDispatcher == null) {
            return;
        }

        ArrayList<ClientSubLevel> stale = new ArrayList<>();

        for (ClientSubLevel subLevel : subLevels) {
            SectionRenderDispatcher bound = boundDispatcherOf(subLevel.getRenderData());
            if (bound != null && bound != currentDispatcher) {
                stale.add(subLevel);
            }
        }

        if (stale.isEmpty()) {
            return;
        }

        LevelRenderer previousRenderer = minecraft.levelRenderer;
        boolean swapped = previousRenderer != levelRenderer;

        try {
            if (swapped) {
                ((IPMinecraftLevelRendererAccessor) minecraft).ip_setLevelRenderer(levelRenderer);
            }

            for (ClientSubLevel subLevel : stale) {
                subLevel.updateRenderData();
            }
        } finally {
            if (swapped) {
                ((IPMinecraftLevelRendererAccessor) minecraft).ip_setLevelRenderer(previousRenderer);
            }
        }

        LOGGER.debug(
            "[IPSablePortalCull] rebuilt {} craft render-data bound to the WRONG world's SectionRenderDispatcher{}",
            stale.size(),
            swapped ? " (swapped mc.levelRenderer for the rebuild)" : " (renderer already current)"
        );
    }

    /** The dispatcher a chunked (vanilla-renderer) render data was built against; null for other kinds. */
    private static SectionRenderDispatcher boundDispatcherOf(SubLevelRenderData renderData) {
        return renderData instanceof IPVanillaChunkedSubLevelRenderDataAccessor accessor
            ? accessor.ip_getSectionRenderDispatcher()
            : null;
    }
}
