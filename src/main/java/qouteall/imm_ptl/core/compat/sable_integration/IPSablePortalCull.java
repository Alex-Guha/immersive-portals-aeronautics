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
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import org.slf4j.Logger;

/**
 * Enable gate for portal-side ship culling (called from {@code MixinLevelRenderer_SablePortalCull} on every
 * solid-layer render of a portal-side world). Active only when Sable's {@code SubLevelRenderDispatcher} and
 * Veil's {@code VeilRenderBridge} classes exist and Sodium does not (Sable's Sodium renderer already culls
 * portal-side worlds). Delegates to {@link IPSablePortalCullImpl} and turns itself off on the first error.
 */
public class IPSablePortalCull {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean checked = false;
    private static boolean enabled = false;
    private static boolean logged = false;

    private static boolean isEnabled() {
        if (!checked) {
            checked = true;

            try {
                Class.forName("dev.ryanhcode.sable.sublevel.render.dispatcher.SubLevelRenderDispatcher");
                Class.forName("foundry.veil.api.client.render.VeilRenderBridge");
                boolean sodiumPresent = ModList.get().isLoaded("sodium");
                enabled = !sodiumPresent;
                if (sodiumPresent) {
                    LOGGER.debug(
                        "[IPSablePortalCull] Sodium present - sable uses its Sodium renderer, which already culls portal-side worlds; this fix stays off"
                    );
                }
            } catch (Throwable e) {
                enabled = false;
            }
        }

        return enabled;
    }

    public static void cullForPortalSideWorld(LevelRenderer levelRenderer, ClientLevel world, double camX, double camY, double camZ, Matrix4f modelViewMatrix, Matrix4f projectionMatrix) {
        if (isEnabled()) {
            try {
                boolean culled = IPSablePortalCullImpl.cull(levelRenderer, world, camX, camY, camZ, modelViewMatrix, projectionMatrix);
                if (culled && !logged) {
                    logged = true;
                    LOGGER.debug(
                        "[IPSablePortalCull] driving sable sub-level culling for portal-side world {} (sable vanilla renderer path)",
                        world.dimension().location()
                    );
                }
            } catch (Throwable e) {
                enabled = false;
                LOGGER.debug("[IPSablePortalCull] disabled after error: {}", e.toString());
            }
        }
    }
}
