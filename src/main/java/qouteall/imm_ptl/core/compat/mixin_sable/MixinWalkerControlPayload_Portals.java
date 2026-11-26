/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.letitwalk.network.WalkerControlPayload;
import dev.ryanhcode.sable.ActiveSableCompanion;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.objectweb.asm.Opcodes;
import qouteall.imm_ptl.core.compat.sable_integration.IPWalkerPilot;

/**
 * Let It Walk's pilot-controls packet across portals ({@link IPWalkerPilot}): the controls resolve in their walker's
 * level, at their position since the walker last moved, with the player's position and heading mapped into that world.
 */
@Mixin(value = WalkerControlPayload.class, remap = false)
public class MixinWalkerControlPayload_Portals {
    @WrapOperation(
        method = "block",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;level()Lnet/minecraft/world/level/Level;"),
        require = 0
    )
    private static Level ip_controlsLevel(final ServerPlayer player, final Operation<Level> original,
                                          @Local(argsOnly = true) final WalkerControlPayload msg) {
        return IPWalkerPilot.packetLevel(player, msg.controls(), original.call(player));
    }

    @WrapOperation(
        method = "block",
        at = @At(value = "FIELD", target = "Ldev/letitwalk/network/WalkerControlPayload;controls:Lnet/minecraft/core/BlockPos;", opcode = Opcodes.GETFIELD),
        require = 0
    )
    private static BlockPos ip_controlsPos(final WalkerControlPayload msg, final Operation<BlockPos> original,
                                           @Local(argsOnly = true) final ServerPlayer player) {
        return IPWalkerPilot.packetPos(player, original.call(msg));
    }

    @WrapOperation(
        method = "block",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/ActiveSableCompanion;distanceSquaredWithSubLevels(Lnet/minecraft/world/level/Level;Lnet/minecraft/core/Position;Lnet/minecraft/core/Position;)D"),
        require = 0
    )
    private static double ip_pilotDistance(final ActiveSableCompanion helper, final Level level, final Position player, final Position controls,
                                           final Operation<Double> original, @Local(argsOnly = true) final ServerPlayer pilot,
                                           @Local(argsOnly = true) final WalkerControlPayload msg) {
        return original.call(helper, level, IPWalkerPilot.playerPosition(pilot, msg.controls(), player), controls);
    }

    @WrapOperation(
        method = "drive",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;getYRot()F"),
        require = 0
    )
    private static float ip_pilotYaw(final ServerPlayer player, final Operation<Float> original) {
        return IPWalkerPilot.pilotYaw(player, original.call(player));
    }
}
