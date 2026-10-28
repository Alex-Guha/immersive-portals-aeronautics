/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import net.irisshaders.iris.Iris;
import net.irisshaders.iris.shaderpack.ShaderPack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Iris's current pack, put back after a pipeline is built from Euphoria's pack for its dimension (IPEuphoriaPacks). */
@Mixin(value = Iris.class, remap = false)
public interface IEIris_CurrentPack {
    @Accessor("currentPack")
    static void ip_setCurrentPack(ShaderPack pack) {
        throw new AssertionError();
    }
}
