/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.AssemblyException;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The message a linear actuator shows on goggles ({@code lastException} is protected). */
@Mixin(value = LinearActuatorBlockEntity.class, remap = false)
public interface IPLinearActuatorAccessor {
    @Accessor("lastException")
    void ip_setLastException(AssemblyException exception);
}
