/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import net.minecraft.world.entity.Entity;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which entities are seat proxies ({@link IPStraddleSeats}), per side. Free of Sable classes: IP's teleport guards ask it
 * whether or not Sable is installed.
 */
public final class IPSeatProxies {
    /** Marks a proxy entity in the world, so one saved with its chunk is dropped on load. */
    public static final String TAG = "ip_aeronautics_seat_proxy";

    private static final Set<UUID> SERVER = ConcurrentHashMap.newKeySet();
    private static final Set<UUID> CLIENT = ConcurrentHashMap.newKeySet();

    private IPSeatProxies() {
    }

    public static boolean isProxy(final Entity entity) {
        return entity != null && (entity.level().isClientSide() ? CLIENT : SERVER).contains(entity.getUUID());
    }

    static void add(final UUID proxy, final boolean client) {
        (client ? CLIENT : SERVER).add(proxy);
    }

    static void remove(final UUID proxy, final boolean client) {
        (client ? CLIENT : SERVER).remove(proxy);
    }

    static boolean isServerProxy(final UUID proxy) {
        return SERVER.contains(proxy);
    }

    static void clear(final boolean client) {
        (client ? CLIENT : SERVER).clear();
    }
}
