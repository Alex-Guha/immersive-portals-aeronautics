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
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.server.ServerAboutToStartEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import org.slf4j.Logger;

/**
 * Facade/guard for ship-through-portal traversal, called from {@code ServerTeleportationManager.tick}.
 * Checks {@code ModList.isLoaded("sable")} once and delegates to {@link IPShipPortalTraversalImpl#tick}. Every server
 * starts afresh ({@link IPShipPortalTraversalImpl#clearState}, before its levels load and again when it stops: a
 * singleplayer game runs one server per world load in the same JVM), and its first tick replays the teleport journal.
 * A Throwable never reaches the server (Sable class loading failures among them): it is logged and the next tick tries
 * again.
 */
public final class IPShipPortalTraversal {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean sableLoaded;
    private static boolean checkedSable;
    private static boolean errorLogged;
    /** The server whose first tick has run (weak, so a closed singleplayer server is not kept alive). */
    private static WeakReference<MinecraftServer> currentServer = new WeakReference<>(null);

    private IPShipPortalTraversal() {
    }

    public static void init() {
        NeoForge.EVENT_BUS.addListener(ServerAboutToStartEvent.class, event -> clearState());
        NeoForge.EVENT_BUS.addListener(ServerStoppedEvent.class, event -> clearState());
        NeoForge.EVENT_BUS.addListener(PlayerEvent.PlayerLoggedInEvent.class, event -> {
            if (isSableLoaded() && event.getEntity() instanceof ServerPlayer player) {
                IPShipPortalTraversalImpl.onPlayerJoined(player);
            }
        });
    }

    private static boolean isSableLoaded() {
        if (!checkedSable) {
            checkedSable = true;
            sableLoaded = ModList.get().isLoaded("sable");
            if (!sableLoaded) {
                LOGGER.debug("[IPShipPortal] sable not present; ship portal traversal disabled");
            }
        }
        return sableLoaded;
    }

    private static void clearState() {
        try {
            if (isSableLoaded()) {
                errorLogged = false;
                IPShipPortalTraversalImpl.clearState();
            }
        } catch (Throwable e) {
            LOGGER.error("[IPShipPortal] clearing the ship state failed: {}", e.toString(), e);
        }
    }

    public static void tick(MinecraftServer server) {
        try {
            if (!isSableLoaded()) {
                return;
            }

            if (currentServer.get() != server) {
                currentServer = new WeakReference<>(server);
                IPShipPortalTraversalImpl.onServerStarted(server);
            }

            IPShipPortalTraversalImpl.tick(server);
        } catch (Throwable e) {
            if (!errorLogged) {
                errorLogged = true;
                LOGGER.error("[IPShipPortal] ship tick failed (logged once per server): {}", e.toString(), e);
            }
        }
    }
}
