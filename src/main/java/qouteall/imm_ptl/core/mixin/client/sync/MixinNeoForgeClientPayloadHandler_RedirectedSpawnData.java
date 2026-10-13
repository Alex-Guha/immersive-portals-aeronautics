/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.sync;

import com.mojang.logging.LogUtils;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.entity.Entity;
import net.neoforged.neoforge.entity.IEntityWithComplexSpawn;
import net.neoforged.neoforge.network.handlers.ClientPayloadHandler;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.payload.AdvancedAddEntityPayload;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.q_misc_util.my_util.CountDownInt;

/**
 * NeoForge sends the spawn data of an {@link IEntityWithComplexSpawn} entity as a separate
 * {@link AdvancedAddEntityPayload} and resolves the entity in {@code context.player().level()}.
 * When IP redirects the packets of a portal-side dimension, the add-entity packet put the entity
 * into that dimension's client level, but the player's level is unchanged, so the lookup finds
 * nothing and the entity silently never receives its spawn data. A Create carriage then has no
 * contraption, throws on its first tick and discards itself, and the train stays invisible until
 * the client reconnects. While IP has the client world switched, resolve the entity in the
 * switched level instead and read the spawn data the way NeoForge does.
 */
@Mixin(value = ClientPayloadHandler.class, remap = false)
public class MixinNeoForgeClientPayloadHandler_RedirectedSpawnData {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();
    @Unique
    private static final CountDownInt ip_MISSING_LOG_COUNTER = new CountDownInt(20);

    @Inject(
        method = "handle(Lnet/neoforged/neoforge/network/payload/AdvancedAddEntityPayload;Lnet/neoforged/neoforge/network/handling/IPayloadContext;)V",
        at = @At("HEAD"),
        cancellable = true
    )
    private static void ip_readSpawnDataInSwitchedWorld(
        AdvancedAddEntityPayload payload, IPayloadContext context, CallbackInfo ci
    ) {
        if (!ClientWorldLoader.getIsWorldSwitched()) {
            return;
        }
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null || level == context.player().level()) {
            return;
        }
        
        ci.cancel();
        
        Entity entity = level.getEntity(payload.entityId());
        if (!(entity instanceof IEntityWithComplexSpawn complexSpawn)) {
            if (ip_MISSING_LOG_COUNTER.tryDecrement()) {
                ip_LOGGER.warn(
                    "[ImmPtl] Spawn data for entity {} arrived for {} but no such entity is there ({})",
                    payload.entityId(), level.dimension().location(), entity
                );
            }
            return;
        }
        
        RegistryFriendlyByteBuf buf = new RegistryFriendlyByteBuf(
            Unpooled.wrappedBuffer(payload.customPayload()),
            entity.registryAccess(),
            context.listener().getConnectionType()
        );
        try {
            complexSpawn.readSpawnData(buf);
        }
        catch (Throwable t) {
            ip_LOGGER.error(
                "[ImmPtl] Failed to read spawn data of {} in {}",
                entity, level.dimension().location(), t
            );
        }
        finally {
            buf.release();
        }
    }
}
