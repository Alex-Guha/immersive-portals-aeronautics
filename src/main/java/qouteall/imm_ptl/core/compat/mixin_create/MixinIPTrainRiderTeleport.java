/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.teleportation.ServerTeleportationManager;

/**
 * Server-side: IP's {@code ServerTeleportationManager.validatePlayerTeleportationAndGetReason}
 * rejects the teleport (returns a failure reason) for players riding a Create
 * {@code CarriageContraptionEntity}; Create's portal-track logic moves the train and its
 * riders through the portal instead.
 */
@Mixin(
    value = ServerTeleportationManager.class,
    remap = false
)
public class MixinIPTrainRiderTeleport {
    @Inject(
        method = "validatePlayerTeleportationAndGetReason",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        expect = 0
    )
    private void ip_deferTrainRidersToCreate(
        ServerPlayer player, ResourceKey<Level> dimensionBefore, Vec3 posBefore, Portal portal,
        CallbackInfoReturnable<String> cir
    ) {
        if (player.getVehicle() instanceof CarriageContraptionEntity) {
            cir.setReturnValue("IP: rider on a Create train; Create handles the portal crossing");
        }
    }
}
