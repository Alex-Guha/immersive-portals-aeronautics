/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelTrackingSystem;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/**
 * Invokers for Sable's private {@code SubLevelTrackingSystem.sendFullSync} and
 * {@code getSubLevelLong}. Used by {@code IPShipThroughPortalSync} to send a ship's
 * StartTracking/chunk/Finalize bundle to a player viewing it through a portal.
 */
@Mixin(
    value = SubLevelTrackingSystem.class,
    remap = false
)
public interface IPSubLevelTrackingSystemAccessor {
    @Invoker("sendFullSync")
    void ip_sendFullSync(ServerPlayer player, ServerSubLevel subLevel, @Nullable CustomPacketPayload extraPacket);

    @Invoker("getSubLevelLong")
    static long ip_getSubLevelLong(ServerSubLevel subLevel, SubLevelContainer container) {
        throw new AssertionError();
    }
}
