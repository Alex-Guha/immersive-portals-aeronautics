/*
 * Copied from Sable 2.0.6 by RyanHCode (https://github.com/ryanhcode/sable), sable_rapier module.
 * Licensed under the PolyForm Shield License 1.0.0 (https://polyformproject.org/licenses/shield/1.0.0),
 * as are this project's changes to it. Modified for Immersive Portals: Aeronautics, 2026:
 * package moved to ipaeronautics.rapier.
 */
package ipaeronautics.rapier;

import org.jetbrains.annotations.ApiStatus;

/**
 * A physics scene, stored natively in {@link Rapier3D}
 */
@ApiStatus.Internal
public final class RapierPhysicsScene {
    private final long handle;

    RapierPhysicsScene(final long handle) {
        if (handle == 0L) {
            throw new IllegalArgumentException("invalid scene handle");
        }
        this.handle = handle;
    }

    long handle() {
        return this.handle;
    }
}
