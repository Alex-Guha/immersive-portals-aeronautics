/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.iris_integration;

import net.irisshaders.iris.api.v0.IrisApi;

/**
 * Iris-touching half of {@link IPIrisCompat}; only loaded when Iris is present.
 * Wraps {@code IrisApi.getInstance().isShaderPackInUse()}.
 */
final class IPIrisCompatImpl {
    private IPIrisCompatImpl() {
    }

    static boolean isShaderPackInUse() {
        return IrisApi.getInstance().isShaderPackInUse();
    }
}
