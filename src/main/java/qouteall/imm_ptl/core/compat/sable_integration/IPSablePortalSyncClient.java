/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.network.tcp.SableTCPPacket;
import foundry.veil.api.network.handler.ClientPacketContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.PacketSendListener;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.q_misc_util.dimension.DimensionIntId;

/**
 * Client half of the "show ships through portals" feature (only loaded through {@link IPSablePortalSync}).
 * {@link #handle} applies a redirected {@link SableTCPPacket} destined for a non-current IP client world
 * using a custom Veil {@link ClientPacketContext} whose {@code level()} is that portal-side
 * {@link ClientLevel}; {@link #tickPortalSideWorlds} ticks the {@link ClientSubLevelContainer} of every
 * non-current client world (Sable itself only ticks {@code mc.level}'s container).
 */
final class IPSablePortalSyncClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static boolean appliedLogged;
    private static boolean tickErrorLogged;
    private static boolean inPortalTick;

    private IPSablePortalSyncClient() {
    }

    static boolean handle(int dimId, Packet<?> packet) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || !minecraft.isSameThread()) {
            return false;
        } else if (!(packet instanceof ClientboundCustomPayloadPacket)) {
            return false;
        } else {
            CustomPacketPayload payload = ((ClientboundCustomPayloadPacket) packet).payload();
            if (!(payload instanceof SableTCPPacket)) {
                return false;
            } else {
                LocalPlayer player = minecraft.player;
                if (player == null) {
                    return false;
                } else {
                    ResourceKey<Level> dimension = DimensionIntId.getClientMap().fromIntegerIdNullable(dimId);
                    if (dimension == null) {
                        return false;
                    } else {
                        ClientLevel world = ClientWorldLoader.getOptionalWorld(dimension);
                        if (world == null) {
                            return false;
                        } else if (world == player.level()) {
                            return false;
                        } else {
                            ((SableTCPPacket) payload).handle(new IPSablePortalSyncClient.PortalSideContext(world));
                            if (!appliedLogged) {
                                appliedLogged = true;
                                LOGGER.debug("[IPShipThroughPortal] client is applying sable sub-level data to the portal-side world {}", dimension.location());
                            }

                            return true;
                        }
                    }
                }
            }
        }
    }

    static void tickPortalSideWorlds() {
        if (!inPortalTick) {
            Minecraft minecraft = Minecraft.getInstance();
            if (minecraft != null && minecraft.isSameThread() && !minecraft.isPaused()) {
                LocalPlayer player = minecraft.player;
                // IP's world list is empty from a vanilla dimension change until IP sets it up again
                if (player != null && ClientWorldLoader.getIsInitialized()) {
                    Level currentLevel = player.level();
                    inPortalTick = true;

                    try {
                        for (ClientLevel world : ClientWorldLoader.getClientWorlds()) {
                            if (world != null && world != currentLevel) {
                                try {
                                    ClientSubLevelContainer container = SubLevelContainer.getContainer(world);
                                    if (container != null && !container.getAllSubLevels().isEmpty()) {
                                        container.tick();
                                    }
                                } catch (Throwable e) {
                                    if (!tickErrorLogged) {
                                        tickErrorLogged = true;
                                        LOGGER.warn("[IPShipThroughPortal] portal-side container tick failed: {}", e.toString());
                                    }
                                }
                            }
                        }
                    } finally {
                        inPortalTick = false;
                    }
                }
            }
        }
    }

    /**
     * Veil packet context whose {@code level()} is the portal-side world. Note {@link #player()} returns the
     * real local player, whose level differs from {@link #level()}.
     */
    private static final class PortalSideContext implements ClientPacketContext {
        private final ClientLevel target;

        PortalSideContext(ClientLevel target) {
            this.target = target;
        }

        @Override
        public Minecraft client() {
            return Minecraft.getInstance();
        }

        @Override
        public LocalPlayer player() {
            return Minecraft.getInstance().player;
        }

        @Override
        public Level level() {
            return this.target;
        }

        @Override
        public Packet<?> createPacket(CustomPacketPayload payload) {
            return new ServerboundCustomPayloadPacket(payload);
        }

        @Override
        public void sendPacket(Packet<?> packet, PacketSendListener listener) {
            ClientPacketListener connection = Minecraft.getInstance().getConnection();
            if (connection != null) {
                connection.send(packet);
            }
        }

        @Override
        public void disconnect(Component reason) {
        }
    }
}
