/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.Train;
import com.simibubi.create.content.trains.entity.TravellingPoint;
import com.simibubi.create.content.trains.graph.TrackNodeLocation;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateCarriageCrossing;

/**
 * Create places the part of a carriage lying in the other dimension by extrapolating from a wheel point towards the
 * portal-track node: {@code lerp(offset / |start - node|, start, node)} in {@code Carriage.pivoted}. With the wheel point
 * exactly on the node that divides by zero, and the copy's anchors become NaN; Create then saves them and crashes
 * creating the copy ("Entity has invalid position", seen with a single-bogey carriage at rest with its trailing wheel
 * point on the overworld node). Within 1e-4 of the node the extrapolation follows the wheel point's track direction
 * past the node instead, which is the limit of Create's formula.
 */
@Mixin(value = Carriage.class, remap = false)
public abstract class MixinCarriage_PortalPivotGuard {
    @Shadow
    public Train train;

    @Shadow
    public abstract TravellingPoint getLeadingPoint();

    @Inject(method = "pivoted", at = @At("HEAD"), cancellable = true, require = 0, expect = 0)
    private void ip_wheelPointOnPortalNode(final Carriage.DimensionalCarriageEntity dce, final ResourceKey<Level> dimension,
                                           final TravellingPoint start, final double offset, final boolean leadingUpsideDown,
                                           final boolean trailingUpsideDown, final CallbackInfoReturnable<Vec3> cir) {
        if (train == null || train.graph == null || start.edge == null) {
            return;
        }
        final boolean leading = start == getLeadingPoint();
        final TrackNodeLocation pivot = dce.findPivot(dimension, leading);
        if (pivot == null) {
            return;
        }
        final boolean flipped = !leading && leadingUpsideDown != trailingUpsideDown;
        final Vec3 startVec = start.getPosition(train.graph, flipped);
        final Vec3 portalVec = pivot.getLocation().add(0.0, leadingUpsideDown ? -1.0 : 1.0, 0.0);
        if (!IPCreateCarriageCrossing.isFinite(startVec) || startVec.distanceToSqr(portalVec) > 1.0E-8) {
            return;
        }
        final double length = start.edge.getLength();
        final Vec3 along = length > 0.0 ? start.edge.getDirectionAt(start.position) : Vec3.ZERO;
        if (!IPCreateCarriageCrossing.isFinite(along) || along.lengthSqr() < 1.0E-8) {
            cir.setReturnValue(portalVec);
            return;
        }
        // past the node the wheel point stands on: node2 (end of its edge) or node1 (start)
        final Vec3 out = start.position >= length / 2.0 ? along : along.scale(-1.0);
        cir.setReturnValue(portalVec.add(out.normalize().scale(offset)));
    }
}
