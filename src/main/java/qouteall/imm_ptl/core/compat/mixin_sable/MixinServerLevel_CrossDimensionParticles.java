/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.core.particles.ParticleOptions;
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipThroughPortalSync;

/**
 * Particles the server sends ({@code ServerLevel.sendParticles}: hit sparks, crits, Create Big Cannons' spark bursts on a
 * Shield Generators shield) also reach the players who see that spot through a portal from another dimension, as
 * dimension-tagged packets ({@link IPShipThroughPortalSync#broadcastToOtherDimensions}); Immersive Portals sent them to
 * players of the same dimension only. Players in the level itself are vanilla's (32 blocks).
 */
@Mixin(ServerLevel.class)
public class MixinServerLevel_CrossDimensionParticles {
    @Inject(method = "sendParticles(Lnet/minecraft/core/particles/ParticleOptions;DDDIDDDD)I", at = @At("RETURN"), require = 0)
    private void ip_sendToOtherDimensions(final ParticleOptions type, final double x, final double y, final double z, final int count,
                                          final double xDist, final double yDist, final double zDist, final double speed,
                                          final CallbackInfoReturnable<Integer> cir) {
        IPShipThroughPortalSync.broadcastToOtherDimensions((ServerLevel) (Object) this, x, y, z, 32.0, null,
                () -> new ClientboundLevelParticlesPacket(type, false, x, y, z, (float) xDist, (float) yDist, (float) zDist,
                        (float) speed, count));
    }
}
