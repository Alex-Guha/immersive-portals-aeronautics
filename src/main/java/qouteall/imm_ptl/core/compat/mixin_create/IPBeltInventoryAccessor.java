/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Items queued onto a belt this tick ({@code BeltInventory.toInsert} is package-private); belts through portals place them. */
@Mixin(value = BeltInventory.class, remap = false)
public interface IPBeltInventoryAccessor {
    @Accessor("toInsert")
    List<TransportedItemStack> ip$toInsert();
}
