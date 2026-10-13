/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.network.packets.tcp.ClientboundFinalizeSubLevelPacket;
import foundry.veil.api.network.handler.PacketContext;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipRenderWatchdog;
import org.spongepowered.asm.mixin.injection.Redirect;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;

/**
 * Around the client handling of a {@code ClientboundFinalizeSubLevelPacket}: the stock handler
 * recreates the ship's render data (a visible blink). For a ship that was already finalized,
 * i.e. a full sync re-sent for a ship the client already shows, the existing render data is
 * marked dirty instead so the old meshes stay until the refreshed chunks are compiled. Afterwards
 * notifies {@link IPShipRenderWatchdog#onFinalized} so a rebuild can be scheduled for that level's ship.
 */
@Mixin(
    value = ClientboundFinalizeSubLevelPacket.class,
    remap = false
)
public abstract class MixinSableFinalize_RenderWatchdog {
    @Shadow
    public abstract long plotCoordinate();

    @Redirect(
        method = "handle",
        at = @At(
            value = "INVOKE",
            target = "Ldev/ryanhcode/sable/sublevel/ClientSubLevel;updateRenderData()V"
        ),
        require = 0
    )
    private void ip_rebuildInsteadOfRecreate(ClientSubLevel subLevel) {
        SubLevelRenderData renderData = subLevel.getRenderData();
        if (renderData != null) {
            renderData.rebuild();
        } else {
            subLevel.updateRenderData();
        }
    }

    @Inject(
        method = "handle",
        at = @At("TAIL"),
        require = 0
    )
    private void ip_scheduleRenderRefresh(PacketContext context, CallbackInfo ci) {
        try {
            IPShipRenderWatchdog.onFinalized(context.level(), this.plotCoordinate());
        } catch (Throwable ignored) {
        }
    }
}
