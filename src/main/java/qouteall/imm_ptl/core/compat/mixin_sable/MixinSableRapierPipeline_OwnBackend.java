/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.physics.impl.rapier.RapierPhysicsPipeline;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery;
import ipaeronautics.rapier.RapierExt;
import net.minecraft.CrashReport;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipJoints;
import qouteall.imm_ptl.core.compat.sable_physics.IPFusedPhysicsStep;
import qouteall.imm_ptl.core.compat.sable_physics.IPPhysicsBackend;
import qouteall.imm_ptl.core.compat.sable_physics.IPRapierColliderBakeryLevel;

/**
 * Sable's Rapier pipeline driving our library (docs/lessons/physics-backend.md, D3). Each hook does nothing unless
 * {@link IPPhysicsBackend#isOwnBackendActive()}, so Sable's own library runs Sable's code unchanged:
 * <ul>
 * <li>the constructor binds the collider bakery to the level ({@code MixinSableRapierBakery_LevelBound});</li>
 * <li>{@code physicsTick} steps the shared native world only for the level the fused step names, and does the other
 * levels' per-chart upkeep ({@link RapierExt#stepChartOnly});</li>
 * <li>{@code addConstraint} records joints between ships ({@link IPShipJoints});</li>
 * <li>a link error names this mod, not Sable's tracker: the library is ours.</li>
 * </ul>
 */
@Mixin(value = RapierPhysicsPipeline.class, remap = false)
public class MixinSableRapierPipeline_OwnBackend {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Shadow
    @Final
    private RapierVoxelColliderBakery colliderBakery;

    @Inject(method = "<init>", at = @At("RETURN"), require = 1)
    private void ip_onCreated(ServerLevel level, CallbackInfo ci) {
        IPPhysicsBackend.decide();
        if (IPPhysicsBackend.isOwnBackendActive()) {
            ((IPRapierColliderBakeryLevel) this.colliderBakery).ip_bindLevel(level);
            ip_LOGGER.info("[IPPhysics] {}: using the ipa_rapier pipeline", level.dimension().location());
        } else {
            ip_LOGGER.info("[IPPhysics] {}: using Sable's stock Rapier pipeline", level.dimension().location());
        }
    }

    @WrapOperation(
        method = "physicsTick",
        at = @At(value = "INVOKE", target = "Ldev/ryanhcode/sable/physics/impl/rapier/Rapier3D;step(JD)V"),
        require = 1
    )
    private void ip_stepWorldOnce(long sceneHandle, double timeStep, Operation<Void> original) {
        if (!IPFusedPhysicsStep.stepWorld && IPPhysicsBackend.isOwnBackendActive()) {
            RapierExt.stepChartOnly(sceneHandle);
        } else {
            original.call(sceneHandle, timeStep);
        }
    }

    @Inject(method = "addConstraint", at = @At("RETURN"), require = 1)
    private <T extends PhysicsConstraintHandle> void ip_trackJoint(@Nullable PhysicsPipelineBody bodyA,
                                                                   @Nullable PhysicsPipelineBody bodyB,
                                                                   PhysicsConstraintConfiguration<T> configuration,
                                                                   CallbackInfoReturnable<T> cir) {
        final T constraint = cir.getReturnValue();
        if (constraint != null && IPPhysicsBackend.isOwnBackendActive()) {
            IPShipJoints.track(bodyA, bodyB, constraint);
        }
    }

    @ModifyArg(
        method = "init",
        at = @At(value = "INVOKE", target = "Lorg/slf4j/Logger;error(Ljava/lang/String;Ljava/lang/Throwable;)V"),
        index = 0
    )
    private String ip_linkErrorMessage(String message) {
        return IPPhysicsBackend.isOwnBackendActive()
            ? "Immersive Portals: Aeronautics has failed to link with its ipa_rapier physics library (loaded in place of Sable's). Please report this to Immersive Portals: Aeronautics, not to Sable"
            : message;
    }

    @WrapOperation(
        method = "init",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/CrashReport;forThrowable(Ljava/lang/Throwable;Ljava/lang/String;)Lnet/minecraft/CrashReport;")
    )
    private CrashReport ip_linkErrorCrash(Throwable cause, String title, Operation<CrashReport> original,
                                          @Local UnsatisfiedLinkError error) {
        if (!IPPhysicsBackend.isOwnBackendActive()) {
            return original.call(cause, title);
        }
        return original.call(cause != null ? cause : error, "Immersive Portals: Aeronautics linking with its ipa_rapier physics library");
    }
}
