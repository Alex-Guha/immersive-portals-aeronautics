/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.AABB;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleShields;

import java.util.List;

/**
 * Shield Generators: the shield of a straddling ship is mirrored into the portal's destination
 * ({@link IPStraddleShields}). A mirrored shield stays registered while its generator is loaded in the ship's dimension,
 * and an impact on it is recorded on that generator.
 */
@Mixin(targets = "com.anton.shieldgenerators.ShieldRegistry", remap = false)
public class MixinShieldRegistry_StraddleMirror {
    @Inject(method = "update", at = @At("TAIL"), require = 0)
    private static void ip_mirrorThroughPortals(final ServerLevel level, final BlockPos generatorPos, final AABB shieldBounds,
                                                final AABB renderBounds, final List<?> collisionPanels, final List<?> collisionTriangles,
                                                @Nullable final Pose3dc shieldPose, final long gameTime, final double shieldStrength,
                                                final CallbackInfo ci) {
        IPStraddleShields.afterUpdate(level, generatorPos, shieldBounds, renderBounds, collisionPanels, collisionTriangles,
                shieldPose, shieldStrength);
    }

    @Redirect(method = "purge", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;isLoaded(Lnet/minecraft/core/BlockPos;)Z"), require = 0)
    private static boolean ip_mirrorLoaded(final ServerLevel level, final BlockPos pos) {
        return IPStraddleShields.isLoaded(level, pos);
    }

    @Redirect(method = {"recordShieldImpact", "recordExplosionImpact"}, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;getBlockEntity(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/entity/BlockEntity;"),
            require = 0)
    private static BlockEntity ip_mirrorGenerator(final ServerLevel level, final BlockPos pos) {
        return IPStraddleShields.blockEntityFor(level, pos);
    }
}
