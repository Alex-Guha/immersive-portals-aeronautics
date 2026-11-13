/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A driver's context in Create's server controls handler ({@code ControlsServerHandler.ControlsContext} is package-private). */
@Mixin(
    targets = "com.simibubi.create.content.contraptions.actors.trainControls.ControlsServerHandler$ControlsContext",
    remap = false
)
public interface IPControlsContextAccessor {
    @Accessor("entity")
    AbstractContraptionEntity ip_getEntity();

    @Accessor("entity")
    void ip_setEntity(AbstractContraptionEntity entity);
}
