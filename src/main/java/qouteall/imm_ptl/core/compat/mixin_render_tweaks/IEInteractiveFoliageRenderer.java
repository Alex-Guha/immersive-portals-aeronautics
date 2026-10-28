/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_render_tweaks;

import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(targets = "net.karto.mc2.mc2_interactivefoliage.gpu.GpuFoliageRenderer", remap = false)
public interface IEInteractiveFoliageRenderer {
    @Invoker("deactivate")
    static void ip_deactivate(Minecraft client) {
        throw new RuntimeException();
    }
}
