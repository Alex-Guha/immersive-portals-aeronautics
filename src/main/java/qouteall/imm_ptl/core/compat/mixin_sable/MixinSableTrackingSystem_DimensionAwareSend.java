/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import qouteall.imm_ptl.core.compat.sable_integration.IPSablePortalSync;
import qouteall.imm_ptl.core.network.PacketRedirection;

/**
 * Tags every packet Sable's tracking tick sends with the level it is about.
 * <p>
 * The tick sends a StopTracking packet to a player who just left this level for another dimension
 * ("they are still online, just not in this world"), and full syncs, bounds and movement updates to
 * players in it. IP keeps several client levels alive and routes a packet to the right one only when
 * it is wrapped as a redirect packet; the fork exempts Sable payloads from that wrapper, so such a
 * StopTracking was applied to the player's new dimension and removed whatever ship sat at the same plot
 * coordinate there (ships vanishing and becoming non-interactable after a crossing, "unknown sub-level"
 * errors after every dimension change, other ships' blocks appearing attached). Running the tick under
 * IP's force-redirect for this level wraps everything it sends; the client applies packets for a
 * non-current level through {@code IPSablePortalSyncClient} and the rest through IP's usual path.
 */
@Mixin(value = SubLevelTrackingSystem.class, remap = false)
public class MixinSableTrackingSystem_DimensionAwareSend {
    @Shadow
    @Final
    private ServerLevel level;

    @WrapMethod(method = "tick", require = 0)
    private void ip_sendDimensionTagged(SubLevelContainer container, Operation<Void> original) {
        ServerLevel trackedLevel = this.level;
        if (trackedLevel == null) {
            original.call(container);
            return;
        }
        PacketRedirection.withForceRedirect(trackedLevel, () -> {
            IPSablePortalSync.beginForceRedirect();
            try {
                original.call(container);
            }
            finally {
                IPSablePortalSync.endForceRedirect();
            }
        });
    }

    /**
     * The StopTracking packets Sable sends when a ship is removed (outside the tick: a crossing ship's hand-off) are
     * tagged with this level too. Untagged, a client that changed dimension a moment earlier (walking through the
     * portal with the ship) applied them to its new level, where the ship's new copy had just arrived at the same plot
     * coordinate: the new copy was removed and the old one stayed as a ghost (1.19.11 in game).
     */
    @WrapMethod(method = "onSubLevelRemoved", require = 0)
    private void ip_sendRemovalDimensionTagged(SubLevel subLevel, SubLevelRemovalReason reason, Operation<Void> original) {
        ServerLevel trackedLevel = this.level;
        if (trackedLevel == null) {
            original.call(subLevel, reason);
            return;
        }
        PacketRedirection.withForceRedirect(trackedLevel, () -> {
            IPSablePortalSync.beginForceRedirect();
            try {
                original.call(subLevel, reason);
            }
            finally {
                IPSablePortalSync.endForceRedirect();
            }
        });
    }
}
