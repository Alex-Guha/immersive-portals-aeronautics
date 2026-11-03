/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
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
 * Entry points of Create Aeronautics (Simulated) tools across portals for IP's and our own code: the physics staff
 * ({@link IPPhysicsStaffPortals}, {@link IPPhysicsStaffPortalsClient}) and handles ({@link IPHandlePortals},
 * {@link IPHandlePortalsClient}). They do nothing unless Simulated and Sable are loaded.
 */
public final class IPPhysicsStaffHooks {
    private static final boolean ACTIVE = ModList.get().isLoaded("simulated") && ModList.get().isLoaded("sable");

    private IPPhysicsStaffHooks() {
    }

    public static void clearState() {
        if (ACTIVE) {
            IPPhysicsStaffPortals.reset();
            IPHandlePortals.reset();
        }
    }

    public static void onServerPlayerTeleported(ServerPlayer player, ResourceKey<Level> from, Portal portal) {
        if (ACTIVE) {
            IPPhysicsStaffPortals.onPlayerTeleported(player, from, portal);
            IPHandlePortals.onPlayerTeleported(player, from, portal);
        }
    }

    public static void onClientPlayerTeleported(Portal portal) {
        if (ACTIVE) {
            IPPhysicsStaffPortalsClient.onPlayerTeleported(portal);
            IPHandlePortalsClient.onPlayerTeleported(portal);
        }
    }

    public static void onShipMoved(ServerLevel src, ServerLevel dst, UUID ship, Portal portal, IPSableBridge.Moved moved) {
        // players carried through with the ship went through its portal too, without IP's teleport and its hooks: a
        // drag kept in the old frame pulled the dragged ship a whole portal's offset away (into the void, under a stack)
        final List<ServerPlayer> carried = IPShipRiderCarry.takeCarriedPlayers();
        if (ACTIVE) {
            for (final ServerPlayer player : carried) {
                onServerPlayerTeleported(player, src.dimension(), portal);
                IPPhysicsStaffPortals.tellCarried(player, portal);
            }
            IPPhysicsStaffPortals.onShipMoved(src, dst, ship, portal, moved);
            IPHandlePortals.onShipMoved(src, dst, ship, portal, moved);
        }
    }
}
