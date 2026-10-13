/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.v2.WrapWithCondition;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipThroughPortalSync;

import java.util.UUID;

/**
 * Ships stay on the client while a player walks through a portal (docs/lessons/ship-sync.md: every ship blinked). In
 * Sable's tracking tick:
 * <ul>
 *     <li>a player who left this level stops tracking its ships; a ship they still see through a portal is taken over
 *     by {@link IPShipThroughPortalSync} instead of being removed on the client (and re-sent at the next rescan);</li>
 *     <li>a player who arrived starts tracking this level's ships; one their client already has through a portal is
 *     not sent again (a full sync re-sends every plot chunk and rebuilds the ship's render data).</li>
 * </ul>
 */
@Mixin(value = SubLevelTrackingSystem.class, remap = false)
public abstract class MixinSableTrackingSystem_PortalHandover {
    @Shadow
    @Final
    private ServerLevel level;

    @WrapWithCondition(method = "tick", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem;sendRemoval(Lfoundry/veil/api/network/VeilPacketManager$PacketSink;Ldev/ryanhcode/sable/sublevel/ServerSubLevel;)V",
            ordinal = 0), require = 0)
    private boolean ip_keepShipInPortalView(final SubLevelTrackingSystem self, final VeilPacketManager.PacketSink sink,
                                            final ServerSubLevel sub, @Local final UUID uuid) {
        final ServerPlayer player = this.level.getServer().getPlayerList().getPlayer(uuid);
        return player == null || !IPShipThroughPortalSync.adoptFromSable(player, this.level, sub);
    }

    @WrapWithCondition(method = "tick", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/system/SubLevelTrackingSystem;sendFullSync(Lnet/minecraft/server/level/ServerPlayer;Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V",
            ordinal = 1), require = 0)
    private boolean ip_skipShipAlreadyInPortalView(final SubLevelTrackingSystem self, final ServerPlayer player,
                                                   final ServerSubLevel sub, final CustomPacketPayload extraPacket) {
        return !IPShipThroughPortalSync.handOverToSable(player, this.level, sub);
    }
}
