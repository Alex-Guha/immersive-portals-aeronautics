/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalShafts;
import qouteall.imm_ptl.core.compat.create_integration.IPPortalShaftEnd;

/**
 * Shafts through portals ({@link IPCreatePortalShafts}): the portal state of a kinetic block entity, saved with it.
 * A driven end's Create source is the portal block in front of it, where no block entity is: Create's periodic check
 * would drop such a source, so it is skipped while the portal drives the end. The stress the two networks share is
 * added by {@code MixinKineticNetwork_PortalShafts}.
 */
@Mixin(value = KineticBlockEntity.class, remap = false)
public abstract class MixinKineticBlockEntity_PortalShafts implements IPPortalShaftEnd {
    /** Not the 2.4.19-2.4.21 tag ("IPPortalShaft"), whose ends were generators and whose numbers were per RPM. */
    @Unique
    private static final String ip$TAG = "IPPortalDrive";

    @Shadow
    protected float capacity;
    @Shadow
    protected float stress;
    @Shadow
    protected boolean wasMoved;
    @Shadow
    public @Nullable BlockPos source;

    @Unique
    private float ip$portalSpeed;
    @Unique
    private @Nullable Direction ip$portalSide;
    @Unique
    private float ip$portalCapacity;
    @Unique
    private float ip$portalLoad;

    @Override
    public float ip$getPortalSpeed() {
        return ip$portalSpeed;
    }

    @Override
    public void ip$setPortalSpeed(final float speed) {
        ip$portalSpeed = speed;
    }

    @Override
    public @Nullable Direction ip$getPortalSide() {
        return ip$portalSide;
    }

    @Override
    public void ip$setPortalSide(final @Nullable Direction side) {
        ip$portalSide = side;
    }

    @Override
    public float ip$getPortalCapacity() {
        return ip$portalCapacity;
    }

    @Override
    public void ip$setPortalCapacity(final float capacity) {
        ip$portalCapacity = capacity;
    }

    @Override
    public float ip$getPortalLoad() {
        return ip$portalLoad;
    }

    @Override
    public void ip$setPortalLoad(final float load) {
        ip$portalLoad = load;
    }

    @Override
    public float ip$getNetworkCapacity() {
        return capacity;
    }

    @Override
    public float ip$getNetworkStress() {
        return stress;
    }

    @Override
    public boolean ip$isPortalDriven() {
        return ip$portalSpeed != 0 && ip$portalSide != null && source != null
            && source.equals(((KineticBlockEntity) (Object) this).getBlockPos().relative(ip$portalSide));
    }

    @Inject(method = "validateKinetics", at = @At("HEAD"), cancellable = true)
    private void ip$keepPortalSource(final CallbackInfo ci) {
        if (ip$isPortalDriven()) {
            ci.cancel();
        }
    }

    @Inject(method = "initialize", at = @At("TAIL"))
    private void ip$findPortal(final CallbackInfo ci) {
        IPCreatePortalShafts.onInitialize((KineticBlockEntity) (Object) this);
    }

    @Inject(method = "write", at = @At("TAIL"))
    private void ip$write(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket, final CallbackInfo ci) {
        if (clientPacket || !ip$hasPortalState()) {
            return;
        }
        final CompoundTag tag = new CompoundTag();
        if (ip$portalSpeed != 0 && ip$portalSide != null) {
            tag.putFloat("Speed", ip$portalSpeed);
            tag.putInt("Side", ip$portalSide.get3DDataValue());
        }
        if (ip$portalCapacity != 0) {
            tag.putFloat("Capacity", ip$portalCapacity);
        }
        if (ip$portalLoad != 0) {
            tag.putFloat("Load", ip$portalLoad);
        }
        compound.put(ip$TAG, tag);
    }

    @Inject(method = "read", at = @At("RETURN"))
    private void ip$read(final CompoundTag compound, final HolderLookup.Provider registries, final boolean clientPacket, final CallbackInfo ci) {
        if (clientPacket) {
            return;
        }
        ip$portalSpeed = 0;
        ip$portalSide = null;
        ip$portalCapacity = 0;
        ip$portalLoad = 0;
        // Create reads no kinetic state into a block placed after a contraption moved it; neither do we
        if (wasMoved || !compound.contains(ip$TAG)) {
            return;
        }
        final CompoundTag tag = compound.getCompound(ip$TAG);
        if (tag.contains("Speed") && tag.contains("Side")) {
            ip$portalSpeed = tag.getFloat("Speed");
            ip$portalSide = Direction.from3DDataValue(tag.getInt("Side"));
        }
        ip$portalCapacity = tag.getFloat("Capacity");
        ip$portalLoad = tag.getFloat("Load");
        if (ip$portalSpeed != 0 && !ip$isPortalDriven()) {
            // saved somewhere else (a ship moved to another plot): its source is not this block's portal any more
            ip$portalSpeed = 0;
            ip$portalSide = null;
            ip$portalCapacity = 0;
        }
    }
}
