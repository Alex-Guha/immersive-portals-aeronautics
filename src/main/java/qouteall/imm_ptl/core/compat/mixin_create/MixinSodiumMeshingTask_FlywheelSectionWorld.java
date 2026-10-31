/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.create_integration.IPFlywheelSectionWorld;

/**
 * Sodium's terrain compiles: Flywheel's render predicate ({@code SodiumCompat}) decides inside
 * {@code ExtendedBlockEntityType.shouldRender} whether the block entity becomes a visual; that call goes by the block
 * entity's own world ({@link IPFlywheelSectionWorld}).
 */
@Mixin(value = ChunkBuilderMeshingTask.class, remap = false)
public class MixinSodiumMeshingTask_FlywheelSectionWorld {
    @WrapOperation(
        method = "execute(Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildContext;Lnet/caffeinemc/mods/sodium/client/util/task/CancellationToken;)Lnet/caffeinemc/mods/sodium/client/render/chunk/compile/ChunkBuildOutput;",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/chunk/ExtendedBlockEntityType;shouldRender(Lnet/minecraft/world/level/block/entity/BlockEntityType;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/entity/BlockEntity;)Z"
        )
    )
    private boolean ip_flywheelSectionWorld(
        BlockEntityType<?> type, BlockGetter getter, BlockPos pos, BlockEntity blockEntity, Operation<Boolean> original
    ) {
        boolean outer = IPFlywheelSectionWorld.begin();
        try {
            return original.call(type, getter, pos, blockEntity);
        }
        finally {
            IPFlywheelSectionWorld.end(outer);
        }
    }
}
