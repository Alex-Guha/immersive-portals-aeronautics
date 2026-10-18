/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.peripheral.mixin.common.nether_portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.FlintAndSteelItem;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.BreakableMirror;
import qouteall.imm_ptl.peripheral.PeripheralModMain;
import qouteall.imm_ptl.peripheral.portal_generation.IntrinsicPortalGeneration;

@Mixin(FlintAndSteelItem.class)
public class MixinFlintAndSteelItem_CVB {
    @Inject(method = "Lnet/minecraft/world/item/FlintAndSteelItem;useOn(Lnet/minecraft/world/item/context/UseOnContext;)Lnet/minecraft/world/InteractionResult;", at = @At("HEAD"), cancellable = true)
    private void onUseFlintAndSteel(
        UseOnContext context,
        CallbackInfoReturnable<InteractionResult> cir
    ) {
        LevelAccessor world = context.getLevel();
        if (!world.isClientSide()) {
            BlockPos targetPos = context.getClickedPos();
            Direction side = context.getClickedFace();
            BlockPos firePos = targetPos.relative(side);
            BlockState targetBlockState = world.getBlockState(targetPos);
            Block targetBlock = targetBlockState.getBlock();
            if (BreakableMirror.isGlass(((Level) world), targetPos) && IPGlobal.enableMirrorCreation) {
                BreakableMirror mirror = BreakableMirror.createMirror(
                    ((ServerLevel) world), targetPos, side
                );
                cir.setReturnValue(InteractionResult.SUCCESS);
            }
            else if (targetBlock == PeripheralModMain.portalHelperBlock) {
                boolean result = IntrinsicPortalGeneration.activatePortalHelper(
                    ((ServerLevel) world),
                    firePos
                );
                cir.setReturnValue(InteractionResult.SUCCESS);
            }
            // Immersive Aeronautics: any nether portal frame block, not only obsidian
            else if (O_O.isObsidian(targetBlockState)) {
                Player player = context.getPlayer();
                if (player != null) {
                    if (player.getPose() == Pose.CROUCHING) {
                        boolean succeeded = IntrinsicPortalGeneration.onCrouchingPlayerIgnite(
                            ((ServerLevel) world),
                            ((ServerPlayer) player),
                            firePos
                        );
                        if (succeeded) {
                            cir.setReturnValue(InteractionResult.SUCCESS);
                            return;
                        }
                    }
                    boolean succ = IntrinsicPortalGeneration.onFireLitOnObsidian(
                        ((ServerLevel) world),
                        firePos,
                        player
                    );
                    if (succ) {
                        // it won't create the fire block
                        cir.setReturnValue(InteractionResult.SUCCESS);
                    }
                }
            }
        }
    }
}
