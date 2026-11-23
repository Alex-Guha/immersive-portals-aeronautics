/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.offroad.handlers.server.MultiMiningServerManager;
import foundry.veil.api.network.VeilPacketManager;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPBoreheadPortals;

/**
 * Cracks on blocks a borehead breaks through a portal ({@link IPBoreheadPortals}): Offroad sends a level's mining
 * progress to the players tracking the breaking bearing's position, which for a bearing of another level is a position
 * nobody tracks there. Those go to the players who have the blocks' chunks instead.
 */
@Mixin(value = MultiMiningServerManager.class, remap = false)
public abstract class MixinMultiMiningServerManager_Portals {
    @WrapOperation(method = "handleTicking", require = 0, at = @At(value = "INVOKE",
            target = "Lfoundry/veil/api/network/VeilPacketManager;tracking(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Lfoundry/veil/api/network/VeilPacketManager$PacketSink;"))
    private VeilPacketManager.PacketSink ip_farBearingCracks(final ServerLevel level, final BlockPos supplier,
                                                             final Operation<VeilPacketManager.PacketSink> original) {
        return IPBoreheadPortals.isFarSupplier(level, supplier) ? IPBoreheadPortals.blockViewers(level) : original.call(level, supplier);
    }
}
