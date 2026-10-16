/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.UUID;

/**
 * Client-only lookups for {@link IPShipPortalDriver}, kept apart so a dedicated server never loads client classes.
 */
final class IPShipPortalsClientAccess {
    private IPShipPortalsClientAccess() {
    }

    @Nullable
    static Pose3dc carrierPose(final ResourceKey<Level> dimension, final UUID carrier, final boolean ticking) {
        if (!ClientWorldLoader.getIsInitialized()) {
            return null;
        }
        final ClientLevel level = ClientWorldLoader.getOptionalWorld(dimension);
        final SubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
        final SubLevel ship = container == null ? null : container.getSubLevel(carrier);
        return ship == null || ship.isRemoved() ? null : IPShipPortalDriver.clientPose(ship, ticking);
    }
}
