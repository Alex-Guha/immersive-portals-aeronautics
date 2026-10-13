/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The mapped render pose follows IPSable 0.5.0's IplClientSubLevelPoseOverrideMixin by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.client.Minecraft;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPClientSubLevelExt;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleHandoffLatch;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;

/**
 * While a straddling ship's far half is drawn in the destination dimension, its render pose is its pose mapped through
 * the portal ({@link IPStraddleRender#forEachProjection}). An old copy held across a hand-off is drawn where its
 * replacement is ({@link IPStraddleHandoffLatch#followedPose}). Everything Sable draws a ship with reads one of these two.
 */
@Mixin(value = ClientSubLevel.class, remap = false)
public class MixinSableClientSubLevel_StraddlePose implements IPClientSubLevelExt {
    @Shadow
    private float lastRenderPosePartialTick;

    @Override
    public void ip_invalidateRenderPose() {
        this.lastRenderPosePartialTick = -1.0f;
    }

    /**
     * A new copy of a ship just handed off plays on the old copy's timeline for a while
     * ({@link IPStraddleHandoffLatch#adjustPointer}).
     */
    @ModifyArg(method = "tick", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/network/client/SubLevelSnapshotInterpolator;tick(D)V"), require = 0)
    private double ip_handoffTimeline(final double pointer) {
        return IPStraddleHandoffLatch.adjustPointer((ClientSubLevel) (Object) this, pointer);
    }
    @Inject(method = "renderPose()Ldev/ryanhcode/sable/companion/math/Pose3dc;", at = @At("HEAD"), cancellable = true, require = 0)
    private void ip_projectedPose(final CallbackInfoReturnable<Pose3dc> cir) {
        if (IPStraddleRender.isProjecting()) {
            final Pose3dc pose = IPStraddleRender.overridePose((ClientSubLevel) (Object) this);
            if (pose != null) {
                cir.setReturnValue(pose);
                return;
            }
        }
        final Pose3dc followed = IPStraddleHandoffLatch.followedPose((ClientSubLevel) (Object) this,
                Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true), true);
        if (followed != null) {
            cir.setReturnValue(followed);
        }
    }

    @Inject(method = "renderPose(F)Ldev/ryanhcode/sable/companion/math/Pose3dc;", at = @At("HEAD"), cancellable = true, require = 0)
    private void ip_projectedPoseAt(final float partialTick, final CallbackInfoReturnable<Pose3dc> cir) {
        if (IPStraddleRender.isProjecting()) {
            final Pose3dc pose = IPStraddleRender.overridePose((ClientSubLevel) (Object) this);
            if (pose != null) {
                cir.setReturnValue(pose);
                return;
            }
        }
        final Pose3dc followed = IPStraddleHandoffLatch.followedPose((ClientSubLevel) (Object) this, partialTick, false);
        if (followed != null) {
            cir.setReturnValue(followed);
        }
    }
}
