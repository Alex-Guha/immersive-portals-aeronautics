/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.voxy_integration;

import com.mojang.logging.LogUtils;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.IGetVoxyRenderSystem;
import me.cortex.voxy.client.core.IrisVoxyRenderPipeline;
import me.cortex.voxy.client.iris.IGetIrisVoxyPipelineData;
import me.cortex.voxy.client.iris.IrisVoxyRenderPipelineData;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.pipeline.WorldRenderingPipeline;
import net.irisshaders.iris.shaderpack.DimensionId;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.irisshaders.iris.shaderpack.materialmap.NamespacedId;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.mixin_voxy.IPVoxyIrisPipelineManagerAccessor;
import qouteall.imm_ptl.core.compat.mixin_voxy.IPVoxyIrisRenderPipelineAccessor;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * Voxy's render systems and Iris's per-dimension pipelines.
 * <p>
 * With a shader pack that supports Voxy, a Voxy render system draws through the Iris pipeline's Voxy data
 * ({@code IrisVoxyRenderPipeline}), which binds to one render system only; Voxy takes the pipeline Iris last made
 * current. Iris keeps a pipeline per dimension and makes current whichever dimension it last drew, while IP keeps a
 * level renderer, and so a Voxy render system, per dimension. The second render system made (IP loading another
 * dimension at join, or Roxy's deferred rebuild after a portal pass) found the player's pipeline already bound; Voxy
 * then threw and switched shaders off. Each render system now takes its own dimension's Iris pipeline, or Voxy's plain
 * pipeline while that one does not exist yet or is bound elsewhere, and is rebuilt at the start of the next frame
 * when the pipeline it would take changes (the dimension's pipeline made, the pack reloaded or switched off). Each
 * renderer is rebuilt at most once per pipeline it is after, so a pipeline that cannot be bound does not rebuild every
 * frame.
 */
public final class IPVoxyIrisPipelines {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Object NO_DATA = new Object();

    private static final Map<LevelRenderer, Object> REBUILT_FOR = new WeakHashMap<>();
    private static final Map<LevelRenderer, ClientLevel> PENDING = new LinkedHashMap<>();
    private static boolean listening;

    private IPVoxyIrisPipelines() {
    }

    /** Iris's pipeline key for a level, as {@code Iris.getCurrentDimension()} makes it for {@code Minecraft.level}. */
    private static NamespacedId irisKey(ClientLevel level) {
        ResourceLocation location = level.dimension().location();
        NamespacedId id = new NamespacedId(location.getNamespace(), location.getPath());
        ShaderPack pack = Iris.getCurrentPack().orElse(null);
        if (pack != null && pack.getDimensionMap().containsKey(id)) {
            return id;
        }
        ResourceLocation effects = level.dimensionType().effectsLocation();
        if (Level.END.location().equals(effects)) {
            return DimensionId.END;
        }
        if (Level.NETHER.location().equals(effects)) {
            return DimensionId.NETHER;
        }
        return id;
    }

    private static @Nullable WorldRenderingPipeline pipelineFor(ClientLevel level) {
        Map<NamespacedId, WorldRenderingPipeline> pipelines =
            ((IPVoxyIrisPipelineManagerAccessor) Iris.getPipelineManager()).ip_getPipelinesPerDimension();
        return pipelines.get(irisKey(level));
    }

    private static @Nullable IrisVoxyRenderPipelineData voxyData(@Nullable WorldRenderingPipeline pipeline) {
        return pipeline instanceof IGetIrisVoxyPipelineData holder ? holder.voxy$getPipelineData() : null;
    }

    /** In Voxy's {@code createIrisPipeline}: the Iris pipeline the new render system may bind to, or null. */
    public static @Nullable WorldRenderingPipeline pipelineForNewRenderSystem() {
        ClientLevel level = IPVoxyPortalView.creationLevel();
        if (level == null) {
            LOGGER.info("[IPVoxy] New Voxy renderer: no level; Voxy's plain pipeline");
            return null;
        }
        WorldRenderingPipeline pipeline = pipelineFor(level);
        IrisVoxyRenderPipelineData data = voxyData(pipeline);
        String outcome = pipeline == null ? "no Iris pipeline yet"
            : data == null ? "Iris pipeline without Voxy support"
            : data.thePipeline != null ? "Iris pipeline bound to another Voxy renderer"
            : "its Iris pipeline";
        LOGGER.info(
            "[IPVoxy] New Voxy renderer for {} ({} level, {} creation scopes open): {}",
            level.dimension().location(), IPVoxyPortalView.creationLevelSource(),
            IPVoxyPortalView.creationScopeDepth(), outcome
        );
        if (data != null && data.thePipeline != null) {
            return null;
        }
        return pipeline;
    }

    /**
     * At the start of a Voxy draw, with the drawn level in {@code Minecraft.level}. Returns whether the render system
     * may draw; one whose pipeline does not match its dimension's Iris pipeline is rebuilt at the next frame start.
     */
    public static boolean checkBeforeDraw(AbstractRenderPipeline own) {
        Minecraft client = Minecraft.getInstance();
        ClientLevel level = client.level;
        if (level == null) {
            return true;
        }
        IrisVoxyRenderPipelineData wanted = voxyData(pipelineFor(level));
        IrisVoxyRenderPipelineData mine = own instanceof IrisVoxyRenderPipeline iris
            ? ((IPVoxyIrisRenderPipelineAccessor) iris).ip_getData() : null;
        if (mine == wanted) {
            return true;
        }
        boolean bindable = wanted == null || wanted.thePipeline == null;
        if (bindable && scheduleRebuild(client.levelRenderer, level, wanted)) {
            return false;
        }
        // Voxy's plain pipeline draws as it would with a pack that has no Voxy support; one bound to another Iris
        // pipeline's data (a reloaded pack's) does not
        return mine == null;
    }

    private static boolean scheduleRebuild(
        LevelRenderer levelRenderer, ClientLevel level, @Nullable IrisVoxyRenderPipelineData wanted
    ) {
        Object target = wanted != null ? wanted : NO_DATA;
        if (REBUILT_FOR.get(levelRenderer) == target) {
            return false;
        }
        REBUILT_FOR.put(levelRenderer, target);
        PENDING.put(levelRenderer, level);
        if (!listening) {
            listening = true;
            NeoForge.EVENT_BUS.addListener(IPGlobal.PreGameRenderEvent.class, event -> rebuildPending());
        }
        return true;
    }

    private static void rebuildPending() {
        if (PENDING.isEmpty()) {
            return;
        }
        Map<LevelRenderer, ClientLevel> pending = new LinkedHashMap<>(PENDING);
        PENDING.clear();
        pending.forEach((levelRenderer, level) -> {
            IGetVoxyRenderSystem holder = (IGetVoxyRenderSystem) levelRenderer;
            if (holder.voxy$getRenderSystem() == null) {
                // shut down meanwhile (world closed, Roxy's own reload under way)
                return;
            }
            LOGGER.info("[IPVoxy] Rebuilding Voxy's renderer of {} for its Iris pipeline", level.dimension().location());
            holder.voxy$shutdownRenderer();
            IPVoxyPortalView.beginRendererCreation(level);
            try {
                holder.voxy$createRenderer();
            }
            finally {
                IPVoxyPortalView.endRendererCreation();
            }
        });
    }
}
