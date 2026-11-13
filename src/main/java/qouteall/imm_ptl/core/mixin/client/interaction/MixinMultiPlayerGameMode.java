/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0), with changes from
 * Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code and ItsSoBread's changes stay under the
 * Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.mixin.client.interaction;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundUseItemOnPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer;
import qouteall.imm_ptl.core.ducks.IEClientPlayerInteractionManager;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import qouteall.imm_ptl.core.block_manipulation.BlockManipulationClient;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Unique;

@Mixin(MultiPlayerGameMode.class)
public abstract class MixinMultiPlayerGameMode implements IEClientPlayerInteractionManager {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();
    @Shadow
    @Final
    private ClientPacketListener connection;
    
    @Shadow
    @Final
    private Minecraft minecraft;
    
    // the player level field is not being switched now
    @Redirect(
        method = "lambda$startDestroyBlock$1", // lambda in startDestroyBlock
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;level()Lnet/minecraft/world/level/Level;"
        )
    )
    private Level redirectPlayerLevel1(LocalPlayer instance) {
        return Minecraft.getInstance().level;
    }
    
    // the player level field is not being switched now
    @Redirect(
        method = "continueDestroyBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/player/LocalPlayer;level()Lnet/minecraft/world/level/Level;"
        )
    )
    private Level redirectPlayerLevel2(LocalPlayer instance) {
        return Minecraft.getInstance().level;
    }
    
    // use another constructor that does not use player level
    @Redirect(
        method = "performUseItemOn",
        at = @At(
            value = "NEW",
            target = "(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/InteractionHand;Lnet/minecraft/world/phys/BlockHitResult;)Lnet/minecraft/world/item/context/UseOnContext;"
        )
    )
    private UseOnContext redirectNewUseOnContext(Player player, InteractionHand interactionHand, BlockHitResult blockHitResult) {
        return new UseOnContext(
            Minecraft.getInstance().level,
            player,
            interactionHand,
            player.getItemInHand(interactionHand),
            blockHitResult
        );
    }
    
    /**
     * Immersive Aeronautics: the hit sound of a block broken through a portal (IP switched the client to its world) is
     * played by that world, so IP's cross-portal sound places it at the portal (vanilla hands it to the sound manager at
     * the block's coordinates, which belong to the other world; on a Sable ship they are plot coordinates).
     */
    @com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation(
        method = "continueDestroyBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/sounds/SoundManager;play(Lnet/minecraft/client/resources/sounds/SoundInstance;)V"
        ),
        require = 0
    )
    private void ip_playHitSoundInItsWorld(
        net.minecraft.client.sounds.SoundManager soundManager, net.minecraft.client.resources.sounds.SoundInstance sound,
        com.llamalad7.mixinextras.injector.wrapoperation.Operation<Void> original,
        @com.llamalad7.mixinextras.sugar.Local(argsOnly = true) net.minecraft.core.BlockPos pos,
        @com.llamalad7.mixinextras.sugar.Local net.minecraft.world.level.block.SoundType soundType
    ) {
        net.minecraft.client.multiplayer.ClientLevel world = minecraft.level;
        if (world != null && minecraft.player != null && world != minecraft.player.level()) {
            world.playLocalSound(
                pos, soundType.getHitSound(), net.minecraft.sounds.SoundSource.BLOCKS,
                (soundType.getVolume() + 1.0F) / 8.0F, soundType.getPitch() * 0.5F, false
            );
            return;
        }
        original.call(soundManager, sound);
    }
    
    @ModifyArg(
        method = "startPrediction",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private Packet<?> modifyPacketInStartPrediction(Packet<?> packet) {
        return ip_redirectPacket(packet);
    }
    
    @ModifyArg(
        method = "startDestroyBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private Packet redirectSendInStartDestroyBlock(Packet packet) {
        return ip_redirectPacket(packet);
    }
    
    @ModifyArg(
        method = "stopDestroyBlock",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/multiplayer/ClientPacketListener;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private Packet redirectSendInStopDestroyBlock(Packet packet) {
        return ip_redirectPacket(packet);
    }
    
    private static Packet<?> ip_redirectPacket(Packet<?> packet) {
        // Immersive Aeronautics: block interaction packets go through un-redirected unless the
        // player is pointing at a portal (fixes breaking/placing blocks on Sable ships)
        if ((packet instanceof ServerboundPlayerActionPacket || packet instanceof ServerboundUseItemOnPacket)
            && !BlockManipulationClient.isPointingToPortal()
        ) {
            if (ClientWorldLoader.getIsWorldSwitched()) {
                ip_LOGGER.debug("[IPShipBreakFix] passthrough interaction packet (worldSwitched, not pointing at portal)");
            }
            return packet;
        }
        
        if (ClientWorldLoader.getIsWorldSwitched()) {
            ResourceKey<Level> dimension = Minecraft.getInstance().level.dimension();
            if (packet instanceof ServerboundPlayerActionPacket playerActionPacket) {
                if (BlockManipulationServer.isAttackingAction(playerActionPacket.getAction())) {
                    FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                    ServerboundPlayerActionPacket.STREAM_CODEC.encode(buf, playerActionPacket);

                    return new ServerboundCustomPayloadPacket(McRemoteProcedureCall.createPacketToSendToServer(
                        "qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer.RemoteCallables.processPlayerActionPacket",
                        dimension,
                        IPMcHelper.bufToBytes(buf)
                    ));
                }
            }
            else if (packet instanceof ServerboundUseItemOnPacket useItemOnPacket) {
                FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
                ServerboundUseItemOnPacket.STREAM_CODEC.encode(buf, useItemOnPacket);

                return new ServerboundCustomPayloadPacket(McRemoteProcedureCall.createPacketToSendToServer(
                    "qouteall.imm_ptl.core.block_manipulation.BlockManipulationServer.RemoteCallables.processUseItemOnPacket",
                    dimension,
                    IPMcHelper.bufToBytes(buf)
                ));
            }
            // ServerboundUseItemPacket is not redirected
        }
        
        return packet;
    }
    
}
