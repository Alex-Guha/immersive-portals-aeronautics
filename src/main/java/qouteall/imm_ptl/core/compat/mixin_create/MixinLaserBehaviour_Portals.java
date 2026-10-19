/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.mojang.logging.LogUtils;
import dev.simulated_team.simulated.content.blocks.lasers.LaserBehaviour;
import dev.simulated_team.simulated.index.SimTags;
import net.createmod.catnip.data.Couple;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPLaserPortals;
import qouteall.imm_ptl.core.compat.sable_integration.IPLaserPortalsClient;

import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Create Aeronautics' (Simulated) lasers through portals ({@link IPLaserPortals}): after Simulated's own cast, a beam
 * that reaches a portal before its hit is continued on the other side, and its hit results become those of its last
 * leg. Beams that meet no portal keep Simulated's results untouched.
 */
@Mixin(value = LaserBehaviour.class, remap = false)
public abstract class MixinLaserBehaviour_Portals implements IPLaserPortals.Holder {
    @Unique
    private static final Logger IP_LOGGER = LogUtils.getLogger();
    @Unique
    private static final Predicate<Entity> IP_HITS = entity -> !entity.getType().is(SimTags.Misc.LASER_BLACKLIST) && !entity.isSpectator();
    @Unique
    private static boolean ip_warned;

    @Shadow
    private BlockHitResult blockHitResult;
    @Shadow
    private EntityHitResult entityHitResult;
    @Shadow
    private HitResult closestHitResult;
    @Shadow
    private ClipContext.Block blockCollide;
    @Shadow
    private ClipContext.Fluid fluidCollide;
    @Shadow
    @Final
    private Supplier<Couple<Vec3>> laserPositions;

    @Unique
    @Nullable
    private IPLaserPortals.Trace ip_trace;

    @Override
    public IPLaserPortals.@Nullable Trace ip$portalTrace() {
        return this.ip_trace;
    }

    @Inject(method = "castRay", at = @At("TAIL"), require = 0)
    private void ip_throughPortals(final CallbackInfo ci) {
        final LaserBehaviour self = (LaserBehaviour) (Object) this;
        final Level level = self.blockEntity.getLevel();
        IPLaserPortals.Trace trace = null;
        if (level != null && this.blockHitResult != null) {
            try {
                final Couple<Vec3> positions = this.laserPositions.get();
                trace = IPLaserPortals.continueThrough(level, positions.getFirst(), positions.getSecond(), this.closestHitResult,
                        this.blockCollide, this.fluidCollide, IP_HITS);
            } catch (final Throwable e) {
                if (!ip_warned) {
                    ip_warned = true;
                    IP_LOGGER.warn("[IPLaser] continuing a laser through a portal failed", e);
                }
            }
        }
        this.ip_trace = trace;
        if (trace != null) {
            this.blockHitResult = trace.block();
            this.entityHitResult = trace.entity();
            this.closestHitResult = trace.closest();
        }
        if (level != null && level.isClientSide()) {
            IPLaserPortalsClient.track(self, trace);
        }
    }
}
