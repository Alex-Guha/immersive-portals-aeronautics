/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.IPDynLightsSuppression;
import qouteall.imm_ptl.core.compat.IPSodiumDynamicLightsExt;
import toni.sodiumdynamiclights.DynamicLightSource;
import toni.sodiumdynamiclights.SodiumDynamicLights;

import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;

/**
 * What {@code IPStraddleDynLights} needs from SodiumDynamicLights to light both halves of a straddling ship: the
 * current sources (to find Create Dynamic Lights' lights of the ship's far-half lamps), and leaving out of the light
 * at a block the sources it suppresses ({@link IPDynLightsSuppression}).
 */
@Mixin(value = SodiumDynamicLights.class, remap = false)
public abstract class MixinSodiumDynamicLights_StraddleLights implements IPSodiumDynamicLightsExt {
    @Shadow
    @Final
    private Set<DynamicLightSource> dynamicLightSources;

    @Shadow
    @Final
    private ReentrantReadWriteLock lightSourcesLock;

    @Override
    public void ip_forEachSource(Consumer<DynamicLightSource> action) {
        lightSourcesLock.readLock().lock();
        try {
            for (DynamicLightSource source : dynamicLightSources) {
                action.accept(source);
            }
        }
        finally {
            lightSourcesLock.readLock().unlock();
        }
    }

    @WrapOperation(
        method = "getDynamicLightLevel(Lnet/minecraft/core/BlockPos;)D",
        at = @At(
            value = "INVOKE",
            target = "Ltoni/sodiumdynamiclights/SodiumDynamicLights;maxDynamicLightLevel(Lnet/minecraft/core/BlockPos;Ltoni/sodiumdynamiclights/DynamicLightSource;D)D"
        )
    )
    private double ip_skipSuppressed(
        BlockPos pos, DynamicLightSource source, double currentLightLevel, Operation<Double> original
    ) {
        Set<Object> suppressed = IPDynLightsSuppression.suppressed;
        if (!suppressed.isEmpty() && suppressed.contains(source)) {
            return currentLightLevel;
        }
        return original.call(pos, source, currentLightLevel);
    }
}
