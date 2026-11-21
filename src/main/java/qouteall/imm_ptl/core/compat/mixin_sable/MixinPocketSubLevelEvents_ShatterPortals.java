/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalPocketCases;

/**
 * Create: Pocket Sized packing a ship into a case: the ship's portals shatter just before Pocket saves its plot (after
 * Pocket's own refusals), so the case keeps the frame without portal blocks ({@link IPShipPortalPocketCases}).
 */
@Mixin(targets = "com.misterblusky9.pocket.pocket.PocketedSubLevelEvents", remap = false)
public class MixinPocketSubLevelEvents_ShatterPortals {
    @Inject(method = "pocket", at = @At(value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelSerializer;toData(Ldev/ryanhcode/sable/sublevel/ServerSubLevel;Ljava/util/List;)Ldev/ryanhcode/sable/sublevel/storage/serialization/SubLevelData;"),
            require = 0)
    private static void ip_shatterPortalsBeforePacking(final ServerLevel level, final Player player, final ServerSubLevel subLevel,
                                                       final ItemStack packedInto, final CallbackInfo ci) {
        IPShipPortalPocketCases.onPacking(subLevel);
    }
}
