/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.network;

import com.mojang.authlib.GameProfile;
import com.mojang.logging.LogUtils;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.network.ConfigurationTask;
import net.minecraft.server.network.ServerConfigurationPacketListenerImpl;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.client.event.ClientPlayerNetworkEvent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.network.configuration.ICustomConfigurationTask;
import net.neoforged.neoforge.network.event.RegisterConfigurationTasksEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPMcHelper;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.mixin.common.other_sync.IEServerConfigurationPacketListenerImpl;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.platform_specific.O_O;

import java.util.function.Consumer;

public class ImmPtlNetworkConfig {
    private static final Logger LOGGER = LogUtils.getLogger();
    
    public static record ModVersion(
        int major, int minor, int patch
    ) {
        // for dev env
        public static final ModVersion OTHER = new ModVersion(0, 0, 0);
        
        /**
         * Immersive Aeronautics: the oldest version this build can play with. Raise it to the next version whenever a
         * payload, an RPC or what one side expects of the other changes, patch releases included. Only the newer side
         * knows, so both sides check: the server in {@link C2SConfigCompletePacket#handle}, the client in
         * {@link S2CConfigStartPacket#handle}.
         */
        public static final ModVersion OLDEST_COMPATIBLE = new ModVersion(2, 7, 12);
        
        /** What every build before 2.7.9 sent, whatever its real version. */
        private static final ModVersion BEFORE_THE_CHECK = new ModVersion(1, 0, 0);
        
        public static ModVersion read(FriendlyByteBuf buf) {
            int major = buf.readVarInt();
            int minor = buf.readVarInt();
            int patch = buf.readVarInt();
            return new ModVersion(major, minor, patch);
        }
        
        public void write(FriendlyByteBuf buf) {
            buf.writeVarInt(major);
            buf.writeVarInt(minor);
            buf.writeVarInt(patch);
        }
        
        @Override
        public String toString() {
            return "%d.%d.%d".formatted(major, minor, patch);
        }
        
        public boolean isNormalVersion() {
            return !OTHER.equals(this);
        }
        
        public boolean isCompatibleWith(ModVersion another) {
            return equals(another) || !another.isOlderThan(OLDEST_COMPATIBLE);
        }
        
        private boolean isOlderThan(ModVersion another) {
            return major != another.major ? major < another.major
                : minor != another.minor ? minor < another.minor
                : patch < another.patch;
        }
        
        /** For players: the version, as far as it is known. */
        public String display() {
            return equals(BEFORE_THE_CHECK) ? "older than " + OLDEST_COMPATIBLE : toString();
        }
    }
    
    public static ModVersion immPtlVersion;
    
    public static record ImmPtlConfigurationTask(
    ) implements ICustomConfigurationTask {
        public static final ConfigurationTask.Type TYPE =
            new ConfigurationTask.Type("iportal:config");

        @Override
        public void run(Consumer<CustomPacketPayload> sender) {
            sender.accept(
                    new S2CConfigStartPacket(immPtlVersion)
            );
        }
        
        @Override
        public @NotNull Type type() {
            return TYPE;
        }
    }
    
