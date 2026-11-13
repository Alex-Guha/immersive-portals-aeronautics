/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Switches off Euphoria Patcher's rebuild on dimension changes; each Iris pipeline gets Euphoria's pack for its own
 * dimension when Iris creates it instead ({@code IPEuphoriaPacks}).
 * <p>
 * Euphoria checks {@code Minecraft.level}'s dimension at the head of every {@code PipelineManager.preparePipeline}
 * ({@code beforePreparePipeline}) and, after a {@code Minecraft.setLevel}, on the client tick; on a change
 * {@code applySwap} swaps Iris's current pack, destroys every Iris pipeline and rebuilds (or schedules a full Iris
 * reload). With IP that ran twice a frame with a portal to another dimension in view (1 fps with
 * Complementary Unbound r5.9.3 + Euphoria Patches 1.10.5) and on every crossing (a hitch of up to 1.5 s:
 * both pipelines recompiled and every chunk of every dimension rebuilt). A vanilla dimension change still gets new
 * pipelines: Iris destroys them itself on {@code setLevel}.
 */
@Mixin(
    targets = "com.euphoriapatches.euphoria_patcher.integration.iris.DimensionShaderRefresh",
    remap = false
)
public class MixinEuphoriaDimensionRefresh_NoRebuild {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Unique
    private static boolean ip_logged = false;

    @Inject(
        method = "beforePreparePipeline",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void ip_skipCheck(CallbackInfo ci) {
        ci.cancel();
    }

    @Inject(
        method = "applySwap",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private static void ip_skipRebuild(String dimension, boolean fastPath, CallbackInfo ci) {
        if (!ip_logged) {
            ip_logged = true;
            ip_LOGGER.info("[IPEuphoria] skipped Euphoria Patcher's pipeline rebuild for {}", dimension);
        }
        ci.cancel();
    }
}
