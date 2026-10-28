/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.simulated_team.simulated.network.packets.UpdatePlayerUsingHandlePacket;
import foundry.veil.api.network.handler.ServerPacketContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPHandlePortals;

/**
 * Routes Create Aeronautics (Simulated) handle packets to the level of the held ship (Simulated uses the player's) and
 * to the handle's position in it after the ship moved to another dimension ({@link IPHandlePortals}).
 */
@Mixin(value = UpdatePlayerUsingHandlePacket.class, remap = false)
public class MixinUpdatePlayerUsingHandlePacket_Portals {
    @WrapOperation(
        method = "handle",
        at = @At(value = "INVOKE", target = "Lfoundry/veil/api/network/handler/ServerPacketContext;level()Lnet/minecraft/world/level/Level;"),
        require = 0
    )
    private Level ip_handleLevel(final ServerPacketContext context, final Operation<Level> original) {
        return IPHandlePortals.packetLevel(context.player(), ((UpdatePlayerUsingHandlePacket) (Object) this).interactionPos(), original.call(context));
    }

    @WrapOperation(
        method = "handle",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"),
        require = 0
    )
    private BlockEntity ip_handlePos(final Level level, final BlockPos pos, final Operation<BlockEntity> original,
                                     @Local(argsOnly = true) final ServerPacketContext context) {
        return original.call(level, IPHandlePortals.packetPos(context.player(), pos));
    }

    @Inject(method = "handle", at = @At("TAIL"), require = 0)
    private void ip_released(final ServerPacketContext context, final CallbackInfo ci) {
        final UpdatePlayerUsingHandlePacket self = (UpdatePlayerUsingHandlePacket) (Object) this;
        if (self.remove()) {
            IPHandlePortals.onReleased(context.player(), self.interactionPos());
        }
    }
}