    public static record S2CConfigStartPacket(
        ModVersion versionFromServer
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<S2CConfigStartPacket> TYPE = new Type<>(ResourceLocation.parse("iportal:config_packet"));

        public static final StreamCodec<FriendlyByteBuf, S2CConfigStartPacket> CODEC = StreamCodec.of(
                (b, p) -> p.write(b), S2CConfigStartPacket::read
        );

        public static S2CConfigStartPacket read(FriendlyByteBuf buf) {
            ModVersion info = ModVersion.read(buf);
            return new S2CConfigStartPacket(info);
        }
        
        public void write(FriendlyByteBuf buf) {
            versionFromServer.write(buf);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
        
        // handled on client side
        //@OnlyIn(Dist.CLIENT)
        public void handle(IPayloadContext configurationPayloadContext) {
            LOGGER.info(
                "Client received ImmPtl config packet. Server mod version: {}", versionFromServer
            );
            
            serverVersion = versionFromServer;
            
            // Immersive Aeronautics: a server older than this build's oldest compatible version cannot know that
            if (versionFromServer.isNormalVersion() && immPtlVersion.isNormalVersion() &&
                !immPtlVersion.isCompatibleWith(versionFromServer) &&
                !IPConfig.getConfig().clientTolerantVersionMismatchWithServer
            ) {
                configurationPayloadContext.disconnect(mismatchMessage(versionFromServer, immPtlVersion));
                return;
            }
            
            configurationPayloadContext.reply(new C2SConfigCompletePacket(
                    immPtlVersion, IPConfig.getConfig().clientTolerantVersionMismatchWithServer
            ));
        }
    }
    
    public record C2SConfigCompletePacket(
        ModVersion versionFromClient,
        boolean clientTolerantVersionMismatch
    ) implements CustomPacketPayload {
        public static final CustomPacketPayload.Type<C2SConfigCompletePacket> TYPE = new Type<>(ResourceLocation.parse("iportal:configure_complete"));
        public static final StreamCodec<FriendlyByteBuf, C2SConfigCompletePacket> CODEC = StreamCodec.of(
                (b, p) -> p.write(b), C2SConfigCompletePacket::read
        );

        public static C2SConfigCompletePacket read(FriendlyByteBuf buf) {
            ModVersion info = ModVersion.read(buf);
            boolean clientTolerantVersionMismatch = buf.readBoolean();
            return new C2SConfigCompletePacket(info, clientTolerantVersionMismatch);
        }

        public void write(FriendlyByteBuf buf) {
            versionFromClient.write(buf);
            buf.writeBoolean(clientTolerantVersionMismatch);
        }

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
        
        // handled on server side
        public void handle(IPayloadContext configurationPayloadContext
        ) {
            if (versionFromClient.isNormalVersion() && immPtlVersion.isNormalVersion()) {
                if (!immPtlVersion.isCompatibleWith(versionFromClient) &&
                    !IPConfig.getConfig().serverTolerantVersionMismatchWithClient &&
                    !clientTolerantVersionMismatch
                ) {
                    configurationPayloadContext.disconnect(mismatchMessage(immPtlVersion, versionFromClient));
                    LOGGER.info(
                        """
                            Disconnecting client because of ImmPtl version difference.
                            Client ImmPtl version: {}
                            Server ImmPtl version: {}""",
                        versionFromClient, immPtlVersion
                    );
                    return;
                }
            }

            configurationPayloadContext.finishCurrentTask(ImmPtlConfigurationTask.TYPE);
        }
    }
    
    private static Component mismatchMessage(ModVersion server, ModVersion client) {
        return Component.translatable(
            "imm_ptl.mod_major_minor_version_mismatch", server.display(), client.display()
        );
    }
    
    public static void init(IEventBus eventBus) {
        immPtlVersion = O_O.getImmPtlVersion();
        
        LOGGER.info("Immersive Portals Core version {}", immPtlVersion);

        // TODO @Nick1st - Check that this fixes Server login
        eventBus.addListener(RegisterConfigurationTasksEvent.class, (event) -> {
            if (event.getListener().getConnectionType().isNeoForge()) {
                event.register(new ImmPtlConfigurationTask());
            }
            else {
                if (FMLEnvironment.dist.isDedicatedServer()) {
                    if (IPConfig.getConfig().serverRejectClientWithoutImmPtl) {
                        // cannot use translation key here
                        // because the translation does not exist on client without the mod
                        event.getListener().disconnect(Component.literal(
                                """
                                    The server detected that client does not install Immersive Portals mod.
                                    A server with Immersive Portals mod only works with the clients that have it.

                                    (Note: The networking sync may be interfered by Essential mod or other mods. When you are using these mods, the detection may malfunction. In this case, you can disable networking check in the server side by changing `serverRejectClientWithoutImmPtl` to `false` in the server's config file `config/immersive_portals.json` and restart.)
                                    """
                        ));
                    }
                    else {
                        GameProfile gameProfile =
                                ((IEServerConfigurationPacketListenerImpl) event.getListener()).ip_getGameProfile();

                        LOGGER.warn(
                                "Neo detected that client does not install ImmPtl. {} {}",
                                gameProfile.getName(), gameProfile.getId()
                        );
                    }
                }
                // TODO @Nick1st Check if this problem also exist at NEO
                else {
                    LOGGER.error("ImmPtl configuration channel is non-sendable from Fabric API in integrated server. Fabric API sendable channel sync is interfered.");
                }
            }
        });
    }
    
    //@OnlyIn(Dist.CLIENT)
    public static void initClient() {
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingIn.class, (event) -> onClientJoin());
        // Immersive Aeronautics: the next server may not have the mod
        NeoForge.EVENT_BUS.addListener(ClientPlayerNetworkEvent.LoggingOut.class, (event) -> serverVersion = null);
    }
    
    private static void onClientJoin() {
        if (serverVersion == null) {
            warnServerMissingImmPtl();
        }
        else {
            if (serverVersion.isNormalVersion() &&
                immPtlVersion.isNormalVersion() &&
                !serverVersion.equals(immPtlVersion)
            ) {
                if (IPConfig.getConfig().shouldDisplayWarning("mod_version_mismatch")) {
                    MutableComponent text =
                        Component.translatable(
                            "imm_ptl.mod_patch_version_mismatch",
                            Component.literal(serverVersion.display())
                                .withStyle(ChatFormatting.GOLD),
                            Component.literal(immPtlVersion.display())
                                .withStyle(ChatFormatting.GOLD)
                        ).append(
                            IPMcHelper.getDisableWarningText("mod_version_mismatch")
                        );
                    CHelper.printChat(text);
                }
            }
        }
    }
    
    // used on client
    private static @Nullable ImmPtlNetworkConfig.ModVersion serverVersion = null;
    
    // should be called from client
    public static boolean doesServerHaveImmPtl() {
        return serverVersion != null;
    }
    
    private static void warnServerMissingImmPtl() {
        Minecraft.getInstance().execute(() -> {
            CHelper.printChat(Component.translatable("imm_ptl.server_missing_immptl"));
        });
    }
}
