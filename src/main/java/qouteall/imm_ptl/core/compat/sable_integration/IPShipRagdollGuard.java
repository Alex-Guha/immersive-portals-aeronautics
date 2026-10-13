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
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Optional hook for the {@code sable_player_ragdoll} mod: players flagged via {@link #suppressFor} have their
 * ragdoll start event vetoed for {@value #SUPPRESS_TICKS} ticks while being carried through a portal, and
 * {@link #isRagdollSubLevel} lets traversal/sync skip ragdoll sub-levels. All ragdoll classes are isolated in
 * {@link IPShipRagdollGuardImpl}; everything is a no-op when the mod is absent.
 */
public final class IPShipRagdollGuard {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final long SUPPRESS_TICKS = 100L;
    private static final Map<UUID, Long> SUPPRESS_UNTIL = new HashMap<>();
    private static boolean registered;
    private static boolean checked;
    private static boolean modPresent;

    private IPShipRagdollGuard() {
    }

    public static void tryRegister() {
        if (!registered) {
            registered = true;

            try {
                if (!isModPresent()) {
                    return;
                }

                IPShipRagdollGuardImpl.register();
                LOGGER.debug("[IPShipRagdoll] ragdoll suppression active during ship portal crossings");
            } catch (Throwable e) {
                LOGGER.warn("[IPShipRagdoll] could not hook ragdoll events: {}", e.toString());
            }
        }
    }

    private static boolean isModPresent() {
        if (!checked) {
            checked = true;

            try {
                modPresent = ModList.get().isLoaded("sable_player_ragdoll");
            } catch (Throwable e) {
                modPresent = false;
            }
        }

        return modPresent;
    }

    public static boolean isRagdollSubLevel(Object subLevel) {
        if (!isModPresent()) {
            return false;
        } else {
            try {
                return IPShipRagdollGuardImpl.isRagdollSubLevel(subLevel);
            } catch (Throwable e) {
                return false;
            }
        }
    }

    public static void suppressFor(UUID playerId, long nowTick) {
        if (isModPresent()) {
            synchronized (SUPPRESS_UNTIL) {
                SUPPRESS_UNTIL.put(playerId, nowTick + SUPPRESS_TICKS);
            }
        }
    }

    /** Drops the suppressions of a previous server (they hold its tick counts). */
    static void reset() {
        synchronized (SUPPRESS_UNTIL) {
            SUPPRESS_UNTIL.clear();
        }
    }

    public static boolean isSuppressed(UUID playerId, long nowTick) {
        synchronized (SUPPRESS_UNTIL) {
            if (SUPPRESS_UNTIL.isEmpty()) {
                return false;
            } else {
                SUPPRESS_UNTIL.values().removeIf(until -> until < nowTick);
                Long until = SUPPRESS_UNTIL.get(playerId);
                return until != null && until >= nowTick;
            }
        }
    }
}
