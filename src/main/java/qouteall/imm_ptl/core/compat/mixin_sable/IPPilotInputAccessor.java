/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.letitwalk.client.PilotInput;
import net.minecraft.core.BlockPos;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Let It Walk's piloted controls position ({@code PilotInput.active}), which follows the walker to its new plot. */
@Mixin(value = PilotInput.class, remap = false)
public interface IPPilotInputAccessor {
    @Accessor("active")
    static BlockPos ip_getActive() {
        throw new AssertionError();
    }

    @Accessor("active")
    static void ip_setActive(final BlockPos pos) {
        throw new AssertionError();
    }
}
