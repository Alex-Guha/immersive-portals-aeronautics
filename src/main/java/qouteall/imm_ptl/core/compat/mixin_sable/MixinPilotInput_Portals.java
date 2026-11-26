/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.letitwalk.client.PilotInput;
import dev.ryanhcode.sable.ActiveSableCompanion;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_integration.IPWalkerPilotClient;

/**
 * Let It Walk's piloting on the client across portals ({@link IPWalkerPilotClient}): the range check measures in the
 * controls' world and follows the walker to its new plot; the controls' state is read in their world.
 */
@Mixin(value = PilotInput.class, remap = false)
public class MixinPilotInput_Portals {
    @WrapOperation(
        method = "onClientTick",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/ActiveSableCompanion;distanceSquaredWithSubLevels(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Position;Lnet/minecraft/core/Position;)D"),
        require = 0
    )
    private static double ip_pilotRange(final ActiveSableCompanion helper, final Level level, final Position player, final Position controls,
                                        final Operation<Double> original) {
        final BlockPos active = IPPilotInputAccessor.ip_getActive();
        if (active == null) {
            return original.call(helper, level, player, controls);
        }
        return IPWalkerPilotClient.distanceSquared(level, player, active, IPPilotInputAccessor::ip_setActive,
            (in, a, b) -> original.call(helper, in, a, b));
    }

    @WrapOperation(
        method = "status()Ldev/letitwalk/network/PilotStatusPayload;",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"),
        require = 0
    )
    private static BlockEntity ip_controlsState(final ClientLevel level, final BlockPos pos, final Operation<BlockEntity> original) {
        final Level controlsLevel = IPWalkerPilotClient.controlsLevel(level, pos);
        return controlsLevel instanceof ClientLevel in && in != level ? original.call(in, pos) : original.call(level, pos);
    }
}
