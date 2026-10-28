/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.ryanhcode.sable.ActiveSableCompanion;
import dev.simulated_team.simulated.content.blocks.handle.ClientHandleHandler;
import dev.simulated_team.simulated.util.hold_interaction.BlockHoldInteraction;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import org.joml.Vector3d;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPHandlePortalsClient;

/**
 * Create Aeronautics (Simulated) handles across portals, client side ({@link IPHandlePortalsClient}): taking hold of a
 * handle on a far half or behind a portal in the ship's own world, catching one there while holding use, and holding
 * it where it is seen, through portal crossings of the player and the ship.
 */
@Mixin(value = ClientHandleHandler.class, remap = false)
public class MixinClientHandleHandler_Portals {
    @Inject(method = "startHold", at = @At("HEAD"), require = 0)
    private void ip_beforeStartHold(final Level level, final Player player, final BlockPos pos, final CallbackInfo ci) {
        IPHandlePortalsClient.beforeStartHold(level, player, pos);
    }

    /**
     * Simulated measures the hold's range to the handle projected in the player's level: in the handle's own, as seen.
     */
    @WrapOperation(
        method = "startHold",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/ActiveSableCompanion;projectOutOfSubLevel(Lnet/minecraft/world/level/Level;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"),
        require = 0
    )
    private Vector3d ip_startingGrabCenter(final ActiveSableCompanion helper, final Level playerLevel, final Vector3d center,
                                          final Operation<Vector3d> original, @Local(argsOnly = true) final Level level) {
        return IPHandlePortalsClient.startingGrabCenterSeen(original.call(helper, level, center));
    }

    @Inject(
        method = "startHold",
        at = @At(value = "INVOKE", target = "Ldev/simulated_team/simulated/util/hold_interaction/BlockHoldInteraction;startHold(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;)V", shift = At.Shift.AFTER),
        require = 0
    )
    private void ip_afterHoldReplaced(final Level level, final Player player, final BlockPos pos, final CallbackInfo ci) {
        IPHandlePortalsClient.afterHoldReplaced(pos);
    }

    @Inject(method = "stop", at = @At("TAIL"), require = 0)
    private void ip_onStopped(final CallbackInfo ci) {
        IPHandlePortalsClient.onStopped();
    }

    /**
     * The hold ticks in the held ship's world (Simulated passes the player's), and waits while its ship is being moved
     * to another dimension.
     */
    @WrapMethod(method = "activeTick", require = 0)
    private boolean ip_activeTick(final Level level, final LocalPlayer player, final Operation<Boolean> original) {
        final BlockHoldInteraction self = (BlockHoldInteraction) (Object) this;
        return switch (IPHandlePortalsClient.beforeTick(self.getInteractionPos(),
            pos -> ((IPBlockHoldInteractionAccessor) self).ip_setInteractionPos(pos))) {
            case STOP -> true;
            case WAIT -> false;
            case RUN -> original.call(IPHandlePortalsClient.holdLevel(level), player);
        };
    }

    @WrapOperation(
        method = "activeTick",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/ActiveSableCompanion;projectOutOfSubLevel(Lnet/minecraft/world/level/Level;Lorg/joml/Vector3d;)Lorg/joml/Vector3d;"),
        require = 0
    )
    private Vector3d ip_grabCenter(final ActiveSableCompanion helper, final Level level, final Vector3d center, final Operation<Vector3d> original) {
        return IPHandlePortalsClient.grabCenterSeen(original.call(helper, level, center));
    }

    @Inject(method = "clientTick", at = @At("HEAD"), require = 0)
    private void ip_beforeSweep(final Level level, final LocalPlayer player, final CallbackInfo ci) {
        IPHandlePortalsClient.beforeSweep();
    }

    @WrapOperation(
        method = "clientTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;clip(Lnet/minecraft/world/level/ClipContext;)Lnet/minecraft/world/phys/BlockHitResult;"),
        require = 0
    )
    private BlockHitResult ip_sweepClip(final Level level, final ClipContext context, final Operation<BlockHitResult> original) {
        return IPHandlePortalsClient.sweepClip(level, context, () -> original.call(level, context));
    }

    @WrapOperation(
        method = "clientTick",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;"),
        require = 0
    )
    private BlockState ip_sweptState(final Level level, final BlockPos pos, final Operation<BlockState> original) {
        return IPHandlePortalsClient.sweptState(level, pos, () -> original.call(level, pos));
    }

    @WrapOperation(
        method = "clientTick",
        at = @At(value = "INVOKE", target = "Ldev/simulated_team/simulated/content/blocks/handle/ClientHandleHandler;startHold(Lnet/minecraft/world/level/Level;Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/core/BlockPos;)V"),
        require = 0
    )
    private void ip_sweptHold(final ClientHandleHandler self, final Level level, final Player player, final BlockPos pos,
                              final Operation<Void> original) {
        original.call(self, IPHandlePortalsClient.sweptLevel(level, pos), player, pos);
    }
}
