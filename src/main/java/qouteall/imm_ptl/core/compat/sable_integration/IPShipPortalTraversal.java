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
import java.lang.ref.WeakReference;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Facade/guard for ship-through-portal traversal, called from {@code ServerTeleportationManager.tick}.
 * Checks {@code ModList.isLoaded("sable")} once, starts every server afresh ({@link IPShipPortalTraversalImpl#onServerStarted}:
 * per-ship state is reset, since a singleplayer game runs one server per world load in the same JVM, and the
 * teleport journal is replayed) and then delegates to {@link IPShipPortalTraversalImpl#tick}. Disables itself
 * forever on the first Throwable so Sable class loading failures cannot take the server down.
 */
public final class IPShipPortalTraversal {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean sableLoaded;
    private static boolean checkedSable;
    private static boolean errorLogged;
    private static boolean activeLogged;
    /** The server the per-ship state belongs to (weak, so a closed singleplayer server is not kept alive). */
    private static WeakReference<MinecraftServer> currentServer = new WeakReference<>(null);

    private IPShipPortalTraversal() {
    }

    public static void tick(MinecraftServer server) {
        try {
            if (!checkedSable) {
                checkedSable = true;
                sableLoaded = ModList.get().isLoaded("sable");
                if (!sableLoaded) {
                    LOGGER.debug("[IPShipPortal] sable not present; ship portal traversal disabled");
                }
            }

            if (!sableLoaded) {
                return;
            }

            if (!activeLogged) {
                activeLogged = true;
                LOGGER.debug("[IPShipPortal] active - sable ships will traverse IP portals");
            }

            if (currentServer.get() != server) {
                currentServer = new WeakReference<>(server);
                IPShipPortalTraversalImpl.onServerStarted(server);
            }

            IPShipPortalTraversalImpl.tick(server);
        } catch (Throwable e) {
            if (!errorLogged) {
                errorLogged = true;
                LOGGER.error("[IPShipPortal] DISABLED by error: {}", e.toString(), e);
            }
        }
    }
}
