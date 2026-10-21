/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.kinetics.RotationPropagator;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import java.util.List;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Create's speed factor of a block's shaft on one face (gearboxes and gearshifts turn some faces the other way), and
 * the kinetic blocks Create joins a block to.
 */
@Mixin(value = RotationPropagator.class, remap = false)
public interface IPRotationPropagatorInvoker {
    @Invoker("getAxisModifier")
    static float ip$getAxisModifier(final KineticBlockEntity be, final Direction direction) {
        throw new AssertionError();
    }

    @Invoker("getConnectedNeighbours")
    static List<KineticBlockEntity> ip$getConnectedNeighbours(final KineticBlockEntity be) {
        throw new AssertionError();
    }
}
