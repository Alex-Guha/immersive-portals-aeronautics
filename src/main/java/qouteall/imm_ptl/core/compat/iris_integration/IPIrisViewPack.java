/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.iris_integration;

import com.google.common.collect.ImmutableList;
import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.gl.shader.StandardMacros;
import net.irisshaders.iris.helpers.StringPair;
import net.irisshaders.iris.pipeline.IrisRenderingPipeline;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.irisshaders.iris.shaderpack.materialmap.WorldRenderingSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.mixin_render_tweaks.IEIrisRenderingPipeline_BlockIds;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashMap;
import java.util.Map;

/**
 * Nested portal views (a portal seen through a portal, pass layer 2 and deeper) drawn through a small pack bundled in
 * the jar ({@code ip_view_pack/shaders}) while the player's view and the views seen directly keep the player's pack;
 * also small direct views while the portal frame rate target asks for it ({@code IPViewGuard.simplifiesCurrent}).
 * The small pack has terrain and Distant Horizons programs of its own and no shadow, deferred or composite passes;
 * everything else falls back to Iris's built-in vanilla-like shaders. A view through it costs about what a view
 * without a pack costs.
 * <p>
 * The chunk meshes stay the player's pack's: Sodium builds them once, in Iris's extended vertex format with the
 * player's pack's block ids and its ambient occlusion and shading settings, which are global. Building a pipeline
 * writes those settings from its own pack, and its first frame sets its block ids and reloads every chunk; for the
 * small pack's pipelines all of that is skipped ({@link #isBuilding}, {@code MixinIrisRenderingPipeline_ViewPack},
 * {@code MixinIrisSodiumPrograms_ViewPack}), and the pack's programs read the player's pack's settings through
 * defines. The pipelines are built here, not by Iris's factory: that takes the player's pack, and
 * {@code PipelineManager.preparePipeline} resets the frame counter of every pack when it makes one.
 * <p>
 * A pipeline takes about half a second to build (Iris compiles every program), so one is built for a dimension when
 * Iris builds its own for it ({@link #onIrisPipelineMade}: joining, changing dimension, reloading the pack, the first
 * view into a dimension), in the same pause; a nested view into any other dimension builds its own when first drawn.
 * <p>
 * Client option {@code simpleNestedPortalViews} (on by default).
 */
public final class IPIrisViewPack {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String[] FILES = {
        "shaders.properties",
        "gbuffers_terrain.vsh", "gbuffers_terrain.fsh",
        "dh_terrain.vsh", "dh_terrain.fsh",
    };

    /** The first pass layer drawn through the small pack (2: views seen through another view). */
    private static final int FIRST_LAYER = 2;

    private static final Map<NamespacedId, WorldRenderingPipeline> PIPELINES = new HashMap<>();
    private static @Nullable ShaderPack pack = null;
    private static @Nullable Path packDir = null;
    private static boolean failed = false;
    private static boolean building = false;

    static {
        // the portal frame rate target draws small direct views through this pack too
        qouteall.imm_ptl.core.render.IPViewGuard.simpleViews = IPIrisViewPack::usable;
    }

    private IPIrisViewPack() {
    }

    /** While a small-pack pipeline is being built: its writes to Iris's global settings are skipped. */
    public static boolean isBuilding() {
        return building;
    }

    /**
     * In {@code PipelineManager.preparePipeline}: the small pack's pipeline for this pass, or null for the usual one.
     */
    public static @Nullable WorldRenderingPipeline pipelineOfPass(NamespacedId dimension) {
        if (!PortalRendering.isRendering() || !usable()) {
            return null;
        }
        int layer = PortalRendering.getPortalLayer();
        if (layer < FIRST_LAYER && !(layer == 1 && qouteall.imm_ptl.core.render.IPViewGuard.simplifiesCurrent())) {
            return null;
        }
        return pipelineOf(dimension);
    }
    
