/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The terrain part restores Immersive Portals' own PlayerList.broadcast (iPortalTeam; Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundCustomPayloadPacket;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.players.PlayerList;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipThroughPortalSync;

/**
 * Sounds, block events (chest lids) and level events reach players who see their position from another dimension.
 * Immersive Portals overwrites {@code PlayerList.broadcast} to send them to every player watching the chunk, through
 * portals too; Sable overwrites it again (higher priority, so it wins) with a same-dimension distance check that also
 * understands ships. This runs after Sable's and adds the players in other dimensions
 * ({@link IPShipThroughPortalSync#broadcastToOtherDimensions}).
 */
@Mixin(value = PlayerList.class, priority = 1100)
public class MixinPlayerList_CrossDimensionBroadcast {
    /** Without Sable, Immersive Portals' own overwrite is in place and already does this. */
    @Unique
    private static final boolean ip_SABLE = net.neoforged.fml.ModList.get().isLoaded("sable");

    @Shadow
    @Final
    private MinecraftServer server;

    @Inject(method = "broadcast", at = @At("RETURN"), require = 0)
    private void ip_broadcastToOtherDimensions(@Nullable final Player except, final double x, final double y, final double z,
                                               final double distance, final ResourceKey<Level> dimension, final Packet<?> packet,
                                               final CallbackInfo ci) {
        if (!ip_SABLE || packet instanceof ClientboundCustomPayloadPacket) {
            return;
        }
        final ServerLevel level = this.server.getLevel(dimension);
        if (level != null) {
            IPShipThroughPortalSync.broadcastToOtherDimensions(level, x, y, z, distance, except, packet);
        }
    }
}
