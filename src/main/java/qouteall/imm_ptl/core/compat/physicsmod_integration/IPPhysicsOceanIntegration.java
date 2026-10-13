/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.physicsmod_integration;

import com.mojang.logging.LogUtils;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Client entry point for the Physics Mod ocean integration (called from {@code IPModEntryClient}).
 * Checks that {@code physicsmod} is loaded and then binds {@link IPPhysicsOceanImpl}; any failure
 * (for example a Physics Mod update changing internals) is logged and the integration stays off.
 */
public final class IPPhysicsOceanIntegration {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String PHYSICS_MOD_ID = "physicsmod";
    private static boolean initialized = false;

    private IPPhysicsOceanIntegration() {
    }

    public static void init() {
        if (initialized) {
            return;
        }
        initialized = true;

        try {
            if (!ModList.get().isLoaded(PHYSICS_MOD_ID)) {
                return;
            }
        } catch (Throwable e) {
            return;
        }

        try {
            IPPhysicsOceanImpl.register();
        } catch (Throwable e) {
            LOGGER.debug(
                "[IPPhysicsOcean] Physics Mod detected but ocean integration could not bind (likely a Physics Mod update changed internals): {}",
                String.valueOf(e)
            );
        }
    }
}
