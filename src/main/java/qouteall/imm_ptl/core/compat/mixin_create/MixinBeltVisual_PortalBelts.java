/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.simibubi.create.content.kinetics.base.KineticBlockEntityVisual;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltPart;
import com.simibubi.create.content.kinetics.belt.BeltRenderer;
import com.simibubi.create.content.kinetics.belt.BeltVisual;
import com.simibubi.create.content.processing.burner.ScrollInstance;
import com.simibubi.create.foundation.render.AllInstanceTypes;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.FlatLit;
import dev.engine_room.flywheel.lib.model.Models;
import dev.engine_room.flywheel.lib.model.baked.PartialModel;
import net.createmod.catnip.render.SpriteShiftEntry;
import net.minecraft.core.Direction;
import net.minecraft.world.item.DyeColor;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalBeltLook;

/**
 * A belt end at a portal runs straight on into the portal block ({@link IPCreatePortalBeltLook}): Create's straight
 * part in the end cell, and half of it in the portal block, scrolling with the belt; with a belt across not running
 * as one (the other way, or at another speed), the end's own loop instead, ending at the plane. Again whenever the
 * visual updates (a portal block appearing or going next to the end, or what the end meets changing, queues one).
 */
@Mixin(value = BeltVisual.class, remap = false)
public abstract class MixinBeltVisual_PortalBelts extends KineticBlockEntityVisual<BeltBlockEntity> {
    @Shadow
    @Final
    protected ScrollInstance[] belts;

    @Unique
    private Direction ip$portalSide;
    @Unique
    private ScrollInstance[] ip$halves;
    /** Two belts across the portal not running as one: the end's own loop, half a block on, instead. */
    @Unique
    private boolean ip$headOn;

    private MixinBeltVisual_PortalBelts(final VisualizationContext context, final BeltBlockEntity blockEntity, final float partialTick) {
        super(context, blockEntity, partialTick);
    }

    @Shadow
    protected abstract ScrollInstance setup(ScrollInstance key, boolean bottom, SpriteShiftEntry spriteShift);

    @WrapOperation(
        method = "<init>",
        at = @At(
            value = "INVOKE",
            target = "Lcom/simibubi/create/content/kinetics/belt/BeltRenderer;getBeltPartial(ZZZZ)Ldev/engine_room/flywheel/lib/model/baked/PartialModel;"
        )
    )
    private PartialModel ip$straightAtPortal(
        final boolean diagonal, final boolean start, final boolean end, final boolean bottom, final Operation<PartialModel> original
    ) {
        ip$portalSide = IPCreatePortalBeltLook.portalSide(level, pos, blockState);
        return ip$portalSide != null ? IPCreatePortalBeltLook.straight(bottom) : original.call(diagonal, start, end, bottom);
    }

    @Inject(method = "<init>", at = @At("TAIL"))
    private void ip$addHalves(final CallbackInfo ci) {
        ip$makeHalves();
    }

    @Inject(method = "update", at = @At("TAIL"))
    private void ip$updateHalves(final float partialTick, final CallbackInfo ci) {
        final Direction side = IPCreatePortalBeltLook.portalSide(level, pos, blockState);
        final boolean headOn = side != null && IPCreatePortalBeltLook.mode(level, pos) == IPCreatePortalBeltLook.Mode.APART;
        if (side == ip$portalSide && headOn != ip$headOn) {
            ip$deleteHalves();
            ip$makeHalves();
            return;
        }
        if (side != ip$portalSide) {
            // a portal block appeared or went: Create's parts and the halves again
            ip$portalSide = side;
            ip$deleteHalves();
            ip$remakeParts();
            ip$makeHalves();
            return;
        }
        if (ip$halves != null) {
            for (int i = 0; i < ip$halves.length; i++) {
                ip$setupHalf(ip$halves[i], i == 0);
            }
        }
    }

