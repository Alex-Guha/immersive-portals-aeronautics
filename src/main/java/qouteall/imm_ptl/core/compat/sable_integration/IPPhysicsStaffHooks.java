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

import java.util.UUID;

/**
 * Entry points of the physics staff across portals ({@link IPPhysicsStaffPortals}, {@link IPPhysicsStaffPortalsClient})
 * for IP's and our own code; they do nothing unless Create Aeronautics (Simulated) and Sable are loaded.
 */
public final class IPPhysicsStaffHooks {
    private static final boolean ACTIVE = ModList.get().isLoaded("simulated") && ModList.get().isLoaded("sable");

    private IPPhysicsStaffHooks() {
    }

    public static void onServerStarted() {
        if (ACTIVE) {
            IPPhysicsStaffPortals.reset();
        }
    }

    public static void onServerPlayerTeleported(ServerPlayer player, ResourceKey<Level> from, Portal portal) {
        if (ACTIVE) {
            IPPhysicsStaffPortals.onPlayerTeleported(player, from, portal);
        }
    }

    public static void onClientPlayerTeleported(Portal portal) {
        if (ACTIVE) {
            IPPhysicsStaffPortalsClient.onPlayerTeleported(portal);
        }
    }

    public static void onShipMoved(ServerLevel src, ServerLevel dst, UUID ship, Portal portal, IPSableBridge.Moved moved) {
        if (ACTIVE) {
            IPPhysicsStaffPortals.onShipMoved(src, dst, ship, portal, moved);
        }
    }
}
