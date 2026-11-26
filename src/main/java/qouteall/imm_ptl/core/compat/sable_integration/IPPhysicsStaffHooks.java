/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.List;
import java.util.UUID;

/**
 * Entry points of tools that act on ships across portals for IP's and our own code: Create Aeronautics' (Simulated)
 * physics staff ({@link IPPhysicsStaffPortals}, {@link IPPhysicsStaffPortalsClient}) and handles
 * ({@link IPHandlePortals}, {@link IPHandlePortalsClient}), which do nothing unless Simulated and Sable are loaded, and
 * Let It Walk's pilot controls ({@link IPWalkerPilot}, {@link IPWalkerPilotClient}), unless Let It Walk is.
 */
public final class IPPhysicsStaffHooks {
    private static final boolean ACTIVE = ModList.get().isLoaded("simulated") && ModList.get().isLoaded("sable");

    private IPPhysicsStaffHooks() {
    }

    public static void clearState() {
        IPWalkerPilot.reset();
        if (ACTIVE) {
            IPPhysicsStaffPortals.reset();
            IPHandlePortals.reset();
        }
    }

    public static void onServerPlayerTeleported(ServerPlayer player, ResourceKey<Level> from, Portal portal) {
        IPWalkerPilot.onPlayerTeleported(player, from, portal);
        if (ACTIVE) {
            IPPhysicsStaffPortals.onPlayerTeleported(player, from, portal);
            IPHandlePortals.onPlayerTeleported(player, from, portal);
        }
    }

    public static void onClientPlayerTeleported(Portal portal) {
        if (IPWalkerPilot.isActive()) {
            IPWalkerPilotClient.onPlayerTeleported(portal);
        }
        if (ACTIVE) {
            IPPhysicsStaffPortalsClient.onPlayerTeleported(portal);
            IPHandlePortalsClient.onPlayerTeleported(portal);
        }
    }

    public static void onShipMoved(ServerLevel src, ServerLevel dst, UUID ship, Portal portal, IPSableBridge.Moved moved) {
        // players carried through with the ship went through its portal too, without IP's teleport and its hooks: a
        // drag kept in the old frame pulled the dragged ship a whole portal's offset away (into the void, under a stack)
        final List<ServerPlayer> carried = IPShipRiderCarry.takeCarriedPlayers();
        // and without its scaling: a player seated on a ship through a portal that changes size kept theirs
        final double factor = IPShipRiderCarry.scaleFactor(portal);
        for (final ServerPlayer player : carried) {
            IPShipRiderCarry.scaleThrough(player, factor);
        }
        for (final ServerPlayer player : carried) {
            onServerPlayerTeleported(player, src.dimension(), portal);
            IPWalkerPilot.tellCarried(player, portal);
            if (ACTIVE) {
                IPPhysicsStaffPortals.tellCarried(player, portal);
            }
        }
        IPWalkerPilot.onShipMoved(src, dst, ship, portal, moved);
        if (ACTIVE) {
            IPPhysicsStaffPortals.onShipMoved(src, dst, ship, portal, moved);
            IPHandlePortals.onShipMoved(src, dst, ship, portal, moved);
        }
    }
}
