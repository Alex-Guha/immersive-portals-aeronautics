/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import net.minecraft.client.renderer.chunk.SectionCompiler;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.create_integration.IPFlywheelSectionWorld;

/**
 * Vanilla section compiles (Sable's ships, also with Sodium): Flywheel's {@code SectionCompilerMixin} decides at the
 * head of {@code handleBlockEntity} whether the block entity becomes a visual; the whole method is wrapped so that
 * decision goes by the block entity's own world ({@link IPFlywheelSectionWorld}).
 */
@Mixin(SectionCompiler.class)
public class MixinSectionCompiler_FlywheelSectionWorld {
    @WrapMethod(method = "handleBlockEntity")
    private void ip_flywheelSectionWorld(SectionCompiler.Results results, BlockEntity blockEntity, Operation<Void> original) {
        boolean outer = IPFlywheelSectionWorld.begin();
        try {
            original.call(results, blockEntity);
        }
        finally {
            IPFlywheelSectionWorld.end(outer);
        }
    }
}
