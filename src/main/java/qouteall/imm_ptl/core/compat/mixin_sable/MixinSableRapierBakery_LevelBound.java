/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.physics.impl.rapier.collider.RapierVoxelColliderBakery;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.sable_physics.IPRapierColliderBakeryLevel;
import qouteall.imm_ptl.core.compat.sable_physics.LevelBoundCollisionCallback;

/**
 * With our library, one native world steps every level at once, so a block's collision callback is bound to the level
 * of the bakery that built its collider ({@link LevelBoundCollisionCallback}; docs/lessons/physics-backend.md). The
 * level is set only while our library runs ({@code MixinSableRapierPipeline_OwnBackend}); without it Sable's callback
 * is used as is.
 */
@Mixin(value = RapierVoxelColliderBakery.class, remap = false)
public class MixinSableRapierBakery_LevelBound implements IPRapierColliderBakeryLevel {
    @Unique
    @Nullable
    private ServerLevel ip_level;

    @Override
    public void ip_bindLevel(ServerLevel level) {
        this.ip_level = level;
    }

    @ModifyExpressionValue(
        method = "buildPhysicsDataForBlock",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/api/block/BlockWithSubLevelCollisionCallback;sable$getCallback(Lnet/minecraft/world/level/block/state/BlockState;)Ldev/ryanhcode/sable/api/physics/callback/BlockSubLevelCollisionCallback;"
        ),
        require = 1
    )
    private BlockSubLevelCollisionCallback ip_bindToLevel(@Nullable BlockSubLevelCollisionCallback callback) {
        return callback == null || this.ip_level == null ? callback : new LevelBoundCollisionCallback(callback, this.ip_level);
    }
}
