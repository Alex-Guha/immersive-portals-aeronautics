/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipThroughPortalSync;

import java.util.UUID;

/**
 * Create: Pocket Sized (Pym) syncs a ship's scale by UUID; the client stores it whatever level the ship is in. Two
 * server paths assumed the player is in the ship's dimension:
 * <ul>
 *   <li>a scale change ({@code ScaleNetwork.sendScale}) went to the players in the ship's dimension only; here it also
 *   goes to the players the ship is synced to through a portal ({@link IPShipThroughPortalSync#getPortalViewers}), so
 *   a ship resized in another dimension changes size there too;</li>
 *   <li>a client's request for a ship's scale ({@code answerScaleRequest}) looked the ship up in the player's own
 *   level; here, when it is not there, in the level that has it, so the answer carries that level's interpolation
 *   tick.</li>
 * </ul>
 * The first sync of a ship through a portal already carries its scale: Pym hooks Sable's {@code sendFullSync}, which
 * our portal sync calls. Client side: {@link MixinPymScaleHandshake_AllLevels}.
 */
@Mixin(targets = "com.misterblusky9.pym.internal.network.ScaleNetwork", remap = false)
public class MixinPymScaleNetwork_PortalViewers {
    @WrapOperation(method = "sendScale(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;DDZ)V", at = @At(value = "INVOKE",
            target = "Lnet/neoforged/neoforge/network/PacketDistributor;sendToPlayersInDimension(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;[Lnet/minecraft/network/protocol/common/custom/CustomPacketPayload;)V"),
            require = 0)
    private static void ip_alsoToPortalViewers(final ServerLevel level, final CustomPacketPayload payload,
                                               final CustomPacketPayload[] more, final Operation<Void> original,
                                               @Local(argsOnly = true) final ServerSubLevel ship) {
        original.call(level, payload, more);
        if (ship == null || more.length > 0) {
            return;
        }
        final Packet<?> packet = new ClientboundCustomPayloadPacket(payload);
        for (final ServerPlayer player : IPShipThroughPortalSync.getPortalViewers(level.getServer(), ship.getUniqueId())) {
            if (!player.level().dimension().equals(level.dimension())) {
                IPShipThroughPortalSync.sendToViewer(player, level.dimension(), packet);
            }
        }
    }

    @WrapOperation(method = "answerScaleRequest", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;getContainer(Lnet/minecraft/server/level/ServerLevel;)Ldev/ryanhcode/sable/api/sublevel/ServerSubLevelContainer;"),
            require = 0)
    private static ServerSubLevelContainer ip_levelThatHasTheShip(final ServerLevel playerLevel,
                                                                  final Operation<ServerSubLevelContainer> original,
                                                                  @Local(argsOnly = true) final UUID shipId) {
        final ServerSubLevelContainer own = original.call(playerLevel);
        if (shipId == null || (own != null && own.getSubLevel(shipId) != null)) {
            return own;
        }
        for (final ServerLevel level : playerLevel.getServer().getAllLevels()) {
            final ServerSubLevelContainer other = original.call(level);
            if (other != null && other.getSubLevel(shipId) != null) {
                return other;
            }
        }
        return own;
    }
}
