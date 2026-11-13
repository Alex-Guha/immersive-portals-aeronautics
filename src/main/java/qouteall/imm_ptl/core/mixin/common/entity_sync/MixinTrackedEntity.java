/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0), with changes from
 * Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code and ItsSoBread's changes stay under the
 * Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.mixin.common.entity_sync;

import net.minecraft.core.SectionPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import net.minecraft.server.network.ServerPlayerConnection;
import net.minecraft.world.entity.Entity;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.chunk_loading.ImmPtlChunkTracking;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipBlockInteraction;
import qouteall.imm_ptl.core.ducks.IEChunkMap;
import qouteall.imm_ptl.core.ducks.IEEntityTrackerEntry;
import qouteall.imm_ptl.core.ducks.IETrackedEntity;
import qouteall.imm_ptl.core.miscellaneous.IPVanillaCopy;
import qouteall.imm_ptl.core.network.PacketRedirection;

import java.util.List;
import java.util.Map;
import java.util.Set;
import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

//NOTE must redirect all packets about entities
@SuppressWarnings({"JavadocReference", "resource"})
@Mixin(value = ChunkMap.TrackedEntity.class, priority = 500)
public abstract class MixinTrackedEntity implements IETrackedEntity {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();
    @Shadow
    @Final
    private ServerEntity serverEntity;
    @Shadow
    @Final
    private Entity entity;
    
    @Shadow
    public abstract void broadcastRemoved();
    
    @Shadow
    protected abstract int getEffectiveRange();
    
    @Shadow
    @Final
    private Set<ServerPlayerConnection> seenBy;
    
    @Shadow
    private SectionPos lastSectionPos;
    
