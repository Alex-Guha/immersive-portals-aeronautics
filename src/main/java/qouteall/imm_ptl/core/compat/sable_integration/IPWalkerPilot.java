/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Position;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalSimilarity;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import qouteall.q_misc_util.my_util.DQuaternion;

/**
 * Let It Walk's pilot controls across portals, server side ({@link IPWalkerPilotClient} on the client).
 * <p>
 * A pilot stands near a walker's pilot controls (12 blocks) and the client sends its keys naming the controls by plot
 * position; Let It Walk resolves them, and measures the distance, in the player's level. A player standing on a walker
 * that straddles a portal goes through before the walker is handed off, or after it: until both are on one side they
 * are in two levels, and a walker handed off has its controls in another plot. A {@link Pilot} names the ship, the
 * controls' plot position (and the one before the ship last moved, which the client names until it knows) and the map
 * from the player's world into the ship's, changed by the player's crossings ({@code F <- T^-1 o F}) and the ship's
 * ({@code F <- F o Q}), as a handle's hold is ({@link IPHandlePortals}). Packets naming the pilot's controls resolve
 * in the ship's level, with the player's position and heading mapped into it.
 */
public final class IPWalkerPilot {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ACTIVE = ModList.get().isLoaded("let_it_walk") && ModList.get().isLoaded("sable");
    private static final String CLIENT = "qouteall.imm_ptl.core.compat.sable_integration.IPWalkerPilotClient.RemoteCallables.";
    /** The client sends at least every 5 ticks while piloting: a pilot silent for longer has stopped. */
    private static final long SILENT_TICKS = 100;

    /**
     * @param pos      the controls' plot position in the ship's level
     * @param stalePos their position before the ship last moved to another plot, or null
     * @param frame    maps the player's world ({@code playerDim}) into the ship's world
     */
    private record Pilot(UUID ship, BlockPos pos, @Nullable BlockPos stalePos, ResourceKey<Level> playerDim,
                         IPPortalSimilarity frame, long lastPacket) {
        boolean names(final BlockPos packetPos) {
            return packetPos.equals(this.pos) || packetPos.equals(this.stalePos);
        }
    }

    /** By player. Server thread only. */
    private static final Map<UUID, Pilot> PILOTS = new HashMap<>();

    private IPWalkerPilot() {
    }

    public static boolean isActive() {
        return ACTIVE;
    }

    public static void reset() {
        PILOTS.clear();
    }

    // ---- the control packet (Let It Walk's WalkerControlPayload.block)

    /**
     * The level Let It Walk resolves a control packet naming {@code controls} in ({@code own}: the player's). Starts a
     * pilot on controls on a ship of the player's level; a packet naming a pilot's controls resolves in its ship's.
     */
    public static Level packetLevel(final ServerPlayer player, final BlockPos controls, final Level own) {
        if (!ACTIVE) {
            return own;
        }
        final long now = player.server.getTickCount();
        final Pilot pilot = PILOTS.get(player.getUUID());
        if (pilot != null && pilot.names(controls) && now - pilot.lastPacket() <= SILENT_TICKS) {
            final ServerLevel shipLevel = IPPhysicsStaffPortals.levelOf(player.server, pilot.ship());
            if (shipLevel != null) {
                PILOTS.put(player.getUUID(), new Pilot(pilot.ship(), pilot.pos(), pilot.stalePos(), pilot.playerDim(), pilot.frame(), now));
                return shipLevel;
            }
        }
        final SubLevel ship = Sable.HELPER.getContaining(own, controls);
        if (ship != null) {
            PILOTS.put(player.getUUID(), new Pilot(ship.getUniqueId(), controls.immutable(), null, own.dimension(), IPPortalSimilarity.IDENTITY, now));
        } else if (pilot != null && now - pilot.lastPacket() > SILENT_TICKS) {
            PILOTS.remove(player.getUUID());
        }
        return own;
    }

    /** @return the controls' position now, for a packet naming them at their position before the ship moved */
    public static BlockPos packetPos(final ServerPlayer player, final BlockPos controls) {
        final Pilot pilot = ACTIVE ? PILOTS.get(player.getUUID()) : null;
        return pilot != null && pilot.names(controls) ? pilot.pos() : controls;
    }

