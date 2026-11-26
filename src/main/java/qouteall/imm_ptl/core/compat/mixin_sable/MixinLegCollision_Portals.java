/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.letitwalk.walker.LegCollision;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.sable_integration.IPWalkerTerrain;

/** Let It Walk's check of a knee's path past a portal reads the destination's blocks ({@link IPWalkerTerrain}). */
@Mixin(value = LegCollision.class, remap = false)
public class MixinLegCollision_Portals {
    @WrapMethod(method = "solidAt", require = 0)
    private static boolean ip_solidThroughPortals(final Level level, final Vec3 p, final Operation<Boolean> original) {
        return IPWalkerTerrain.atPoint(level, p, (in, q) -> original.call(in, q));
    }
}
