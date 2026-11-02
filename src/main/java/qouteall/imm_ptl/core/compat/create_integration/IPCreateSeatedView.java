/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.IPSeatedView;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalUtils;

/**
 * Client: the view of a player seated on a carriage crossing an IP portal while its head, as drawn, is past the copy's
 * portal plane (the trains' {@code IPStraddleSeatView}). The server hands riders over to the other copy by its own
 * carriage ({@link IPCreateRiderHandOver}), which runs a few ticks ahead of the one this client draws: after the
 * hand-over the drawn head is still behind the destination's portal for those ticks. At a carriage's ends, before
 * Create has a copy on the other side (or after it dropped it), the head of a rider near the end goes past the plane
 * with no copy to change to. In both cases the view and the crosshair go through the copy's portal.
 * <p>
 * The server's hand-over moves the player to its own (ahead) seat position, and vanilla places a rider on its seat
 * only at the rider's next tick: for up to a tick the camera stood that far ahead, past the plane, then went back
 * behind it. A player seated on a crossing carriage is placed on the seat at once ({@link #onSeated}, 1.24.13), and
 * again after every position packet (the dismount's tracker update and {@code ServerPlayer.startRiding} each send the
 * server's position, sometimes after the passengers packet, 1.24.14).
 */
public final class IPCreateSeatedView {
    /** How far in front of the plane the cross-portal view starts its ray to the head. */
    private static final double ORIGIN_OFFSET = 0.05;

    private static final Logger LOGGER = LogUtils.getLogger();

    private IPCreateSeatedView() {
    }

    /**
     * The local player has just been seated on a carriage copy: if the carriage crosses a portal, places the player
     * on the seat now, moving with it (the previous position is the seat's, one tick of the carriage's motion back).
     */
    public static void onSeated(final LocalPlayer player, final CarriageContraptionEntity carriage) {
        if (IPCreateCarriageCrossing.STOCK || IPCreateCarriageCrossingClient.crossingOf(carriage) == null) {
            return;
        }
        final Vec3 before = player.position();
        carriage.positionRider(player);
        final Vec3 after = player.position();
        if (after.equals(before)) {
            // no seat for the player on this client yet: vanilla places the rider at its next tick
            return;
        }
        player.xo = after.x - (carriage.getX() - carriage.xo);
        player.yo = after.y - (carriage.getY() - carriage.yo);
        player.zo = after.z - (carriage.getZ() - carriage.zo);
        player.xOld = player.xo;
        player.yOld = player.yo;
        player.zOld = player.zo;
        LOGGER.debug("[IPTrainRider] client: seated on carriage {} in {} at once: {} -> {}",
            carriage.getId(), carriage.level().dimension().location(), before, after);
    }

    @Nullable
    public static IPSeatedView.View of(final float partialTick) {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (IPCreateCarriageCrossing.STOCK || player == null
            || !(player.getVehicle() instanceof final CarriageContraptionEntity carriage)) {
            return null;
        }
        final IPCreateCarriageCrossing.Crossing crossing = IPCreateCarriageCrossingClient.crossingOf(carriage);
        if (crossing == null) {
            return null;
        }
        final Vec3 eye = player.getEyePosition(partialTick);
        final double depth = -crossing.worldDepth(eye);
        if (depth <= 0.0) {
            return null;
        }
        final Vec3 origin = eye.add(crossing.via().toWorldDirection(crossing.keepNormal()).scale(depth + ORIGIN_OFFSET));
        // any portal entity to the other dimension: a nether portal has one per face, a ray only hits one from its
        // front, and the crossing may name the other face (1.24.14: the view never went through)
        final Portal portal = PortalUtils.raytracePortals(player.level(), origin, eye, true,
            candidate -> candidate.getDestDim().equals(crossing.portal().getDestDim())).map(hit -> hit.getFirst()).orElse(null);
        return portal == null ? null : new IPSeatedView.View(portal, origin);
    }
}
