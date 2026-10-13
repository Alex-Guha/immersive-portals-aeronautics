/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_physicsmod;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.logging.LogUtils;
import net.diebuddies.compat.Iris;
import net.diebuddies.render.MainRenderer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.RenderType;
import org.joml.Matrix4f;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.physicsmod_integration.IPPhysicsOceanImpl;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Physics Mod renderer guard: cancels {@code MainRenderer.render/renderAll/renderShadows} while a
 * portal is being rendered (the main view re-issues them via {@code IPPhysicsOceanImpl}), and wraps
 * {@code renderLiquid}/{@code renderCloth} to catch Throwables caused by stale Iris shaders left over
 * from a pipeline rebuild, nulling out Physics Mod's cached Iris shaders and IP's own shader cache.
 */
@Mixin(value = MainRenderer.class, remap = false)
public class MixinPhysicsModMainRenderer {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Unique
    private static boolean ip_loggedLiquidGuard;

    @Unique
    private static boolean ip_loggedBound;

    @Inject(
        method = {"render", "renderAll", "renderShadows"},
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        expect = 0
    )
    private void ip_skipDuringPortalRendering(CallbackInfo ci) {
        if (PortalRendering.isRendering()) {
            ci.cancel();
        }
    }

    @WrapMethod(method = "renderLiquid", require = 0)
    private void ip_guardLiquidRendering(
        ClientLevel level, RenderType renderType, double camX, double camY, double camZ,
        Matrix4f modelView, Matrix4f projection, Operation<Void> original
    ) {
        if (!ip_loggedBound) {
            ip_loggedBound = true;
            ip_LOGGER.debug("[IPPhysicsLiquidGuard] active on Physics Mod liquid/cloth passes");
        }

        try {
            original.call(level, renderType, camX, camY, camZ, modelView, projection);
        } catch (Throwable t) {
            ip_recoverFromStaleShader(t);
        }
    }

    @WrapMethod(method = "renderCloth", require = 0)
    private void ip_guardClothRendering(
        ClientLevel level, RenderType renderType, double camX, double camY, double camZ,
        Matrix4f modelView, Matrix4f projection, Operation<Void> original
    ) {
        try {
            original.call(level, renderType, camX, camY, camZ, modelView, projection);
        } catch (Throwable t) {
            ip_recoverFromStaleShader(t);
        }
    }

    @Unique
    private static void ip_recoverFromStaleShader(Throwable cause) {
        try {
            // Physics Mod 3.0.34: the static Iris.{ocean,oceanShadow,liquid,liquidShadow}Shader
            // fields the fork nulled out no longer exist; ask Physics Mod to rebuild them instead.
            Iris.reloadOceanShaders();
        } catch (Throwable ignored) {
        }

        // Same mod, direct call: this mixin only applies when Physics Mod's MainRenderer exists,
        // and IPPhysicsOceanImpl only references Physics Mod classes, so loading it here is safe.
        IPPhysicsOceanImpl.forgetCachedOceanShaders();

        if (!ip_loggedLiquidGuard) {
            ip_loggedLiquidGuard = true;
            ip_LOGGER.debug(
                "[IPPhysicsLiquidGuard] Physics Mod liquid/cloth pass threw ({}: {}) - almost certainly a shader left over from an Iris pipeline that Immersive Portals caused to be rebuilt. Cached shaders dropped; Physics Mod will rebuild them. Further occurrences are silent.",
                cause.getClass().getSimpleName(), cause.getMessage()
            );
        }
    }
}
