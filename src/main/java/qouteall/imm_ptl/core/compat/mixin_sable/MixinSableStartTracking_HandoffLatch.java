/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket;
import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.UUID;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleHandoffLatch;

/**
 * A ship tracked into a plot slot whose old copy is still held by {@link IPStraddleHandoffLatch}, or a held ship
 * tracked again in its own dimension, releases the held copy first (before {@link MixinSableStartTracking_DupGuard},
 * which would otherwise keep the stale copy as the "same ship"). Afterwards a new copy of a ship held in another
 * dimension is linked to it, so both show one motion.
 */
@Mixin(value = ClientboundStartTrackingSubLevelPacket.class, remap = false, priority = 900)
public abstract class MixinSableStartTracking_HandoffLatch {
    @Shadow
    public abstract long plotCoordinate();

    @Shadow
    public abstract UUID subLevelID();

    @Inject(method = "handle", at = @At("HEAD"), require = 0)
    private void ip_releaseHeldSlot(final PacketContext context, final CallbackInfo ci) {
        final long plot = this.plotCoordinate();
        IPStraddleHandoffLatch.releaseSlot(context.level(), ChunkPos.getX(plot), ChunkPos.getZ(plot), this.subLevelID());
    }

    @Inject(method = "handle", at = @At("TAIL"), require = 0)
    private void ip_linkHeldCopy(final PacketContext context, final CallbackInfo ci) {
        IPStraddleHandoffLatch.onTracked(context.level(), this.subLevelID());
    }
}
