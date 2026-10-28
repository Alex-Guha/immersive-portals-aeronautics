/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.simulated_team.simulated.util.hold_interaction.BlockHoldInteraction;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Moves a Create Aeronautics (Simulated) hold to its ship's new plot after the ship went to another dimension
 * ({@code IPHandlePortalsClient}).
 */
@Mixin(value = BlockHoldInteraction.class, remap = false)
public interface IPBlockHoldInteractionAccessor {
    @Accessor(value = "interactionPos", remap = false)
    void ip_setInteractionPos(BlockPos pos);
}
