/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import java.util.List;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalPocketCases;

/**
 * Create: Pocket Sized packing a ship into a case: the ship's portals shatter just before Pocket saves its plot (after
 * Pocket's own refusals), so the case keeps the frame without portal blocks ({@link IPShipPortalPocketCases}). Each ship
 * saved is one {@code SubLevelSerializer.toData} call: the ship packed ({@code PocketedSubLevelEvents.pocket}) and, from
 * Pocket 0.32, every ship hot-glued to it ({@code PocketCaseItem.captureWelded}). The call is wrapped instead of reading
 * {@code pocket}'s arguments, which change between Pocket versions.
 */
@Mixin(targets = {"com.misterblusky9.pocket.pocket.PocketedSubLevelEvents", "com.misterblusky9.pocket.item.PocketCaseItem"},
        remap = false)
public class MixinPocketSubLevelEvents_ShatterPortals {
    @WrapOperation(method = {"pocket", "captureWelded"}, at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelSerializer;toData(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ljava/util/List;)Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelData;"),
            require = 0)
    private static SubLevelData ip_shatterPortalsBeforePacking(final ServerSubLevel ship, final List<?> dependencies,
                                                               final Operation<SubLevelData> original) {
        IPShipPortalPocketCases.onPacking(ship);
        return original.call(ship, dependencies);
    }
}
