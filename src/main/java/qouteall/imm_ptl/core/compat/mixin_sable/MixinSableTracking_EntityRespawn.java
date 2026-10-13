/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipEntityRespawn;

/**
 * After Sable's {@code SubLevelTrackingSystem.sendFullSync} sends a ship to a player, calls
 * {@link IPShipEntityRespawn#onFullSync} so IP's entity tracker re-sends the entities that
 * live on that ship (they would otherwise be missing on the client after a resync).
 */
@Mixin(
    value = SubLevelTrackingSystem.class,
    remap = false
)
public class MixinSableTracking_EntityRespawn {
    @Shadow
    @Final
    private ServerLevel level;

    @Inject(
        method = "sendFullSync",
        at = @At("TAIL"),
        require = 0
    )
    private void ip_respawnShipEntities(
        ServerPlayer player, ServerSubLevel subLevel, @Nullable CustomPacketPayload extraPacket, CallbackInfo ci
    ) {
        try {
            IPShipEntityRespawn.onFullSync(this.level, subLevel);
        } catch (Throwable ignored) {
        }
    }
}
