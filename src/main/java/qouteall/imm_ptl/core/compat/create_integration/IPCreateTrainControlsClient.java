/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.Create;
import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import com.simibubi.create.foundation.utility.ControlsUtil;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Client: a player driving a train keeps the controls when they go through a portal (server side:
 * {@link IPCreateTrainControls}).
 * <p>
 * Create's {@code ControlsHandler} holds the carriage copy whose controls were clicked, sends every input with that
 * entity's id, and stops controlling once the entity is removed: the copy on the side the carriage has left is
 * discarded a few ticks after, while its driver may still be on that side (standing by, or at the carriage's back).
 * The controls switch to the carriage's copy in the player's dimension when there is one, and otherwise keep the copy
 * they have while it lives, even past a portal (the server finds it and measures reach through the portal). When it is
 * removed they switch to any live copy, and resend the held keys at once. While no copy lives but the train still
 * exists, Create's tick is skipped (no input, no stop) for up to {@link #GRACE_TICKS}.
 */
public final class IPCreateTrainControlsClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPTrainControls] ";
    /** Ticks to wait for a live copy of the carriage before letting Create stop. */
    private static final int GRACE_TICKS = 100;

    private static int waitingTicks;
    /** Why our tick hook let Create stop, for the stop's log line; null when Create stops on its own. */
    private static @Nullable String letStopReason;

    private IPCreateTrainControlsClient() {
    }

    /** What {@code ControlsHandler.tick} should do this tick. */
    public enum Action { CONTINUE, SKIP }

    /**
     * {@code ControlsHandler.tick}, head.
     *
     * @param replace receives the copy to control from now on, if it changes
     */
    public static Action beforeTick(final @Nullable AbstractContraptionEntity controlled,
                                    final Consumer<CarriageContraptionEntity> replace) {
        letStopReason = null;
        final LocalPlayer player = Minecraft.getInstance().player;
        if (!(controlled instanceof final CarriageContraptionEntity carriage) || player == null) {
            waitingTicks = 0;
            return Action.CONTINUE;
        }
        final CarriageContraptionEntity copy = IPCreateTrainControls.bestCopy(carriage, player.level());
        if (copy == carriage) {
            waitingTicks = 0;
            return Action.CONTINUE;
        }
        if (copy != null) {
            LOGGER.info(TAG + "client: controls follow carriage {} to its copy {} in {} (from entity {} in {}{}; player in {})",
                IPCreateTrainControls.carriageId(carriage), copy.getId(), copy.level().dimension().location(),
                carriage.getId(), carriage.level().dimension().location(), carriage.isRemoved() ? ", removed" : "",
                player.level().dimension().location());
            waitingTicks = 0;
            replace.accept(copy);
            return Action.CONTINUE;
        }
        if (!trainExists(carriage)) {
            letStopReason = "the train no longer exists";
            waitingTicks = 0;
            return Action.CONTINUE;
        }
        if (waitingTicks == 0) {
            LOGGER.info(TAG + "client: carriage {} has no live copy (controlled entity {} in {} removed; player in {}), waiting",
                IPCreateTrainControls.carriageId(carriage), carriage.getId(), carriage.level().dimension().location(),
                player.level().dimension().location());
        }
        if (++waitingTicks > GRACE_TICKS) {
            letStopReason = "no live copy of the carriage after " + GRACE_TICKS + " ticks";
            waitingTicks = 0;
            return Action.CONTINUE;
        }
        // Create's tick also keeps the movement keys from moving the player; do that while waiting
        ControlsUtil.getControls().forEach(key -> key.setDown(false));
        return Action.SKIP;
    }

    /** {@code ControlsHandler.stopControlling}, head: logs why controlling stops. */
    public static void onStop(final @Nullable AbstractContraptionEntity controlled, final @Nullable BlockPos controlsPos) {
        final LocalPlayer player = Minecraft.getInstance().player;
        if (!(controlled instanceof final CarriageContraptionEntity carriage) || player == null) {
            return;
        }
        final String cause = letStopReason != null ? letStopReason : IPCreateTrainControls.caller();
        letStopReason = null;
        final double distance = controlsPos == null ? -1
            : carriage.toGlobalVector(Vec3.atCenterOf(controlsPos), 1.0f).distanceTo(player.getEyePosition());
        LOGGER.info(TAG + "client: Create stops controlling carriage {} (entity {} in {}{}; player in {}, {} from the "
                + "controls): {}",
            IPCreateTrainControls.carriageId(carriage), carriage.getId(), carriage.level().dimension().location(),
            carriage.isRemoved() ? ", removed" : "", player.level().dimension().location(),
            String.format("%.2f", distance), cause);
    }

    private static boolean trainExists(final CarriageContraptionEntity entity) {
        final Carriage carriage = entity.getCarriage();
        return carriage != null && Create.RAILWAYS.sided(entity.level()).trains.containsKey(carriage.train.id);
    }
}
