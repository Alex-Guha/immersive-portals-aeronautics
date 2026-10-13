/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipRopeCompat;

/**
 * After Sable sends a ship's full sync to a player (its own tracking, or IP's through-portal sync, which calls
 * the same method), has the ropes attached to that ship re-sent to the player
 * ({@link IPShipRopeCompat#resendRopesAfterFullSync}): the sync re-creates the ship's block entities on the
 * client, dropping the rope strands they held.
 */
@Mixin(value = SubLevelTrackingSystem.class, remap = false)
public class MixinSableFullSync_RopeResend {
    @Inject(method = "sendFullSync", at = @At("TAIL"), require = 0)
    private void ip_resendRopes(ServerPlayer player, ServerSubLevel subLevel, CustomPacketPayload extraPacket, CallbackInfo ci) {
        if (subLevel.getLevel() instanceof ServerLevel level) {
            IPShipRopeCompat.resendRopesAfterFullSync(level, subLevel, player);
        }
    }
}
