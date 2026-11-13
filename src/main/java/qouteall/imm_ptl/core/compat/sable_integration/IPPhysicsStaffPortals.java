/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * Re-framing a drag when its player or ship crosses follows IPSable 0.5.0's IplStaffDragSessionOverwriteMixin by
 * r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.physics_staff.PhysicsStaffServerHandler;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.mixin_create.IPPhysicsStaffDragSessionAccessor;
import qouteall.imm_ptl.core.compat.mixin_create.IPPhysicsStaffServerHandlerAccessor;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalSimilarity;
import qouteall.imm_ptl.core.portal.Mirror;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.global_portals.GlobalPortalStorage;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * The Create Aeronautics (Simulated) physics staff across portals, server side (docs/lessons/physics-staff.md).
 * <p>
 * A drag lives in the staff handler of the ship's own level; its goal is the player's eye plus the player-relative
 * goal the client sends, both in the player's world, and the orientation the client sends is in the player's world
 * too. A {@link Grab} holds the map from the player's world into the ship's world whenever the two differ: a ship
 * picked through a portal or by its far half (the client names the portals, {@link RemoteCallables#grab}), a player
 * who went through a portal while dragging ({@link #onPlayerTeleported}), a dragged ship moved to another dimension
 * ({@link #onShipMoved}). Packets are routed to the ship's level by its UUID. Without a grab the staff works as it
 * does without IP.
 */
public final class IPPhysicsStaffPortals {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean ACTIVE = ModList.get().isLoaded("simulated") && ModList.get().isLoaded("sable");
    private static final String CLIENT = "qouteall.imm_ptl.core.compat.sable_integration.IPPhysicsStaffPortalsClient.RemoteCallables.";

    static final String LINKS = "links";
    static final String LINK_PORTAL = "portal";
    static final String LINK_DIM = "dim";
    static final String LINK_INVERSE = "inverse";
    static final int MAX_LINKS = 2;

    /**
     * @param frame maps the player's world ({@code playerDim}) into the ship's world
     */
    public record Grab(UUID ship, ResourceKey<Level> playerDim, IPPortalSimilarity frame) {
    }

    /** By player. Server thread only. */
    private static final Map<UUID, Grab> GRABS = new HashMap<>();

    private IPPhysicsStaffPortals() {
    }

    public static void reset() {
        GRABS.clear();
    }

    /**
     * @return whether the staff maps through {@code portal}: any portal but a mirror (a scaled one through its
     * similarity, {@link #mapOf})
     */
    public static boolean isMappable(final Portal portal) {
        return !(portal instanceof Mirror);
    }

    /**
     * @return the map through {@code portal} for the staff's and handles' frames: rigid when unscaled
     */
    public static IPPortalSimilarity mapOf(final Portal portal) {
        return Math.abs(portal.getScale() - 1.0) < 1.0e-6
            ? IPPortalSimilarity.rigid(IPPortalIsometry.of(portal)) : IPPortalSimilarity.of(portal);
    }

    @Nullable
    private static Grab grabOf(final UUID player, final UUID ship) {
        final Grab grab = GRABS.get(player);
        return grab != null && grab.ship().equals(ship) ? grab : null;
    }

    // ---- packets

    /**
     * @return the level whose staff handler takes {@code player}'s drag of {@code ship}, or null to drop the packet
     */
    @Nullable
    public static ServerLevel dragLevel(final ServerPlayer player, final UUID ship) {
        final ServerLevel own = player.serverLevel();
        final Grab grab = grabOf(player.getUUID(), ship);
        if (grab == null) {
            // as without IP: the handler of the player's level ignores ships of other levels
            return hasShip(own, ship) ? own : null;
        }
        if (!own.dimension().equals(grab.playerDim())) {
            return null;
        }
        return levelOf(player.server, ship);
    }

    /**
     * @return the drag orientation the client sent (in the player's world), in the ship's world
     */
    public static Quaterniondc dragOrientation(final ServerPlayer player, final UUID ship, final Quaterniondc orientation) {
        final Grab grab = grabOf(player.getUUID(), ship);
        return grab == null ? orientation : new Quaterniond(grab.frame().rotation()).mul(orientation);
    }

    /**
     * @return the plot anchor of the drag: a drag under way keeps its own (the client's names the old plot for a while
     * after the ship moved to another dimension), a new one takes the client's
     */
    public static Vector3dc dragAnchor(final PhysicsStaffServerHandler handler, final UUID player, final UUID ship, final Vector3dc anchor) {
        final Object session = sessions(handler).get(player);
        if (session instanceof final IPPhysicsStaffDragSessionAccessor drag && drag.ip_getSubLevel().getUniqueId().equals(ship)) {
            return new Vector3d(drag.ip_getPlotAnchor());
        }
        return anchor;
    }

    /**
     * @return the staff handler that toggles the lock of {@code ship}: the one of the ship's level
     */
    public static PhysicsStaffServerHandler lockHandler(final ServerPlayer player, final UUID ship, final PhysicsStaffServerHandler own) {
        if (hasShip(player.serverLevel(), ship)) {
            return own;
        }
        final ServerLevel level = levelOf(player.server, ship);
        return level == null ? own : PhysicsStaffServerHandler.get(level);
    }

    /**
     * Ends {@code player}'s drag in whichever level it is.
     */
    public static void stopDragging(final MinecraftServer server, final UUID player) {
        for (final ServerLevel level : server.getAllLevels()) {
            final PhysicsStaffServerHandler handler = PhysicsStaffServerHandler.get(level);
            if (sessions(handler).containsKey(player)) {
                handler.stopDragging(player);
            }
        }
    }

    // ---- drag sessions

    /**
     * @param own the player as Simulated looks it up, in the ship's level
     * @return the player driving the drag: while the drag has a grab, looked up server-wide and only while in the world
     * the grab's frame starts in
     */
    @Nullable
    public static Player dragPlayer(final ServerLevel level, final UUID player, final UUID ship, @Nullable final Player own) {
        final Grab grab = grabOf(player, ship);
        if (grab == null) {
            return own;
        }
        final ServerPlayer found = level.getServer().getPlayerList().getPlayer(player);
        return found != null && found.level().dimension().equals(grab.playerDim()) ? found : null;
    }

    /**
     * Maps the drag's goal from the player's world into the ship's, in place.
     */
    public static void mapGoal(final UUID player, final UUID ship, final Vector3d goal) {
        final Grab grab = grabOf(player, ship);
        if (grab != null) {
            final Vec3 mapped = grab.frame().mapPoint(new Vec3(goal.x, goal.y, goal.z));
            goal.set(mapped.x, mapped.y, mapped.z);
        }
    }

    public static void onDragRemoved(final UUID player, final UUID ship) {
        if (grabOf(player, ship) != null) {
            GRABS.remove(player);
        }
    }

    // ---- frame changes

    /**
     * {@code player} went through {@code portal} from {@code from}: a drag keeps its goal where it was.
     */
    public static void onPlayerTeleported(final ServerPlayer player, final ResourceKey<Level> from, final Portal portal) {
        if (!ACTIVE) {
            return;
        }
        PhysicsStaffServerHandler handler = null;
        IPPhysicsStaffDragSessionAccessor drag = null;
        for (final ServerLevel level : player.server.getAllLevels()) {
            final PhysicsStaffServerHandler candidate = PhysicsStaffServerHandler.get(level);
            if (sessions(candidate).get(player.getUUID()) instanceof final IPPhysicsStaffDragSessionAccessor session) {
                handler = candidate;
                drag = session;
                break;
            }
        }
        if (drag == null) {
            GRABS.remove(player.getUUID());
            return;
        }
        final UUID ship = drag.ip_getSubLevel().getUniqueId();
        Grab grab = grabOf(player.getUUID(), ship);
        if (grab == null) {
            grab = new Grab(ship, from, IPPortalSimilarity.IDENTITY);
        }
        if (!isMappable(portal) || !grab.playerDim().equals(from)) {
            LOGGER.info("[IPStaff] {} went through portal {} while dragging ship {}: drag ended ({})", player.getGameProfile().getName(),
                portal.getUUID(), ship, isMappable(portal) ? "not in the drag's world" : "mirror");
            handler.stopDragging(player.getUUID());
            return;
        }
        final IPPortalSimilarity through = mapOf(portal);
        GRABS.put(player.getUUID(), new Grab(ship, portal.getDestDim(), through.inverse().then(grab.frame())));
        // until the client's next drag packet (already in the new world's frame) the goal offset is the old one, turned
        through.rotation().transform(drag.ip_getPlayerRelativeGoal());
        if (through.isScaled()) {
            drag.ip_getPlayerRelativeGoal().mul(through.scale());
        }
        LOGGER.info("[IPStaff] {} went through portal {} ({} -> {}) while dragging ship {}", player.getGameProfile().getName(),
            portal.getUUID(), from.location(), portal.getDestDim().location(), ship);
    }

    /**
     * {@code player} was carried through {@code portal} with a ship: their client's drag or held handle turns as for
     * their own teleport.
     */
    static void tellCarried(final ServerPlayer player, final Portal portal) {
        if (!isMappable(portal)) {
            return;
        }
        final IPPortalSimilarity through = mapOf(portal);
        final Quaterniondc q = through.rotation();
        McRemoteProcedureCall.tellClientToInvoke(player, CLIENT + "onCarriedThrough", portal.getDestDim(),
            through.origin(), through.dest(), new DQuaternion(q.x(), q.y(), q.z(), q.w()), through.scale());
    }

    /**
     * {@code shipId} went from {@code src} through {@code portal} and is now {@code moved}: drags of it move to the
     * destination's staff handler, with the anchor in the new plot and the frame extended by the portal.
     */
    public static void onShipMoved(final ServerLevel src, final ServerLevel dst, final UUID shipId, final Portal portal,
                                   final IPSableBridge.Moved moved) {
        if (!ACTIVE) {
            return;
        }
        final PhysicsStaffServerHandler from = PhysicsStaffServerHandler.get(src);
        for (final Map.Entry<UUID, Object> entry : new ArrayList<>(sessions(from).entrySet())) {
            if (!(entry.getValue() instanceof final IPPhysicsStaffDragSessionAccessor drag) || !drag.ip_getSubLevel().getUniqueId().equals(shipId)) {
                continue;
            }
            final UUID playerId = entry.getKey();
            final ServerPlayer player = src.getServer().getPlayerList().getPlayer(playerId);
            final Grab grab = grabOf(playerId, shipId);
            if (player == null || !isMappable(portal)) {
                from.stopDragging(playerId);
                continue;
            }
            final IPPortalSimilarity through = mapOf(portal);
            final Vector3d relative = new Vector3d(drag.ip_getPlayerRelativeGoal());
            final Vector3d anchor = new Vector3d(drag.ip_getPlotAnchor()).add(moved.shift().getX(), moved.shift().getY(), moved.shift().getZ());
            final Quaterniond orientation = new Quaterniond(through.rotation()).mul(drag.ip_getOrientation());
            final Grab carried = new Grab(shipId, grab != null ? grab.playerDim() : player.level().dimension(),
                (grab != null ? grab.frame() : IPPortalSimilarity.IDENTITY).then(through));

            from.stopDragging(playerId);
            PhysicsStaffServerHandler.get(dst).drag(playerId, shipId, relative, anchor, orientation);
            GRABS.put(playerId, carried);
            final Quaterniondc q = through.rotation();
            McRemoteProcedureCall.tellClientToInvoke(player, CLIENT + "onShipMoved", shipId, dst.dimension(), moved.shift(),
                through.origin(), through.dest(), new DQuaternion(q.x(), q.y(), q.z(), q.w()), through.scale());
            LOGGER.info("[IPStaff] {}'s drag of ship {} follows it to {}", player.getGameProfile().getName(), shipId, dst.dimension().location());
        }
    }

    // ---- lookups

    @SuppressWarnings("unchecked")
    private static Map<UUID, Object> sessions(final PhysicsStaffServerHandler handler) {
        return ((IPPhysicsStaffServerHandlerAccessor) handler).ip_getDraggingSessions();
    }

    static boolean hasShip(final ServerLevel level, final UUID ship) {
        final SubLevelContainer container = SubLevelContainer.getContainer(level);
        final SubLevel sub = container == null ? null : container.getSubLevel(ship);
        return sub != null && !sub.isRemoved();
    }

    @Nullable
    static ServerLevel levelOf(final MinecraftServer server, final UUID ship) {
        for (final ServerLevel level : server.getAllLevels()) {
            if (hasShip(level, ship)) {
                return level;
            }
        }
        return null;
    }

    @Nullable
    private static Portal findPortal(final ServerLevel level, final UUID id) {
        final Entity entity = level.getEntity(id);
        if (entity instanceof final Portal portal) {
            return portal;
        }
        for (final Portal portal : GlobalPortalStorage.get(level).data) {
            if (portal.getUUID().equals(id)) {
                return portal;
            }
        }
        return null;
    }

    /**
     * @return the map from {@code player}'s world into {@code shipLevel} along the portals named by {@code chain}, or
     * null when they do not lead there (or one is a mirror)
     */
    @Nullable
    static IPPortalSimilarity resolve(final ServerPlayer player, final ServerLevel shipLevel, final ListTag links) {
        ResourceKey<Level> at = player.level().dimension();
        IPPortalSimilarity frame = IPPortalSimilarity.IDENTITY;
        for (int i = 0; i < links.size(); i++) {
            final CompoundTag link = links.getCompound(i);
            final ResourceLocation dimId = ResourceLocation.tryParse(link.getString(LINK_DIM));
            final ServerLevel level = dimId == null ? null : player.server.getLevel(ResourceKey.create(Registries.DIMENSION, dimId));
            final Portal portal = level == null || !link.hasUUID(LINK_PORTAL) ? null : findPortal(level, link.getUUID(LINK_PORTAL));
            if (portal == null || !isMappable(portal)) {
                return null;
            }
            if (link.getBoolean(LINK_INVERSE)) {
                if (!portal.getDestDim().equals(at)) {
                    return null;
                }
                frame = frame.then(mapOf(portal).inverse());
                at = level.dimension();
            } else {
                if (!level.dimension().equals(at)) {
                    return null;
                }
                frame = frame.then(mapOf(portal));
                at = portal.getDestDim();
            }
        }
        return at.equals(shipLevel.dimension()) ? frame : null;
    }

    public static final class RemoteCallables {
        private RemoteCallables() {
        }

        /**
         * The client started dragging {@code ship} (of {@code shipDim}), seen through the portals in {@code chain}
         * (none: in the player's own world). Sent before its first drag packet.
         */
        public static void grab(final ServerPlayer player, final UUID ship, final ResourceKey<Level> shipDim, final CompoundTag chain) {
            if (!ACTIVE) {
                return;
            }
            GRABS.remove(player.getUUID());
            final ListTag links = chain.getList(LINKS, Tag.TAG_COMPOUND);
            if (links.isEmpty() || links.size() > MAX_LINKS) {
                return;
            }
            final ServerLevel shipLevel = player.server.getLevel(shipDim);
            final IPPortalSimilarity frame = shipLevel == null || !hasShip(shipLevel, ship) ? null : resolve(player, shipLevel, links);
            if (frame == null) {
                LOGGER.info("[IPStaff] {} cannot drag ship {} of {} through the portals named: they do not lead there",
                    player.getGameProfile().getName(), ship, shipDim.location());
                return;
            }
            GRABS.put(player.getUUID(), new Grab(ship, player.level().dimension(), frame));
            LOGGER.info("[IPStaff] {} drags ship {} of {} from {} through {} portal(s)", player.getGameProfile().getName(), ship,
                shipDim.location(), player.level().dimension().location(), links.size());
        }
    }
}
