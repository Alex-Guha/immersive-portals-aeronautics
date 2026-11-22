/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.engine_room.flywheel.lib.visual.AbstractBlockEntityVisual;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The block entity of a Flywheel block entity visual (protected). */
@Mixin(value = AbstractBlockEntityVisual.class, remap = false)
public interface IPBlockEntityVisualAccessor {
    @Accessor("blockEntity")
    BlockEntity ip_getBlockEntity();
}
