/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.iris_integration;

import com.mojang.logging.LogUtils;
import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.mixin_render_tweaks.IEIris_CurrentPack;

import java.lang.reflect.Method;
import java.util.function.Supplier;

/**
 * Builds each Iris pipeline from Euphoria Patcher's pack for that pipeline's dimension.
 * <p>
 * Euphoria Patcher bakes the dimension into the pack it patches (its {@code CURRENT_EUPHORIA_PATCHES_DIMENSION_*}
 * define and dimension mappings, read from {@code Minecraft.level} when the pack is built) and keeps one pack per
 * dimension. On its own it swaps Iris's current pack and rebuilds every pipeline whenever {@code Minecraft.level}'s
 * dimension changes. With IP that happened twice a frame with a portal in view (IP swaps {@code Minecraft.level} for
 * each portal pass) and on every crossing: each rebuild recompiled both dimensions' pipelines and reloaded every
 * world renderer (all chunks of every dimension), a hitch of up to a second and a half per crossing.
 * <p>
 * Iris itself keeps one pipeline per dimension and only switches between them. So Euphoria's rebuild is switched off
 * ({@code MixinEuphoriaDimensionRefresh_NoRebuild}), and when Iris creates the pipeline for a dimension
 * ({@code MixinIrisPipelineManager_EuphoriaPack}; {@code Minecraft.level} is that dimension then, in the main pass as in
 * a portal pass) Euphoria is asked for its pack for that dimension, the pipeline is built from it, and Iris's current
 * pack is put back. Crossings then rebuild nothing, as without Euphoria.
 */
public class IPEuphoriaPacks {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static boolean initialized = false;
    private static @Nullable Method swapToDimension = null;
    private static @Nullable Method getCurrentDimension = null;
    private static boolean loggedSwap = false;

    private static boolean init() {
        if (!initialized) {
            initialized = true;
            if (ModList.get().isLoaded("euphoria_patcher")) {
                try {
                    swapToDimension = Class.forName(
                        "com.euphoriapatches.euphoria_patcher.integration.iris.EuphoriaShaderPackCache"
                    ).getMethod("swapToDimension", String.class);
                    getCurrentDimension = Class.forName(
                        "com.euphoriapatches.euphoria_patcher.util.mod.ModLoaderSpecifics"
                    ).getMethod("getCurrentDimensionStatic");
                }
                catch (ReflectiveOperationException | LinkageError e) {
                    swapToDimension = null;
                    getCurrentDimension = null;
                    LOGGER.warn("[IPEuphoria] Euphoria Patcher's per-dimension packs not found; portal views may " +
                        "show another dimension's Euphoria settings", e);
                }
            }
        }
        return swapToDimension != null;
    }

    /**
     * Runs Iris's pipeline factory for the dimension of {@code Minecraft.level} with Euphoria's pack for it, then
     * restores Iris's current pack. Without Euphoria (or if it cannot swap) the factory runs unchanged.
     */
    public static <T> T createWithDimensionPack(Supplier<T> factory) {
        if (!init()) {
            return factory.get();
        }
        ShaderPack previous = Iris.getCurrentPack().orElse(null);
        Object result = null;
        try {
            Object dimension = getCurrentDimension.invoke(null);
            result = swapToDimension.invoke(null, dimension);
            if (!loggedSwap) {
                loggedSwap = true;
                LOGGER.info("[IPEuphoria] building Iris pipelines from Euphoria Patcher's pack for their dimension " +
                    "(first: {}, {})", dimension, result);
            }
        }
        catch (ReflectiveOperationException | RuntimeException e) {
            LOGGER.warn("[IPEuphoria] Euphoria Patcher's pack swap failed", e);
        }
        try {
            return factory.get();
        }
        finally {
            if (previous != null && Iris.getCurrentPack().orElse(null) != previous) {
                IEIris_CurrentPack.ip_setCurrentPack(previous);
            }
        }
    }
}
