/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The idea follows IPSable 0.5.0's IplParentDimSync hand-off by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.network.packets.tcp.ClientboundStopTrackingSubLevelPacket;
import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleHandoffLatch;

/**
 * Defers the client-side removal of a ship that was just handed off through a portal until its copy in the
 * destination can be drawn ({@link IPStraddleHandoffLatch}).
 */
@Mixin(value = ClientboundStopTrackingSubLevelPacket.class, remap = false)
public abstract class MixinSableStopTracking_HandoffLatch {
    @Shadow
    public abstract long plotCoordinate();

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true, require = 0)
    private void ip_deferHandedOffRemoval(final PacketContext context, final CallbackInfo ci) {
        final long plot = this.plotCoordinate();
        if (IPStraddleHandoffLatch.deferRemoval(context.level(), ChunkPos.getX(plot), ChunkPos.getZ(plot))) {
            ci.cancel();
        }
    }
}
