/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.fluids.FluidNetwork;
import com.simibubi.create.content.fluids.PipeConnection;
import java.util.Optional;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The network a pipe face starts ({@code PipeConnection.network} is package-private); portal pipes fill its targets. */
@Mixin(value = PipeConnection.class, remap = false)
public interface IPPipeConnectionAccessor {
    @Accessor("network")
    Optional<FluidNetwork> ip$network();
}
