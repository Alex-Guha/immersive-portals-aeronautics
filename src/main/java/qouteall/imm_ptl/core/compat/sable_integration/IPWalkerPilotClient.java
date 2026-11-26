/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalSimilarity;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.my_util.DQuaternion;

/**
 * Let It Walk's pilot controls across portals, client side ({@link IPWalkerPilot}).
 * <p>
 * Let It Walk's client ({@code PilotInput}) stops piloting once the player is more than 10 blocks from the controls,
 * measured in the player's level, where controls on a walker in another level (straddling a portal the player went
 * through) are not found at all, and it names the controls by a plot position the walker leaves when the server hands
 * it off. So the pilot keeps the walker's ship, its level and the map from the player's world into it, as the server
 * does: the distance is measured in the ship's world, and when the server moves the ship the controls' position
 * follows, once the ship shows in its new level. Until then the pilot is kept (a hand-off takes a moment to reach the
 * client).
 */
public final class IPWalkerPilotClient {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Ticks a pilot is kept while the client waits for its moved ship to show up. */
    private static final int MOVE_WAIT_TICKS = 100;

    private static final class Pilot {
        final UUID ship;
        BlockPos pos;
        ResourceKey<Level> shipDim;
        IPPortalSimilarity frame;
        @Nullable
        IPPortalSimilarity pendingMove;
        @Nullable
        ResourceKey<Level> movedTo;
        BlockPos shift = BlockPos.ZERO;
        int waited;

        Pilot(final UUID ship, final BlockPos pos, final ResourceKey<Level> shipDim) {
            this.ship = ship;
            this.pos = pos;
            this.shipDim = shipDim;
            this.frame = IPPortalSimilarity.IDENTITY;
        }
    }

    @Nullable
    private static Pilot pilot;

    private IPWalkerPilotClient() {
    }

    /**
     * Let It Walk's range check of the pilot: the squared distance from the player at {@code at} to the controls at
     * {@code controls}, in the controls' world. {@code setControls} moves Let It Walk's controls position.
     *
     * @param measure Sable's distance in a level, as Let It Walk calls it
     */
    public static double distanceSquared(final Level level, final Position at, final BlockPos controls, final Consumer<BlockPos> setControls,
                                         final Measure measure) {
        final Pilot current = pilotFor(level, controls);
        if (current == null) {
            return measure.distanceSquared(level, at, Vec3.atCenterOf(controls));
        }
        if (current.pendingMove != null) {
            if (shipIn(current.movedTo, current.ship) == null) {
                // the ship is on its way to its new plot: keep piloting until it shows, or give up
                return ++current.waited <= MOVE_WAIT_TICKS ? 0.0 : Double.MAX_VALUE;
            }
            current.frame = current.frame.then(current.pendingMove);
            current.shipDim = current.movedTo;
            current.pos = current.pos.offset(current.shift);
            current.pendingMove = null;
            current.movedTo = null;
            current.shift = BlockPos.ZERO;
            setControls.accept(current.pos);
            LOGGER.info("[IPWalker] piloting goes on with the walker on ship {} in {}", current.ship, current.shipDim.location());
        }
        final ClientLevel shipLevel = ClientWorldLoader.getOptionalWorld(current.shipDim);
        if (shipLevel == null) {
            return measure.distanceSquared(level, at, Vec3.atCenterOf(controls));
        }
        final Position seen = current.frame == IPPortalSimilarity.IDENTITY ? at : current.frame.mapPoint(new Vec3(at.x(), at.y(), at.z()));
        return measure.distanceSquared(shipLevel, seen, Vec3.atCenterOf(current.pos));
    }

    /** @return the level of the pilot's controls at {@code controls} (Let It Walk reads the controls' state there) */
    public static Level controlsLevel(final Level level, final BlockPos controls) {
        final Pilot current = pilot;
        if (current == null || !current.pos.equals(controls)) {
            return level;
        }
        final ClientLevel shipLevel = ClientWorldLoader.getOptionalWorld(current.shipDim);
        return shipLevel != null ? shipLevel : level;
    }

    /** The pilot of the controls at {@code controls}, started when they are on a ship of the player's level. */
    @Nullable
    private static Pilot pilotFor(final Level level, final BlockPos controls) {
        final Pilot current = pilot;
        if (current != null && current.pos.equals(controls)) {
            return current;
        }
        final SubLevel ship = Sable.HELPER.getContaining(level, controls);
        pilot = ship == null ? null : new Pilot(ship.getUniqueId(), controls.immutable(), level.dimension());
        return pilot;
    }

    @Nullable
    private static SubLevel shipIn(@Nullable final ResourceKey<Level> dim, final UUID ship) {
        final ClientLevel level = dim == null ? null : ClientWorldLoader.getOptionalWorld(dim);
        final SubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
        final SubLevel found = container == null ? null : container.getSubLevel(ship);
        return found == null || found.isRemoved() ? null : found;
    }

    /** The local player went through {@code portal}: the pilot's frame now starts in the destination. */
    public static void onPlayerTeleported(final Portal portal) {
        final Pilot current = pilot;
        if (current == null) {
            return;
        }
        if (!IPPhysicsStaffPortals.isMappable(portal)) {
            pilot = null;
            return;
        }
        current.frame = IPPhysicsStaffPortals.mapOf(portal).inverse().then(current.frame);
    }

    /** Sable's distance through sub-levels, in a given level. */
    @FunctionalInterface
    public interface Measure {
        double distanceSquared(Level level, Position a, Position b);
    }

    private static IPPortalSimilarity through(final Vec3 origin, final Vec3 dest, final DQuaternion rotation, final double scale) {
        return new IPPortalSimilarity(new IPPortalIsometry(origin, dest,
            new Quaterniond(rotation.x, rotation.y, rotation.z, rotation.w).normalize()), scale);
    }

    public static final class RemoteCallables {
        private RemoteCallables() {
        }

        /** The server moved the piloted walker's ship {@code ship} through a portal to {@code dim}, its plot by {@code shift}. */
        public static void onShipMoved(final UUID ship, final ResourceKey<Level> dim, final BlockPos shift, final Vec3 origin,
                                       final Vec3 dest, final DQuaternion rotation, final double scale) {
            final Pilot current = pilot;
            if (current == null || !current.ship.equals(ship)) {
                return;
            }
            final IPPortalSimilarity map = through(origin, dest, rotation, scale);
            current.pendingMove = current.pendingMove == null ? map : current.pendingMove.then(map);
            current.shift = current.movedTo != null ? current.shift.offset(shift) : shift;
            current.movedTo = dim;
            current.waited = 0;
        }

        /** The server carried the player through a portal with a ship, without IP's teleport. */
        public static void onCarried(final ResourceKey<Level> dim, final Vec3 origin, final Vec3 dest, final DQuaternion rotation,
                                     final double scale) {
            final Pilot current = pilot;
            if (current != null) {
                current.frame = through(origin, dest, rotation, scale).inverse().then(current.frame);
            }
        }
    }
}