    /** @return the player's position {@code at} in the ship's world, while a pilot spans worlds */
    public static Position playerPosition(final ServerPlayer player, final BlockPos controls, final Position at) {
        final Pilot pilot = ACTIVE ? PILOTS.get(player.getUUID()) : null;
        if (pilot == null || !pilot.names(controls) || pilot.frame() == IPPortalSimilarity.IDENTITY) {
            return at;
        }
        return pilot.frame().mapPoint(new Vec3(at.x(), at.y(), at.z()));
    }

    /** @return the player's heading {@code yaw} in the ship's world (Let It Walk steers by it) */
    public static float pilotYaw(final ServerPlayer player, final float yaw) {
        final Pilot pilot = ACTIVE ? PILOTS.get(player.getUUID()) : null;
        if (pilot == null || pilot.frame() == IPPortalSimilarity.IDENTITY) {
            return yaw;
        }
        final Vec3 heading = pilot.frame().mapDirection(Vec3.directionFromRotation(0.0F, yaw));
        return (float) (Mth.atan2(-heading.x, heading.z) * Mth.RAD_TO_DEG);
    }

    // ---- frame changes

    /** {@code player} went through {@code portal} from {@code from}: a pilot now starts in the destination. */
    public static void onPlayerTeleported(final ServerPlayer player, final ResourceKey<Level> from, final Portal portal) {
        if (!ACTIVE) {
            return;
        }
        final Pilot pilot = PILOTS.get(player.getUUID());
        if (pilot == null) {
            return;
        }
        if (!IPPhysicsStaffPortals.isMappable(portal) || !pilot.playerDim().equals(from)) {
            PILOTS.remove(player.getUUID());
            return;
        }
        final IPPortalSimilarity through = IPPhysicsStaffPortals.mapOf(portal);
        PILOTS.put(player.getUUID(), new Pilot(pilot.ship(), pilot.pos(), pilot.stalePos(), portal.getDestDim(),
            through.inverse().then(pilot.frame()), pilot.lastPacket()));
        LOGGER.info("[IPWalker] {} went through portal {} while piloting the walker on ship {}", player.getGameProfile().getName(),
            portal.getUUID(), pilot.ship());
    }

    /** {@code player} was carried through {@code portal} with a ship: its client is told, as IP's teleport tells it. */
    public static void tellCarried(final ServerPlayer player, final Portal portal) {
        if (!ACTIVE || !PILOTS.containsKey(player.getUUID()) || !IPPhysicsStaffPortals.isMappable(portal)) {
            return;
        }
        final IPPortalSimilarity through = IPPhysicsStaffPortals.mapOf(portal);
        final Quaterniondc q = through.rotation();
        McRemoteProcedureCall.tellClientToInvoke(player, CLIENT + "onCarried", portal.getDestDim(), through.origin(), through.dest(),
            new DQuaternion(q.x(), q.y(), q.z(), q.w()), through.scale());
    }

    /**
     * {@code shipId} went from {@code src} through {@code portal} and is now {@code moved}: pilots of its controls
     * follow it, with the controls in the new plot and the frame extended by the portal.
     */
    public static void onShipMoved(final ServerLevel src, final ServerLevel dst, final UUID shipId, final Portal portal,
                                   final IPSableBridge.Moved moved) {
        if (!ACTIVE) {
            return;
        }
        for (final Iterator<Map.Entry<UUID, Pilot>> it = PILOTS.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Pilot> entry = it.next();
            final Pilot pilot = entry.getValue();
            if (!pilot.ship().equals(shipId)) {
                continue;
            }
            final ServerPlayer player = src.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !IPPhysicsStaffPortals.isMappable(portal)) {
                it.remove();
                continue;
            }
            final IPPortalSimilarity through = IPPhysicsStaffPortals.mapOf(portal);
            entry.setValue(new Pilot(shipId, pilot.pos().offset(moved.shift()), pilot.pos(), pilot.playerDim(),
                pilot.frame().then(through), pilot.lastPacket()));
            final Quaterniondc q = through.rotation();
            McRemoteProcedureCall.tellClientToInvoke(player, CLIENT + "onShipMoved", shipId, dst.dimension(), moved.shift(),
                through.origin(), through.dest(), new DQuaternion(q.x(), q.y(), q.z(), q.w()), through.scale());
            LOGGER.info("[IPWalker] {}'s piloting of the walker on ship {} follows it to {}", player.getGameProfile().getName(), shipId,
                dst.dimension().location());
        }
    }
}
