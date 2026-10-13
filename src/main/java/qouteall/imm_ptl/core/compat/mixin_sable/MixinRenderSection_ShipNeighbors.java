/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;

/**
 * Lets ship (plot-space) render sections pass vanilla's neighbour check.
 * <p>
 * A section's compile task first checks {@code hasAllNeighbors()}: with the camera more than 24 blocks away,
 * the four horizontally adjacent chunks must exist in the dispatcher's level, otherwise the task cancels
 * itself and {@code cancel()} marks the section dirty again. Empty chunks of a ship's plot do not exist on
 * the client (the lookup returns null), so edge sections of a ship seen from further away never compiled:
 * background compiles re-queued themselves every frame, synchronous ones failed silently inside Sable's
 * compile loop (parts of ships invisible, through portals in particular, where the camera is rarely near the
 * ship). Neighbouring plot chunks do not matter for a ship section; missing ones snapshot as empty.
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection", remap = false)
public class MixinRenderSection_ShipNeighbors {
    // named explicitly: merged into the vanilla class, LogUtils.getLogger() would name it after
    // net.minecraft..., whose debug output is not written to debug.log
    @Unique
    private static final Logger ip_LOGGER =
        LoggerFactory.getLogger("qouteall.imm_ptl.core.compat.mixin_sable.MixinRenderSection_ShipNeighbors");
    @Unique
    private static volatile boolean ip_logged;

    @Shadow
    @Final
    BlockPos.MutableBlockPos origin;

    @Inject(method = "hasAllNeighbors", at = @At("RETURN"), cancellable = true, require = 0)
    private void ip_shipSectionsHaveNeighbors(CallbackInfoReturnable<Boolean> cir) {
        if (cir.getReturnValueZ() || this.origin == null
            || !IPSableCompatHelper.isPlotSpaceChunk(new ChunkPos(this.origin.getX() >> 4, this.origin.getZ() >> 4))) {
            return;
        }
        cir.setReturnValue(true);
        if (!ip_logged) {
            ip_logged = true;
            ip_LOGGER.debug("[IPShipNeighbors] passing vanilla's neighbour check for ship sections (first: {})", this.origin.toShortString());
        }
    }
}
