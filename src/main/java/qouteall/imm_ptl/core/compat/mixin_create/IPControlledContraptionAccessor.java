/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.ControlledContraptionEntity;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The controller a contraption belongs to ({@code ControlledContraptionEntity.controllerPos} is protected). */
@Mixin(value = ControlledContraptionEntity.class, remap = false)
public interface IPControlledContraptionAccessor {
    @Accessor("controllerPos")
    BlockPos ip_getControllerPos();

    @Accessor("prevAngle")
    void ip_setPrevAngle(float angle);
}
