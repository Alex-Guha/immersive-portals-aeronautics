/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.Contraption;
import com.simibubi.create.content.contraptions.render.ClientContraption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The contraption a {@code ClientContraption} renders. */
@Mixin(value = ClientContraption.class, remap = false)
public interface IPClientContraptionAccessor {
    @Accessor("contraption")
    Contraption ip_getContraption();
}
