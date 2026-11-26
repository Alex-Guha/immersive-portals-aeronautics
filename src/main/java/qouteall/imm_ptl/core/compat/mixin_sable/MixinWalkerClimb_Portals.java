/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.letitwalk.walker.WalkerClimb;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.sable_integration.IPWalkerTerrain;

/** Let It Walk's search for a wall or ledge to climb past a portal reads the destination ({@link IPWalkerTerrain}). */
@Mixin(value = WalkerClimb.class, remap = false)
public class MixinWalkerClimb_Portals {
    @WrapMethod(method = "clip", require = 0)
    private static BlockHitResult ip_clipThroughPortals(final Level level, final ServerSubLevel own, final Vec3 from, final Vec3 to,
                                                       final Operation<BlockHitResult> original) {
        return IPWalkerTerrain.alongRay(level, from, to, (in, a, b) -> original.call(in, own, a, b), IPWalkerTerrain::unmapHit);
    }

    @WrapMethod(method = "open", require = 0)
    private static boolean ip_openThroughPortals(final Level level, final Vec3 p, final Operation<Boolean> original) {
        return IPWalkerTerrain.atPoint(level, p, (in, q) -> original.call(in, q));
    }

    @WrapMethod(method = "loaded", require = 0)
    private static boolean ip_loadedThroughPortals(final Level level, final Vec3 p, final Operation<Boolean> original) {
        return IPWalkerTerrain.atPoint(level, p, (in, q) -> original.call(in, q));
    }
}
