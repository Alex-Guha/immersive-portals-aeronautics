/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.fluids.FlowSource;
import com.simibubi.create.content.fluids.FluidNetwork;
import java.util.List;
import net.createmod.catnip.data.Pair;
import net.createmod.catnip.math.BlockFace;
import net.neoforged.neoforge.fluids.FluidStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A Create fluid network's endpoints and fluid (package-private), for portal pipes filling it from the other side. */
@Mixin(value = FluidNetwork.class, remap = false)
public interface IPFluidNetworkAccessor {
    @Accessor("targets")
    List<Pair<BlockFace, FlowSource>> ip$targets();

    @Accessor("fluid")
    FluidStack ip$fluid();
}
