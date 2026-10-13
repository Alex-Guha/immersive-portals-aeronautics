/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipPortalAssemblyGuard;

/**
 * Breaks lit portals whose blocks are about to be assembled into a sub-level
 * ({@link IPShipPortalAssemblyGuard}). {@code moveBlocks} is the one method every assembly path
 * (Sable commands, Create Aeronautics assemblers) uses to move blocks from the world into a plot.
 */
@Mixin(
    value = SubLevelAssemblyHelper.class,
    remap = false
)
public class MixinSableAssembly_PortalBreak {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Inject(
        method = "moveBlocks",
        at = @At("HEAD"),
        require = 0
    )
    private static void ip_breakPortalsBeforeMove(
        ServerLevel level, SubLevelAssemblyHelper.AssemblyTransform transform, Iterable<BlockPos> blocks, CallbackInfo ci
    ) {
        try {
            IPShipPortalAssemblyGuard.breakPortalsAmong(level, blocks);
        } catch (Throwable e) {
            ip_LOGGER.warn("[IPShipPortal] portal check before sub-level assembly failed: {}", e.toString());
        }
    }
}
