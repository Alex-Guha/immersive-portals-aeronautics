/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import dev.letitwalk.content.WalkerCoreBlockEntity;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.sable_integration.IPWalkerTerrain;

/** A walker's tick: its terrain probes read past the portals it walks into ({@link IPWalkerTerrain}). */
@Mixin(value = WalkerCoreBlockEntity.class, remap = false)
public class MixinWalkerCoreBlockEntity_Portals {
    @WrapMethod(method = "tickOnSubLevel", require = 0)
    private void ip_tickAtPortals(final ServerSubLevel hull, final Operation<Void> original) {
        IPWalkerTerrain.begin((WalkerCoreBlockEntity) (Object) this, hull);
        try {
            original.call(hull);
        } finally {
            IPWalkerTerrain.end();
        }
    }
}
