/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import java.util.UUID;

/**
 * The client's side of {@link IPSeatProxies}, for {@code client.IPStraddleSeatsClient}.
 */
public final class IPSeatProxiesClientAccess {
    private IPSeatProxiesClientAccess() {
    }

    public static void add(final UUID proxy) {
        IPSeatProxies.add(proxy, true);
    }

    public static void remove(final UUID proxy) {
        IPSeatProxies.remove(proxy, true);
    }
}
