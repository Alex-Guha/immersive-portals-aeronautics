/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Create Aeronautics (Simulated) handles on ships across portals, server side (docs/lessons/handles.md).
 * <p>
 * Simulated resolves a handle in the player's level and pulls the ship towards the player's eye plus look, in the
 * player's world. A {@link Hold} names the ship, the handle's plot position and the map from the player's world into
 * the ship's: a handle on a far half or seen through a portal (the client names the portals, {@link RemoteCallables#hold}),
 * a player who went through a portal while holding ({@link #onPlayerTeleported}), a held ship moved to another dimension
 * ({@link #onShipMoved}). The client announces every hold of a handle on a ship, so a hold in the player's own world
 * follows its ship too. Handle packets naming the hold's position go to the ship's level.
 */
public final class IPHandlePortals {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ACTIVE = ModList.get().isLoaded("simulated") && ModList.get().isLoaded("sable");
    private static final String CLIENT = "qouteall.imm_ptl.core.compat.sable_integration.IPHandlePortalsClient.RemoteCallables.";

    /**
     * @param pos      the handle's plot position in the ship's level
     * @param stalePos its position before the ship last moved to another dimension (the client names it until it knows),
     *                 or null
     * @param frame    maps the player's world ({@code playerDim}) into the ship's world
     */
    public record Hold(UUID ship, BlockPos pos, @Nullable BlockPos stalePos, ResourceKey<Level> playerDim, IPPortalIsometry frame) {
        boolean names(final BlockPos packetPos) {
            return packetPos.equals(this.pos) || packetPos.equals(this.stalePos);
        }
    }

    /** By player. Server thread only. */
    private static final Map<UUID, Hold> HOLDS = new HashMap<>();

    private IPHandlePortals() {
    }

    public static void reset() {
        HOLDS.clear();
    }

    @Nullable
    private static Hold holdOf(final UUID player, final UUID ship) {
        final Hold hold = HOLDS.get(player);
        return hold != null && hold.ship().equals(ship) ? hold : null;
    }

    // ---- packets

    /**
     * @param own the player's level, where Simulated looks for the handle
     * @return the level of the handle a handle packet names: the held ship's when the packet names the hold's handle
     */
    public static Level packetLevel(final ServerPlayer player, final BlockPos pos, final Level own) {
        final Hold hold = HOLDS.get(player.getUUID());
        if (hold == null || !hold.names(pos)) {
            return own;
        }
        final ServerLevel level = IPPhysicsStaffPortals.levelOf(player.server, hold.ship());
        return level != null ? level : own;
    }

    /**
     * @return the handle's position now, for a packet naming the hold's handle at its position before the ship moved
     */
    public static BlockPos packetPos(final ServerPlayer player, final BlockPos pos) {
        final Hold hold = HOLDS.get(player.getUUID());
        return hold != null && hold.names(pos) ? hold.pos() : pos;
    }

    /**
     * The player let go of the handle at {@code pos}.
     */
    public static void onReleased(final ServerPlayer player, final BlockPos pos) {
        final Hold hold = HOLDS.get(player.getUUID());
        if (hold != null && hold.names(pos)) {
            HOLDS.remove(player.getUUID());
        }
    }

    // ---- the handle block entity

    /**
     * @param ship the ship the handle is on, or null for a handle in terrain
     * @param own  the player as Simulated looks it up, in the handle's level
     * @return the player holding a handle of {@code ship}: while the hold has a frame across worlds, looked up
     * server-wide and only while in the world the frame starts in
     */
    @Nullable
    public static Player holdingPlayer(final ServerLevel level, @Nullable final SubLevel ship, final UUID player, @Nullable final Player own) {
        final Hold hold = ship == null ? null : holdOf(player, ship.getUniqueId());
        if (hold == null || hold.playerDim().equals(level.dimension())) {
            return own;
        }
        final ServerPlayer found = level.getServer().getPlayerList().getPlayer(player);
        return found != null && found.level().dimension().equals(hold.playerDim()) ? found : null;
    }

    /**
     * Maps the goal Simulated pulls the handle towards (the player's eye plus look) from the player's world into the
     * ship's, in place.
     */
    public static void mapGoal(final UUID player, final SubLevel ship, final Vector3d goal) {
        final Hold hold = holdOf(player, ship.getUniqueId());
        if (hold != null) {
            final Vec3 mapped = hold.frame().mapPoint(new Vec3(goal.x, goal.y, goal.z));
            goal.set(mapped.x, mapped.y, mapped.z);
        }
    }

    /**
     * @param own the ship Sable has the player standing on (none for the far half of a ship of another level)
     * @return the ship the player stands on: Simulated never pulls the ship its player stands on
     */
    @Nullable
    public static SubLevel standingShip(final Player player, @Nullable final SubLevel own) {
        if (own != null) {
            return own;
        }
        return IPStraddleCut.farHalfShipUnder(player, 0.2);
    }

    // ---- frame changes

    /**
     * {@code player} went through {@code portal} from {@code from}: a hold now starts in the destination.
     */
    public static void onPlayerTeleported(final ServerPlayer player, final ResourceKey<Level> from, final Portal portal) {
        if (!ACTIVE) {
            return;
        }
        final Hold hold = HOLDS.get(player.getUUID());
        if (hold == null) {
            return;
        }
        if (!IPPhysicsStaffPortals.isRigid(portal) || !hold.playerDim().equals(from)) {
            HOLDS.remove(player.getUUID());
            LOGGER.info("[IPHandle] {} went through portal {} while holding a handle on ship {}: let go ({})",
                player.getGameProfile().getName(), portal.getUUID(), hold.ship(),
                IPPhysicsStaffPortals.isRigid(portal) ? "not in the hold's world" : "scaled or mirror portal");
            return;
        }
        final IPPortalIsometry through = IPPortalIsometry.of(portal);
        HOLDS.put(player.getUUID(), new Hold(hold.ship(), hold.pos(), hold.stalePos(), portal.getDestDim(), through.inverse().then(hold.frame())));
        LOGGER.info("[IPHandle] {} went through portal {} ({} -> {}) while holding a handle on ship {}", player.getGameProfile().getName(),
            portal.getUUID(), from.location(), portal.getDestDim().location(), hold.ship());
    }

    /**
     * {@code shipId} went from {@code src} through {@code portal} and is now {@code moved}: holds of its handles follow
     * it, with the handle in the new plot and the frame extended by the portal.
     */
    public static void onShipMoved(final ServerLevel src, final ServerLevel dst, final UUID shipId, final Portal portal,
                                   final IPSableBridge.Moved moved) {
        if (!ACTIVE) {
            return;
        }
        for (final Iterator<Map.Entry<UUID, Hold>> it = HOLDS.entrySet().iterator(); it.hasNext(); ) {
            final Map.Entry<UUID, Hold> entry = it.next();
            final Hold hold = entry.getValue();
            if (!hold.ship().equals(shipId)) {
                continue;
            }
            final ServerPlayer player = src.getServer().getPlayerList().getPlayer(entry.getKey());
            if (player == null || !IPPhysicsStaffPortals.isRigid(portal)) {
                // the client lets go once the ship is gone from where it held it
                it.remove();
                continue;
            }
            final IPPortalIsometry through = IPPortalIsometry.of(portal);
            entry.setValue(new Hold(shipId, hold.pos().offset(moved.shift()), hold.pos(), hold.playerDim(), hold.frame().then(through)));
            final Quaterniondc q = through.rotation();
            McRemoteProcedureCall.tellClientToInvoke(player, CLIENT + "onShipMoved", shipId, dst.dimension(), moved.shift(),
                through.origin(), through.dest(), new DQuaternion(q.x(), q.y(), q.z(), q.w()));
            LOGGER.info("[IPHandle] {}'s hold of a handle on ship {} follows it to {}", player.getGameProfile().getName(), shipId,
                dst.dimension().location());
        }
    }

    public static final class RemoteCallables {
        private RemoteCallables() {
        }

        /**
         * The client took hold of the handle at {@code pos} on {@code ship} (of {@code shipDim}), seen through the
         * portals in {@code chain} (none: in the player's own world). Sent before its first handle packet.
         */
        public static void hold(final ServerPlayer player, final UUID ship, final ResourceKey<Level> shipDim, final BlockPos pos,
                                final CompoundTag chain) {
            if (!ACTIVE) {
                return;
            }
            HOLDS.remove(player.getUUID());
            final ListTag links = chain.getList(IPPhysicsStaffPortals.LINKS, Tag.TAG_COMPOUND);
            final ServerLevel shipLevel = player.server.getLevel(shipDim);
            if (shipLevel == null || !IPPhysicsStaffPortals.hasShip(shipLevel, ship) || links.size() > IPPhysicsStaffPortals.MAX_LINKS) {
                return;
            }
            final IPPortalIsometry frame;
            if (links.isEmpty()) {
                frame = shipLevel == player.level() ? IPPortalIsometry.IDENTITY : null;
            } else {
                frame = IPPhysicsStaffPortals.resolve(player, shipLevel, links);
            }
            if (frame == null) {
                LOGGER.info("[IPHandle] {} cannot hold a handle on ship {} of {} through the portals named: they do not lead there",
                    player.getGameProfile().getName(), ship, shipDim.location());
                return;
            }
            HOLDS.put(player.getUUID(), new Hold(ship, pos, null, player.level().dimension(), frame));
            if (!links.isEmpty()) {
                LOGGER.info("[IPHandle] {} holds a handle on ship {} of {} from {} through {} portal(s)", player.getGameProfile().getName(),
                    ship, shipDim.location(), player.level().dimension().location(), links.size());
            }
        }
    }
}
