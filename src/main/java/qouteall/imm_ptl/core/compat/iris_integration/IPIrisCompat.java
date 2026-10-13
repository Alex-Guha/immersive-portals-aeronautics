/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.iris_integration;

import net.neoforged.fml.ModList;

/**
 * Safe entry point for querying Iris: {@link #isShaderPackInUse()} returns false unless the
 * {@code iris} mod is loaded (checked once via {@link ModList}), and only then touches the
 * Iris API through {@link IPIrisCompatImpl} so no Iris classes are loaded without Iris.
 */
public final class IPIrisCompat {
    private static boolean checked;
    private static boolean irisPresent;

    private IPIrisCompat() {
    }

    private static boolean isIrisPresent() {
        if (!checked) {
            checked = true;

            try {
                irisPresent = ModList.get().isLoaded("iris");
            } catch (Throwable t) {
                irisPresent = false;
            }
        }

        return irisPresent;
    }

    public static boolean isShaderPackInUse() {
        if (!isIrisPresent()) {
            return false;
        }
        try {
            return IPIrisCompatImpl.isShaderPackInUse();
        } catch (Throwable t) {
            return false;
        }
    }
}