    @Inject(method = "updateLight", at = @At("TAIL"))
    private void ip$lightHalves(final float partialTick, final CallbackInfo ci) {
        if (ip$halves != null) {
            relight((FlatLit[]) ip$halves);
        }
    }

    @Inject(method = "_delete", at = @At("TAIL"))
    private void ip$deleteHalvesWithVisual(final CallbackInfo ci) {
        ip$deleteHalves();
    }

    @Unique
    private void ip$makeHalves() {
        if (ip$portalSide == null) {
            ip$halves = null;
            return;
        }
        final Direction facing = blockState.getValue(BeltBlock.HORIZONTAL_FACING);
        // BeltVisual.setup's turn for a horizontal belt
        final float yRot = facing.toYRot() + (facing.getAxis() == Direction.Axis.X ? 180 : 0);
        ip$headOn = IPCreatePortalBeltLook.mode(level, pos) == IPCreatePortalBeltLook.Mode.APART;
        final BeltPart part = blockState.getValue(BeltBlock.PART);
        ip$halves = new ScrollInstance[2];
        for (int i = 0; i < 2; i++) {
            final boolean bottom = i == 0;
            // straight on to the plane, or (apart) the cell's own end loop
            final PartialModel partial = ip$headOn
                ? BeltRenderer.getBeltPartial(false, part == BeltPart.START, part == BeltPart.END, bottom)
                : IPCreatePortalBeltLook.straight(bottom);
            ip$halves[i] = instancerProvider()
                .instancer(AllInstanceTypes.SCROLLING,
                    IPCreatePortalBeltLook.halfModel(partial, yRot, ip$headOn ? ip$portalSide.getOpposite() : ip$portalSide))
                .createInstance();
            ip$setupHalf(ip$halves[i], bottom);
        }
        relight((FlatLit[]) ip$halves);
    }

    @Unique
    private void ip$setupHalf(final ScrollInstance half, final boolean bottom) {
        final DyeColor color = blockEntity.color.orElse(null);
        setup(half, bottom, BeltRenderer.getSpriteShiftEntry(color, false, bottom));
        // the loop moved half a block on ends at the plane, less IPCreatePortalBeltLook.LOOP_SHORT
        final float shift = ip$headOn ? 0.5f - IPCreatePortalBeltLook.LOOP_SHORT : 1;
        half.position(getVisualPosition().getX() + ip$portalSide.getStepX() * shift, getVisualPosition().getY(),
            getVisualPosition().getZ() + ip$portalSide.getStepZ() * shift);
        if (ip$headOn) {
            // the texture repeats once a block: moved half a block, the loop's scroll starts half a turn later to line
            // up with the belt (as Create's bottom strap's does with its top)
            half.offset(0, (bottom ? 0.5f : 0) + 0.5f);
        }
        half.setChanged();
    }

    @Unique
    private void ip$deleteHalves() {
        if (ip$halves != null) {
            for (final ScrollInstance half : ip$halves) {
                half.delete();
            }
            ip$halves = null;
        }
    }

    /** Create's two parts again (horizontal belts only: diagonal ones never face a portal block here). */
    @Unique
    private void ip$remakeParts() {
        if (belts.length != 2) {
            return;
        }
        final BeltPart part = blockState.getValue(BeltBlock.PART);
        final DyeColor color = blockEntity.color.orElse(null);
        for (int i = 0; i < 2; i++) {
            final boolean bottom = i == 0;
            final PartialModel partial = ip$portalSide != null ? IPCreatePortalBeltLook.straight(bottom)
                : BeltRenderer.getBeltPartial(false, part == BeltPart.START, part == BeltPart.END, bottom);
            belts[i].delete();
            belts[i] = setup(instancerProvider().instancer(AllInstanceTypes.SCROLLING, Models.partial(partial)).createInstance(),
                bottom, BeltRenderer.getSpriteShiftEntry(color, false, bottom));
        }
        relight((FlatLit[]) belts);
    }
}
