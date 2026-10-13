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
import net.minecraft.network.protocol.Packet;
import org.slf4j.Logger;

/**
 * Server/common-safe facade for the client half of the "show ships through portals" feature. Owns the
 * {@code FORCE_REDIRECT} ThreadLocal (opt-in to IP's dimension redirect for Sable packets, see
 * {@code IPSableCompatHelper.isDimensionAgnosticPacket}) and wraps {@link IPSablePortalSyncClient} calls,
 * latching off permanently on a {@link LinkageError} / "invalid dist" (dedicated server).
 */
public final class IPSablePortalSync {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final ThreadLocal<Boolean> FORCE_REDIRECT = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static boolean clientErrorLogged;
    private static volatile boolean clientHalfAbsent;

    private IPSablePortalSync() {
    }

    private static boolean isAbsent(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof LinkageError) {
                return true;
            }

            String message = cause.getMessage();
            if (message != null && message.contains("invalid dist")) {
                return true;
            }

            if (cause.getCause() == cause) {
                break;
            }
        }

        return false;
    }

    private static boolean noteFailure(Throwable error, String feature) {
        if (isAbsent(error)) {
            clientHalfAbsent = true;
            LOGGER.debug(
                "[IPShipThroughPortal] client half absent on this side (normal on a dedicated server); {} disabled for this session: {}",
                feature,
                error.toString()
            );
            return true;
        } else {
            return false;
        }
    }

    public static void beginForceRedirect() {
        FORCE_REDIRECT.set(Boolean.TRUE);
    }

    public static void endForceRedirect() {
        FORCE_REDIRECT.set(Boolean.FALSE);
    }

    public static boolean isForceRedirect() {
        return FORCE_REDIRECT.get();
    }

    public static void tickPortalSideWorlds() {
        if (!clientHalfAbsent) {
            try {
                IPSablePortalSyncClient.tickPortalSideWorlds();
            } catch (Throwable e) {
                if (noteFailure(e, "portal-side craft animation")) {
                    return;
                }

                if (!clientErrorLogged) {
                    clientErrorLogged = true;
                    LOGGER.warn("[IPShipThroughPortal] portal-side tick unavailable; craft will not animate through portals: {}", e.toString());
                }
            }
        }
    }

    public static boolean handleClientRedirected(int dimId, Packet<?> packet) {
        if (clientHalfAbsent) {
            return false;
        } else {
            try {
                return IPSablePortalSyncClient.handle(dimId, packet);
            } catch (Throwable e) {
                if (noteFailure(e, "cross-portal craft display")) {
                    return false;
                } else {
                    if (!clientErrorLogged) {
                        clientErrorLogged = true;
                        LOGGER.error("[IPShipThroughPortal] client-side apply failed; craft will not show through portals: {}", e.toString(), e);
                    }

                    return false;
                }
            }
        }
    }
}
