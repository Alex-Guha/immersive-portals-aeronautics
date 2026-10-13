/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.network.packets.tcp.ClientboundStartTrackingSubLevelPacket;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import foundry.veil.api.network.handler.PacketContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.util.UUID;

/**
 * Before the client handles a {@code ClientboundStartTrackingSubLevelPacket}: if the same ship
 * (same UUID) is already present at that plot, the packet is skipped entirely so the stock
 * handler does not tear the ship's render data down and recreate it (a visible blink); the
 * chunk and finalize packets that follow refresh the existing ship in place. If a different
 * ship occupies the plot it is removed (reason {@code REMOVED}) so the stock handler can
 * allocate the new one.
 */
@Mixin(
    value = ClientboundStartTrackingSubLevelPacket.class,
    remap = false
)
public abstract class MixinSableStartTracking_DupGuard {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Shadow
    public abstract long plotCoordinate();

    @Shadow
    public abstract UUID subLevelID();

    @Inject(
        method = "handle",
        at = @At("HEAD"),
        cancellable = true,
        require = 0
    )
    private void ip_replaceStaleSubLevel(PacketContext context, CallbackInfo ci) {
        try {
            Level level = context.level();
            if (level == null) {
                return;
            }

            SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (!(container instanceof ClientSubLevelContainer)) {
                return;
            }

            long plot = this.plotCoordinate();
            int plotX = ChunkPos.getX(plot);
            int plotZ = ChunkPos.getZ(plot);
            SubLevel existing = container.getSubLevel(plotX, plotZ);
            if (existing == null) {
                return;
            }
            if (existing.getUniqueId().equals(this.subLevelID())) {
                ip_LOGGER.debug(
                    "[IPSableDupGuard] ship {} already tracked at plot {}, {}; skipping duplicate start-tracking",
                    existing.getUniqueId(), plotX, plotZ
                );
                ci.cancel();
                return;
            }
            ip_LOGGER.debug(
                "[IPSableDupGuard] replacing stale sub-level at plot {}, {} (uuid {})",
                plotX, plotZ, existing.getUniqueId()
            );
            container.removeSubLevel(plotX, plotZ, SubLevelRemovalReason.REMOVED);
        } catch (Throwable t) {
            ip_LOGGER.warn("[IPSableDupGuard] guard failed, letting stock handler run: {}", String.valueOf(t));
        }
    }
}
