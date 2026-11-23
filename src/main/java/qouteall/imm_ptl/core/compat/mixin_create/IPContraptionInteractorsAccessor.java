/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.api.behaviour.interaction.MovingInteractionBehaviour;
import com.simibubi.create.content.contraptions.Contraption;
import java.util.Map;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A contraption's clickable parts (controls, doors, levers; {@code Contraption.interactors} is protected). */
@Mixin(value = Contraption.class, remap = false)
public interface IPContraptionInteractorsAccessor {
    @Accessor("interactors")
    Map<BlockPos, MovingInteractionBehaviour> ip_getInteractors();
}
