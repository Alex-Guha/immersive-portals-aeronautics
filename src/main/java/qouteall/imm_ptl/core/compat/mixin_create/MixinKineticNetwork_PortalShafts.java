/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.kinetics.KineticNetwork;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import java.util.Map;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalShafts;
import qouteall.imm_ptl.core.compat.create_integration.IPPortalShaftEnd;

/**
 * Shafts through portals ({@link IPCreatePortalShafts}): the stress two networks joined through a portal share, added
 * where Create sums a network up, so it works whatever the end blocks are. Each end adds the far side's own capacity
 * (it is kept among the network's sources for that, as a generator would be) and the far side's own stress, so both
 * networks show the totals one network would. Create's saved network totals include both, so a loading end takes its
 * share out of the network's unloaded totals, as Create does for its own capacity and impact.
 */
@Mixin(value = KineticNetwork.class, remap = false)
public abstract class MixinKineticNetwork_PortalShafts {
    @Shadow
    public Map<KineticBlockEntity, Float> sources;
    @Shadow
    public Map<KineticBlockEntity, Float> members;
    @Shadow
    private float unloadedCapacity;
    @Shadow
    private float unloadedStress;

    @Inject(method = "getActualCapacityOf", at = @At("RETURN"), cancellable = true)
    private void ip$portalCapacity(final KineticBlockEntity be, final CallbackInfoReturnable<Float> cir) {
        final float lent = ((IPPortalShaftEnd) be).ip$getPortalCapacity();
        if (lent != 0) {
            cir.setReturnValue(cir.getReturnValueF() + lent);
        }
    }

    @Inject(method = "getActualStressOf", at = @At("RETURN"), cancellable = true)
    private void ip$portalLoad(final KineticBlockEntity be, final CallbackInfoReturnable<Float> cir) {
        final float load = ((IPPortalShaftEnd) be).ip$getPortalLoad();
        if (load != 0) {
            cir.setReturnValue(cir.getReturnValueF() + load);
        }
    }

    @Inject(method = "add", at = @At("HEAD"))
    private void ip$addPortalSource(final KineticBlockEntity be, final CallbackInfo ci) {
        if (!members.containsKey(be) && ip$countsCapacity((IPPortalShaftEnd) be)) {
            sources.putIfAbsent(be, 0f);
        }
    }

    @Inject(method = "addSilently", at = @At("HEAD"))
    private void ip$addPortalSourceSilently(final KineticBlockEntity be, final float lastCapacity, final float lastStress, final CallbackInfo ci) {
        if (members.containsKey(be)) {
            return;
        }
        final IPPortalShaftEnd end = (IPPortalShaftEnd) be;
        if (ip$countsCapacity(end)) {
            sources.putIfAbsent(be, 0f);
        }
        // Create clamps both at 0 after its own subtraction
        unloadedCapacity -= end.ip$getPortalCapacity();
        unloadedStress -= end.ip$getPortalLoad();
    }

    @Unique
    private static boolean ip$countsCapacity(final IPPortalShaftEnd end) {
        return end.ip$getPortalSpeed() != 0 || end.ip$getPortalCapacity() != 0;
    }

    @Inject(method = "remove", at = @At("HEAD"))
    private void ip$removePortalSource(final KineticBlockEntity be, final CallbackInfo ci) {
        // Create only removes generators from the sources; a portal end that is none must go too
        if (members.containsKey(be)) {
            sources.remove(be);
        }
    }
}
