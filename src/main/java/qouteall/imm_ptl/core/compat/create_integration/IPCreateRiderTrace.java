/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.IPSeatedView;

/**
 * Client diagnostics (1.24.13, user: "a brief moment when the player passes through when the screen flashes with the
 * other side of the portal"): one line per frame while a seated player's head is near its carriage's portal plane,
 * and for a second after the player changed dimension or vehicle near one. Says what each frame showed: the player's
 * level, vehicle, head (and where it was a tick earlier), its depth past the copy's plane, and whether IP's cross-portal
 * view drew it through a portal and from where. Off unless {@code -Dip_aeronautics.debug.riderTrace=true} (1.24.16).
 */
public final class IPCreateRiderTrace {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ENABLED = Boolean.getBoolean("ip_aeronautics.debug.riderTrace");
    /** Frames traced after a dimension or vehicle change. */
    private static final int AFTER_CHANGE = 60;
    /** Frames traced while the head is this close to the plane. */
    private static final double NEAR = 2.0;
    private static final int MAX_LINES = 4000;

    private static ResourceKey<Level> lastDim;
    private static int lastVehicle = -1;
    private static long lastRodeTrain = Long.MIN_VALUE;
    private static int framesLeft;
    private static int lines;
    private static long frame;

    private IPCreateRiderTrace() {
    }

    /** After each frame was drawn; {@code crossPortal}: whether IP's cross-portal view drew it. */
    public static void afterFrame(final boolean crossPortal) {
        if (!ENABLED) {
            return;
        }
        frame++;
        final Minecraft client = Minecraft.getInstance();
        final LocalPlayer player = client.player;
        if (player == null || client.level == null || lines >= MAX_LINES) {
            return;
        }
        final long time = client.level.getGameTime();
        final Entity vehicle = player.getVehicle();
        final boolean onTrain = vehicle instanceof CarriageContraptionEntity;
        if (onTrain) {
            lastRodeTrain = time;
        }
        final ResourceKey<Level> dim = player.level().dimension();
        final int vehicleId = vehicle == null ? -1 : vehicle.getId();
        final boolean recent = time - lastRodeTrain >= 0 && time - lastRodeTrain < 40;
        if ((dim != lastDim || vehicleId != lastVehicle) && recent) {
            framesLeft = AFTER_CHANGE;
        }
        lastDim = dim;
        lastVehicle = vehicleId;
        final float partialTick = client.getTimer().getGameTimeDeltaPartialTick(false);
        final Vec3 eye = player.getEyePosition(partialTick);
        final IPCreateCarriageCrossing.Crossing crossing = onTrain ? IPCreateCarriageCrossingClient.crossingOf(vehicle) : null;
        final double depth = crossing == null ? Double.NaN : -crossing.worldDepth(eye);
        if (crossing != null && Math.abs(depth) < NEAR) {
            framesLeft = Math.max(framesLeft, 1);
        }
        if (framesLeft <= 0) {
            return;
        }
        framesLeft--;
        lines++;
        final IPSeatedView.View view = IPSeatedView.of(partialTick);
        final Vec3 camera = client.gameRenderer.getMainCamera().getPosition();
        LOGGER.info("[IPTrainRiderTrace] frame {} tick {} pt {} in {} level {} vehicle {}{} eye {} (tick before {}) depth {}"
                + " portal {}{} seatView {} crossPortal {} camera {}",
            frame, time, String.format("%.2f", partialTick), dim.location(), client.level.dimension().location(),
            vehicleId, vehicle == null ? "" : " in " + vehicle.level().dimension().location(), fmt(eye),
            fmt(new Vec3(player.xo, player.yo + player.getEyeHeight(), player.zo)),
            Double.isNaN(depth) ? "-" : String.format("%.3f", depth),
            crossing == null ? "-" : crossing.portal().getId(), crossing != null && crossing.farHalf() ? " (far half)" : "",
            view == null ? "-" : view.portal().getId() + " from " + fmt(view.origin()), crossPortal, fmt(camera));
    }

    private static String fmt(final Vec3 v) {
        return String.format("(%.3f %.3f %.3f)", v.x, v.y, v.z);
    }
}
