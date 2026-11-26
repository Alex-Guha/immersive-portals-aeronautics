/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.letitwalk.walker.TerrainProbe;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.sable_integration.IPWalkerTerrain;

/** Let It Walk's ground probe past a portal reads the destination's ground ({@link IPWalkerTerrain}). */
@Mixin(value = TerrainProbe.class, remap = false)
public class MixinTerrainProbe_Portals {
    @WrapMethod(method = "cast", require = 0)
    private static TerrainProbe.Ground ip_castThroughPortals(final Level level, final Vec3 from, final Vec3 to, final SubLevel ignore,
                                                            final Operation<TerrainProbe.Ground> original) {
        return IPWalkerTerrain.alongRay(level, from, to, (in, a, b) -> original.call(in, a, b, ignore),
            (ground, map) -> new TerrainProbe.Ground(map.unmapPoint(ground.point()), map.unmapDirection(ground.normal()), ground.state(),
                ground.friction()));
    }
}
