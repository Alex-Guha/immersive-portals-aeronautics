/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.foundation.blockEntity.behaviour.BehaviourType;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalBelts;

/**
 * Belts through portals ({@link IPCreatePortalBelts}): the block past a belt's end that Create hands items to is the one
 * past the portal; items run on to the plane before they are handed over, and land at the far plane.
 */
@Mixin(value = BeltInventory.class, remap = false)
public abstract class MixinBeltInventory_PortalBelts {
    @Shadow
    @Final
    BeltBlockEntity belt;

    @Shadow
    boolean beltMovementPositive;

    @Shadow
    @Final
    private List<TransportedItemStack> items;

    @Shadow
    @Final
    List<TransportedItemStack> toInsert;

    @Shadow
    TransportedItemStack lazyClientItem;

    @Unique
    private IPCreatePortalBelts.Exit ip$exit;
    @Unique
    private long ip$exitTick = -1;
    @Unique
    private boolean ip$exitPositive;
    /** The ending last resolved hands items through the portal: they run on to the plane. */
    @Unique
    private boolean ip$toPlane;

    /** The portal exit at the end items move towards, looked up once a tick. */
    @Unique
    private IPCreatePortalBelts.Exit ip$exit() {
        final Level level = belt.getLevel();
        final long now = level == null ? -1 : level.getGameTime();
        if (now != ip$exitTick || ip$exitPositive != beltMovementPositive) {
            ip$exitTick = now;
            ip$exitPositive = beltMovementPositive;
            ip$exit = level == null ? null : IPCreatePortalBelts.exit(belt, beltMovementPositive);
        }
        return ip$exit;
    }

    /** Client: belts with items near a portal at an end are drawn cut at its plane ({@code IPCreatePortalBeltsClient}). */
    @Inject(method = "tick", at = @At("HEAD"))
    private void ip$noteForDrawing(final CallbackInfo ci) {
        if (belt.getLevel() != null && belt.getLevel().isClientSide && !belt.isVirtual()) {
            IPCreatePortalBelts.noteClientItems(belt, items, lazyClientItem);
        }
    }

    /** On a far belt, for an item handed through a portal: occupied measured from the plane. */
    @Inject(method = "canInsertAtFromSide", at = @At("HEAD"), cancellable = true)
    private void ip$spacingAtPlane(final int segment, final Direction side, final CallbackInfoReturnable<Boolean> cir) {
        final IPCreatePortalBelts.Exit exit = IPCreatePortalBelts.handingThrough(belt.getLevel(), side);
        if (exit != null && belt.getMovementFacing() == side) {
            cir.setReturnValue(!IPCreatePortalBelts.occupiedAtPlane(exit, segment, beltMovementPositive, items, toInsert));
        }
    }

    /** The far side not loaded: wait as at a blocked end, rather than ejecting into a portal that holds entities back. */
    @Inject(method = "resolveEnding", at = @At("HEAD"), cancellable = true)
    private void ip$waitForFarSide(final CallbackInfoReturnable<Object> cir) {
        ip$toPlane = false;
        final IPCreatePortalBelts.Exit exit = ip$exit();
        if (exit != null && !belt.getLevel().isClientSide && !exit.farLevel().isLoaded(exit.farPos())
            && IPCreatePortalBelts.ENDING_BLOCKED != null) {
            cir.setReturnValue(IPCreatePortalBelts.ENDING_BLOCKED);
        }
    }

    @WrapOperation(
        method = {"resolveEnding", "tick"},
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/foundation/blockEntity/behaviour/BlockEntityBehaviour;get(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lcom/simibubi/create/foundation/blockEntity/behaviour/BehaviourType;)Lcom/simibubi/create/foundation/blockEntity/behaviour/BlockEntityBehaviour;"
        )
    )
    private BlockEntityBehaviour ip$farInput(
        final BlockGetter world, final BlockPos pos, final BehaviourType<?> type, final Operation<BlockEntityBehaviour> original
    ) {
        final BlockEntityBehaviour own = original.call(world, pos, type);
        if (own != null || type != DirectBeltInputBehaviour.TYPE) {
            return own; // Create's own neighbour wins over a portal plane between them
        }
        final IPCreatePortalBelts.Exit exit = ip$exit();
        final DirectBeltInputBehaviour far = exit == null ? null : IPCreatePortalBelts.farInput(exit);
        if (far != null) {
            ip$toPlane = true;
        }
        return far;
    }

    @ModifyExpressionValue(
        method = "tick",
        at = @At(value = "FIELD", target = "Lcom/simibubi/create/content/kinetics/belt/transport/BeltInventory$Ending;margin:F")
    )
    private float ip$runOnToPlane(final float margin) {
        return ip$toPlane && ip$exit != null ? -ip$exit.nearGap() : margin;
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/belt/behaviour/DirectBeltInputBehaviour;canInsertFromSide(Lnet/minecraft/core/Direction;)Z"
        )
    )
    private boolean ip$farCanInsert(final DirectBeltInputBehaviour input, final Direction side, final Operation<Boolean> original) {
        return original.call(input, IPCreatePortalBelts.isFar(ip$exit, input) ? ip$exit.farFacing() : side);
    }

    @WrapOperation(
        method = "tick",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/belt/behaviour/DirectBeltInputBehaviour;handleInsertion(Lcom/simibubi/create/content/kinetics/belt/transport/TransportedItemStack;Lnet/minecraft/core/Direction;Z)Lnet/minecraft/world/item/ItemStack;"
        )
    )
    private ItemStack ip$handOver(
        final DirectBeltInputBehaviour input, final TransportedItemStack stack, final Direction side, final boolean simulate,
        final Operation<ItemStack> original
    ) {
        final IPCreatePortalBelts.Exit exit = ip$exit;
        if (!IPCreatePortalBelts.isFar(exit, input)) {
            return original.call(input, stack, side, simulate);
        }
        final ItemStack before = stack.stack;
        final ItemStack remainder = IPCreatePortalBelts.handing(exit, () -> original.call(input, stack, exit.farFacing(), simulate));
        if (!simulate && !ItemStack.matches(remainder, before)) {
            IPCreatePortalBelts.placeAtPlane(exit, input);
        }
        return remainder;
    }
}
