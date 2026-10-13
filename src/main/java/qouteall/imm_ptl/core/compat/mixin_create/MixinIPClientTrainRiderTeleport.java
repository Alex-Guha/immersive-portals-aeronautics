/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.create_integration.IPCreateClientCarriageDriver;
import qouteall.imm_ptl.core.teleportation.ClientTeleportationManager;
import qouteall.imm_ptl.core.teleportation.TeleportationUtil;

/**
 * Client-side counterpart of {@link MixinIPTrainRiderTeleport}: IP's
 * {@code ClientTeleportationManager.tryTeleport} returns null (no teleport) while the local
 * player rides a Create {@code CarriageContraptionEntity}, and for
 * 20 ticks after dismounting, so Create's own
 * portal-track crossing is not fought by IP's client teleportation.
 */
@Mixin(
    value = ClientTeleportationManager.class,
    remap = false
)
public class MixinIPClientTrainRiderTeleport {
    @Unique
    private static final Logger ip_LOGGER = LogUtils.getLogger();

    @Unique
    private static long ip_lastRodeTrainTick = Long.MIN_VALUE;
    @Unique
    private static final long ip_TRAIN_CROSS_WINDOW = 20L;
    /** Window start already logged; the window is logged once, not on every teleport check. */
    @Unique
    private static long ip_loggedWindowTick = Long.MIN_VALUE;

    /**
     * IP's client-side dimension change carries the player's vehicle into the new world. A
     * Create carriage must never travel that way: its dimension copies share one UUID, so the
     * carried copy becomes a ghost in the destination world. Dismount first; the server has
     * already dismounted the player (Create's portal handoff) or will re-seat them.
     */
    @Inject(
        method = "forceTeleportPlayer(Lnet/minecraft/resources/ResourceKey;Lnet/minecraft/world/phys/Vec3;)V",
        at = @At("HEAD"),
        require = 0,
        expect = 0
    )
    private static void ip_dismountCarriageBeforeForcedTeleport(
        ResourceKey<Level> toDimension, Vec3 destination, CallbackInfo ci
    ) {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.getVehicle() instanceof CarriageContraptionEntity carriage
            && player.level().dimension() != toDimension
        ) {
            ip_LOGGER.debug(
                "[IPTrainRider] client: dismounting carriage {} before forced teleport to {}",
                carriage.getId(), toDimension.location()
            );
            player.stopRiding();
            ip_lastRodeTrainTick = player.level().getGameTime();
        }
    }

    @Inject(
        method = "tryTeleport",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        expect = 0
    )
    private static void ip_skipTrainRiders(float partialTicks, CallbackInfoReturnable<TeleportationUtil.Teleportation> cir) {
        IPCreateClientCarriageDriver.register();
        LocalPlayer player = Minecraft.getInstance().player;
        if (player != null && player.level() != null) {
            long gameTime = player.level().getGameTime();
            if (player.getVehicle() instanceof CarriageContraptionEntity) {
                ip_lastRodeTrainTick = gameTime;
                cir.setReturnValue(null);
            } else {
                long ticksSinceRiding = gameTime - ip_lastRodeTrainTick;
                if (ticksSinceRiding >= 0L && ticksSinceRiding <= ip_TRAIN_CROSS_WINDOW) {
                    if (ip_loggedWindowTick != ip_lastRodeTrainTick) {
                        ip_loggedWindowTick = ip_lastRodeTrainTick;
                        ip_LOGGER.debug(
                            "[IPTrainRider] client: suppressing IP teleport for {} ticks after leaving a train",
                            ip_TRAIN_CROSS_WINDOW
                        );
                    }
                    cir.setReturnValue(null);
                }
            }
        }
    }
}