    @Redirect(
        method = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;broadcast(Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerPlayerConnection;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private void onSendToOtherNearbyPlayers(
        ServerPlayerConnection entityTrackingListener, Packet<?> packet
    ) {
        PacketRedirection.withForceRedirect(
            ((ServerLevel) entity.level()),
            () -> {
                entityTrackingListener.send(packet);
            }
        );
    }
    
    @SuppressWarnings("rawtypes")
    @Redirect(
        method = "Lnet/minecraft/server/level/ChunkMap$TrackedEntity;broadcastAndSend(Lnet/minecraft/network/protocol/Packet;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/network/ServerGamePacketListenerImpl;send(Lnet/minecraft/network/protocol/Packet;)V"
        )
    )
    private void onSendToNearbyPlayers(
        ServerGamePacketListenerImpl serverPlayNetworkHandler,
        Packet packet
    ) {
        PacketRedirection.sendRedirectedPacket(
            serverPlayNetworkHandler, packet, entity.level().dimension()
        );
    }
    
    /**
     * Managed by ImmPtl.
     * In vanilla, entity tracking updates when
     * - {@link ChunkMap#move(ServerPlayer)}
     *   When the player moves, the entities in curr dim except that player updates to that player,
     *   and that player updates to all player in that dimension
     * Immersive Aeronautics: cancelled at HEAD instead of overwritten, so that other mods' required
     * injectors into the vanilla body (Sable's position() redirect, Vista's inRange modifier) still
     * find their targets; an overwrite crashed the server on the first tracked entity.
     */
    @Inject(method = "updatePlayer", at = @At("HEAD"), cancellable = true, require = 1)
    private void ip_onUpdatePlayer(ServerPlayer player, CallbackInfo ci) {
        ci.cancel();
    }
    
    /**
     * Managed by ImmPtl. Immersive Aeronautics: cancelled instead of overwritten, see above.
     */
    @Inject(method = "updatePlayers", at = @At("HEAD"), cancellable = true, require = 1)
    private void ip_onUpdatePlayers(List<ServerPlayer> list, CallbackInfo ci) {
        ci.cancel();
    }
    
    @Override
    public Entity ip_getEntity() {
        return entity;
    }
    
    /**
     * {@link ChunkMap.TrackedEntity#updatePlayer(ServerPlayer)}
     * This only checks the players viewing the chunk.
     * so this is more efficient in this aspect.
     * However, in vanilla, if both the entity and player doesn't move, it won't update.
     * But in ImmPtl, it constantly updates because portals can change at any time and
     * that can changes entity visibility at anytime.
     */
    @IPVanillaCopy
    @Override
    public void ip_updateEntityTrackingStatus() {
        IEChunkMap chunkMap = (IEChunkMap)
            ((ServerLevel) entity.level()).getChunkSource().chunkMap;
        
        var watchRecMap = ImmPtlChunkTracking.getWatchRecordForChunk(
            entity.level().dimension(),
            entity.chunkPosition().x, entity.chunkPosition().z
        );
        
        // no need to clamp it with render distance, as we check chunk watch records now
        int effectiveRange = getEffectiveRange();
        
        seenBy.removeIf(connection -> {
            ServerPlayer player = connection.getPlayer();
            boolean shouldRemove = !watches(entity, watchRecMap, effectiveRange, player);
            if (shouldRemove) {
                PacketRedirection.withForceRedirect(
                    ((ServerLevel) entity.level()),
                    () -> {
                        this.serverEntity.removePairing(player);
                    }
                );
            }
            return shouldRemove;
        });
        
        if (watchRecMap != null) {
            for (var e : watchRecMap.entrySet()) {
                ServerPlayer player = e.getKey();
                ImmPtlChunkTracking.PlayerWatchRecord rec = e.getValue();
                
                if (recWatches(entity, effectiveRange, rec, player)) {
                    if (seenBy.add(player.connection)) {
                        PacketRedirection.withForceRedirect(
                            ((ServerLevel) entity.level()),
                            () -> {
                                this.serverEntity.addPairing(player);
                            }
                        );
                    }
                }
            }
        }
        
        // Immersive Aeronautics: entities in Sable plot space (|chunk| > 100000) are paired
        // with every player in the level, since IP's chunk tracking never covers plot chunks
        if (Math.abs(this.entity.chunkPosition().x) > 100000 || Math.abs(this.entity.chunkPosition().z) > 100000) {
            List<ServerPlayer> players = ((ServerLevel) this.entity.level()).players();
            for (int i = 0; i < players.size(); i++) {
                ServerPlayer player = players.get(i);
                if (player != this.entity && this.seenBy.add(player.connection)) {
                    this.serverEntity.addPairing(player);
                    ip_LOGGER.debug(
                        "[IPPlotEntityPair] paired {}#{} -> {}",
                        this.entity.getClass().getName(), this.entity.getId(), player.getName().getString()
                    );
                }
            }
            // Immersive Aeronautics: and with the players in other dimensions the ship is synced to (seeing it through a
            // portal, or near its far half), dimension-tagged
            ServerLevel level = (ServerLevel) this.entity.level();
            for (ServerPlayer player : IPShipBlockInteraction.withPortalViewers(level, this.entity.chunkPosition(), List.of())) {
                if (player != this.entity && player.level() != level && this.seenBy.add(player.connection)) {
                    PacketRedirection.withForceRedirect(level, () -> this.serverEntity.addPairing(player));
                }
            }
        }
    }
    
    @Unique
    private static boolean watches(
        Entity entity,
        @Nullable Map<ServerPlayer, ImmPtlChunkTracking.PlayerWatchRecord> watchRec,
        int effectiveRange,
        ServerPlayer player
    ) {
        // Immersive Aeronautics: plot-space entities are watched by everyone in the same level
        if ((Math.abs(entity.chunkPosition().x) > 100000 || Math.abs(entity.chunkPosition().z) > 100000)) {
            if (entity.level() == player.level()) {
                return true;
            }
            // Immersive Aeronautics: and by the players in other dimensions the ship is synced to
            if (IPShipBlockInteraction.withPortalViewers((ServerLevel) entity.level(), entity.chunkPosition(), List.of()).contains(player)) {
                return true;
            }
        }
        
        // Immersive Aeronautics: a rider always watches its vehicle and vice versa
        if (entity != null && player != null) {
            if (entity.hasPassenger(player)) {
                return true;
            }
            if (player.getVehicle() == entity) {
                return true;
            }
        }
        
        // Immersive Aeronautics: Sable entities are never watched across dimensions
        if (entity.getClass().getName().startsWith("dev.ryanhcode.sable") && entity.level() != player.level()) {
            return false;
        }
        
        if (watchRec == null) {
            return false;
        }
        
        if (entity == player) {
            return false;
        }
        
        ImmPtlChunkTracking.PlayerWatchRecord rec = watchRec.get(player);
        
        return recWatches(entity, effectiveRange, rec, player);
    }
    
    @Unique
    private static boolean recWatches(
        Entity entity, int effectiveRange,
        ImmPtlChunkTracking.PlayerWatchRecord rec, ServerPlayer player
    ) {
        if (rec == null) {
            return false;
        }
        
        if (!rec.isLoadedToPlayer) {
            // when player logging in standing on cross portal collision
            // we need to send add entity packet early,
            // otherwise cross portal collision will not work early enough
            // but if the portal is big (center can be 4 chunks away from player)
            // the new slow chunk sending mechanism will slow its packet sent
            // TODO find a solution to this
            return false;
        }
        
        if (entity == player) {
            return false;
        }
        
        return rec.distanceToSource * 16 + 8 <= effectiveRange;
    }
    
    @Override
    public void ip_onDimensionRemove() {
        for (ServerPlayerConnection connection : seenBy) {
            serverEntity.removePairing(connection.getPlayer());
        }
        seenBy.clear();
    }
    
    @Override
    public void ip_resendSpawnPacketToTrackers() {
        // avoid sending wrong position delta update packet
        ((IEEntityTrackerEntry) serverEntity).ip_updateTrackedEntityPosition();
        
        Packet spawnPacket = entity.getAddEntityPacket(serverEntity);
        Packet<ClientGamePacketListener> redirected = PacketRedirection.createRedirectedMessage(
            entity.getServer(),
            entity.level().dimension(), spawnPacket
        );
        seenBy.forEach(handler -> {
            handler.send(redirected);
        });
    }
    
    @Override
    public void ip_stopTrackingToAllPlayers() {
        broadcastRemoved();
    }
    
    @Override
    public void ip_sendChanges() {
        serverEntity.sendChanges();
    }
    
    @Override
    public SectionPos ip_getLastSectionPos() {
        return lastSectionPos;
    }
    
    @Override
    public void ip_setLastSectionPos(SectionPos arg) {
        lastSectionPos = arg;
    }
    
}
