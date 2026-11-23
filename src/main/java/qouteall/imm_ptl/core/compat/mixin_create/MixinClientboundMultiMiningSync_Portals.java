/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.offroad.network.borehead_bearing.ClientboundMultiMiningSync;
import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPBoreheadPortalsClient;

/**
 * Offroad's crack progress for blocks of another dimension, sent dimension-tagged to players who see them through a
 * portal ({@code IPBoreheadPortals}), is applied in that dimension rather than the player's own. Runs on the client only.
 */
@Mixin(value = ClientboundMultiMiningSync.class, remap = false)
public abstract class MixinClientboundMultiMiningSync_Portals {
    @WrapOperation(method = "handle", require = 0, at = @At(value = "INVOKE",
            target = "Lfoundry/veil/api/network/handler/PacketContext;level()Lnet/minecraft/world/level/Level;"))
    private Level ip_redirectedLevel(final PacketContext context, final Operation<Level> original) {
        return IPBoreheadPortalsClient.packetLevel(original.call(context));
    }
}