    /** After {@code PipelineManager.preparePipeline} made a pipeline for {@code dimension}: ours for it too, now. */
    public static void onIrisPipelineMade(NamespacedId dimension) {
        // with the frame rate target on, direct views may be drawn through it too (IPViewGuard)
        if (usable() && (IPConfig.getConfig().maxPortalLayer >= FIRST_LAYER
            || IPConfig.getConfig().portalFrameRateTarget > 0)) {
            pipelineOf(dimension);
        }
    }
    
    private static boolean usable() {
        if (!IPConfig.getConfig().simpleNestedPortalViews || failed || Iris.getCurrentPack().isEmpty()) {
            return false;
        }
        ClientLevel level = Minecraft.getInstance().level;
        // Voxy binds its LODs to one Iris pipeline; another would draw the view without them
        return level != null && !IPIrisPortalViewPipelines.voxyBinds(level);
    }
    
    private static @Nullable WorldRenderingPipeline pipelineOf(NamespacedId dimension) {
        WorldRenderingPipeline pipeline = PIPELINES.get(dimension);
        if (pipeline == null) {
            pipeline = build(dimension);
            if (pipeline != null) {
                PIPELINES.put(dimension, pipeline);
            }
        }
        return pipeline;
    }

    private static @Nullable WorldRenderingPipeline build(NamespacedId dimension) {
        long start = System.nanoTime();
        building = true;
        try {
            if (pack == null) {
                pack = loadPack();
            }
            IrisRenderingPipeline pipeline = new IrisRenderingPipeline(pack.getProgramSet(dimension));
            // its first frame would set the small pack's block ids and reload every chunk
            ((IEIrisRenderingPipeline_BlockIds) pipeline).ip_setInitializedBlockIds(true);
            LOGGER.info("[IPViewPack] Made the view pack's pipeline for {} in {} ms", dimension,
                (System.nanoTime() - start) / 1_000_000);
            return pipeline;
        }
        catch (Throwable e) {
            // not through Iris's own fallback: its vanilla pipeline would rewrite the global settings
            LOGGER.error("[IPViewPack] Could not make the view pack's pipeline; nested views keep the player's pack", e);
            failed = true;
            return null;
        }
        finally {
            building = false;
        }
    }

    /** The pack, copied out of the jar into a temporary directory (Iris reads packs through files). */
    private static ShaderPack loadPack() throws IOException {
        if (packDir == null) {
            Path root = Files.createTempDirectory("ip-view-pack");
            Path shaders = Files.createDirectories(root.resolve("shaders"));
            for (String name : FILES) {
                try (InputStream in = IPIrisViewPack.class.getResourceAsStream("/ip_view_pack/shaders/" + name)) {
                    if (in == null) {
                        throw new IOException("missing from the jar: ip_view_pack/shaders/" + name);
                    }
                    Files.copy(in, shaders.resolve(name), StandardCopyOption.REPLACE_EXISTING);
                }
                shaders.resolve(name).toFile().deleteOnExit();
            }
            shaders.toFile().deleteOnExit();
            root.toFile().deleteOnExit();
            packDir = shaders;
        }
        ImmutableList.Builder<StringPair> defines = ImmutableList.builder();
        defines.addAll(StandardMacros.createStandardEnvironmentDefines());
        // the player's pack's settings, which the meshes were built with
        if (WorldRenderingSettings.INSTANCE.shouldUseSeparateAo()) {
            defines.add(new StringPair("IP_SEPARATE_AO", ""));
        }
        if (WorldRenderingSettings.INSTANCE.shouldDisableDirectionalShading()) {
            defines.add(new StringPair("IP_NO_DIRECTIONAL_SHADING", ""));
        }
        return new ShaderPack(packDir, defines.build(), false);
    }


    /** With Iris's pipelines (a pack reload or change): the settings the defines took may have changed. */
    public static void destroyAll() {
        PIPELINES.forEach((dimension, pipeline) -> {
            LOGGER.info("[IPViewPack] Destroying the view pack's pipeline of {}", dimension);
            pipeline.destroy();
        });
        PIPELINES.clear();
        pack = null;
        failed = false;
    }
}
