/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalAnchors;
import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.compat.sable_physics.IPPhysicsPipelineProvider;
import org.joml.Quaterniond;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddle;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;
import qouteall.q_misc_util.my_util.DQuaternion;

/**
 * Server-side ship-through-portal driver (Sable classes are only referenced here, behind
 * {@link IPShipPortalTraversal}). Every {@value #SCAN_INTERVAL_TICKS} ticks, outside a physics step, scans
 * every {@link ServerSubLevel} in every level; a ship whose hull AABB intersects a teleportable scale-1
 * portal that it is moving into (judged from the hull centre at the previous scan) is moved via {@link IPSableBridge#moveThroughPortal} (per-ship cooldown), then "suppressed" until it
 * is clear of any portal; if it stays overlapping for {@value #STUCK_TICKS} ticks it is shoved along the
 * portal normal. Before the move (and the shove) the hull is placed where it overlaps no terrain, portal or other
 * ship ({@link IPShipArrivalClearance}); when there is no such place near the destination portal the crossing is
 * refused and the ship pushed back out of the portal. Also ticks rider remounts, the ragdoll guard and {@link IPShipThroughPortalSync}.
 * While {@link IPStraddle#isAvailable()}, ships straddle portals instead and the scan does not run.
 */
public final class IPShipPortalTraversalImpl {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int SCAN_INTERVAL_TICKS = 4;
    private static final int COOLDOWN_TICKS = 100;
    private static final double MAX_SUBLEVEL_VOLUME = 2000000.0;
    private static final int STUCK_TICKS = 200;
    /** Per ship: tick of the last move (or shove), for the {@value #COOLDOWN_TICKS}-tick cooldown. */
    private static final Map<UUID, Long> LAST_MOVE_TICK = new HashMap<>();
    /** Ships that just arrived and must leave the portal before they may enter one again. */
    private static final Set<UUID> SUPPRESSED_UNTIL_CLEAR = new HashSet<>();
    /** Per suppressed ship: tick since which it has been sitting in a portal mouth. */
    private static final Map<UUID, Long> STUCK_SINCE = new HashMap<>();
    /** Per ship: hull centre at the previous scan, to tell which way it is moving through a portal. */
    private static final Map<UUID, Vec3> LAST_HULL_CENTER = new HashMap<>();
    /** Movement into a portal between two scans below this (0.25 m/s) does not carry a ship through. */
    private static final double STILL_EPSILON = 0.05;
    /** Portals and ships this far beyond the hull size around an arrival point are outside the clearance search. */
    private static final double KEEP_OUT_MARGIN = 32.0;
    /** Speed [m/s] a ship whose crossing was refused is given away from the portal. */
    private static final double REFUSED_PUSH_BACK_SPEED = 1.0;
    private static final long TICK_FAILURE_LOG_INTERVAL = 6000;
    /** Per part of the ship tick that failed: the tick last logged, the failures since. */
    private static final Map<String, long[]> TICK_FAILURES = new HashMap<>();

    private IPShipPortalTraversalImpl() {
    }

    /**
     * Forgets everything about ships, before a server's levels load and when it stops. The per-ship state holds server
     * tick counts, which restart at 0 with every server: in singleplayer the state of the previous world load would
     * otherwise block a ship's crossings (cooldown) until the new server's tick count caught up. It also holds levels
     * and ships, which a stopped server must not keep alive. Not at the first tick: what the levels fill in while they
     * load (a ship's joints) would be lost.
     */
    static void clearState() {
        LAST_MOVE_TICK.clear();
        SUPPRESSED_UNTIL_CLEAR.clear();
        STUCK_SINCE.clear();
        LAST_HULL_CENTER.clear();
        TICK_FAILURES.clear();
        IPShipThroughPortalSync.reset();
        IPShipRiderCarry.reset();
        IPShipRagdollGuard.reset();
        IPStraddle.clearState();
        IPShipPortalAnchors.clearState();
        IPPhysicsStaffHooks.clearState();
    }

    /** A server's first tick: its levels are up. */
    static void onServerStarted(MinecraftServer server) {
        IPStraddle.onServerStarted();
        IPShipTeleportJournal.replayPending(server);
    }

    /** An operator should know when ships fall back to one-step crossings: nothing else shows it in game. */
    static void onPlayerJoined(ServerPlayer player) {
        String reason = IPPhysicsPipelineProvider.getFallbackReason();
        if (reason != null && player.hasPermissions(2)) {
            player.sendSystemMessage(Component.literal(
                "Immersive Portals: Aeronautics: ships cannot straddle portals here, they cross in one step: " + reason
            ).withStyle(ChatFormatting.GOLD));
        }
    }

    /**
     * Runs one part of the ship tick, so that a part that keeps failing costs the others nothing. Logged the first time
     * and then every {@value #TICK_FAILURE_LOG_INTERVAL} ticks, with how many failed since.
     */
    private static void guarded(String part, long tick, Runnable action) {
        try {
            action.run();
        } catch (Throwable e) {
            long[] failure = TICK_FAILURES.computeIfAbsent(part, key -> new long[]{Long.MIN_VALUE, 0});
            failure[1]++;
            if (tick - failure[0] >= TICK_FAILURE_LOG_INTERVAL) {
                LOGGER.error("[IPShipPortal] {} failed in the ship tick ({} time(s) since the last report)", part, failure[1], e);
                failure[0] = tick;
                failure[1] = 0;
            }
        }
    }

    static void tick(MinecraftServer server) {
        long tick = server.getTickCount();
        guarded("riders' remounts", tick, () -> IPShipRiderCarry.tickPendingRemounts(server));
        guarded("the ragdoll guard", tick, IPShipRagdollGuard::tryRegister);
        guarded("ship-mounted portals", tick, () -> IPShipPortalAnchors.tick(server));
        guarded("through-portal sync", tick, () -> IPShipThroughPortalSync.tick(server, tick));

        if (SubLevelPhysicsSystem.IN_PHYSICS_STEP) {
            return;
        }

        if (IPStraddle.isAvailable()) {
            // ships straddle portals and are handed off once through (docs/lessons/straddling.md); replaces the scan below
            guarded("straddling", tick, () -> IPStraddle.tick(server, tick));
            return;
        }

        if (tick % SCAN_INTERVAL_TICKS != 0L) {
            return;
        }

        HashSet<UUID> seenShips = new HashSet<>();
        boolean scanFailed = false;

        for (ServerLevel level : server.getAllLevels()) {
            try {
                scanLevel(server, level, tick, seenShips);
            } catch (Throwable e) {
                scanFailed = true;
                LOGGER.warn("[IPShipPortal] scan failed in {}: {}", level.dimension().location(), e.toString());
            }
        }

        if (!scanFailed) {
            pruneState(server, seenShips);
        }
    }

    /**
     * Drops per-ship state for ships that no longer exist (otherwise the maps grow forever).
     * Ships whose chunks are merely unloaded ("held" by Sable) keep their state, so a ship that
     * arrived overlapping a portal and then unloaded stays suppressed when it comes back instead
     * of being sent straight back through the portal.
     */
    private static void pruneState(MinecraftServer server, Set<UUID> liveShips) {
        HashSet<UUID> stale = new HashSet<>();
        stale.addAll(LAST_MOVE_TICK.keySet());
        stale.addAll(SUPPRESSED_UNTIL_CLEAR);
        stale.addAll(STUCK_SINCE.keySet());
        stale.addAll(LAST_HULL_CENTER.keySet());
        stale.removeAll(liveShips);
        if (stale.isEmpty()) {
            return;
        }
        stale.removeIf(uuid -> isHeldAnywhere(server, uuid));
        LAST_MOVE_TICK.keySet().removeAll(stale);
        SUPPRESSED_UNTIL_CLEAR.removeAll(stale);
        STUCK_SINCE.keySet().removeAll(stale);
        LAST_HULL_CENTER.keySet().removeAll(stale);
    }

    /** @return whether Sable holds a ship whose chunks are not loaded, in any level */
    private static boolean isHeldAnywhere(MinecraftServer server, UUID shipId) {
        for (ServerLevel level : server.getAllLevels()) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && container.getHoldingChunkMap().getHoldingSubLevel(shipId) != null) {
                return true;
            }
        }
        return false;
    }

    private static void scanLevel(MinecraftServer server, ServerLevel level, long tick, Set<UUID> seenShips) {
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }

        List<ServerSubLevel> subLevels = container.getAllSubLevels();
        if (subLevels.isEmpty()) {
            return;
        }

        for (ServerSubLevel sub : List.copyOf(subLevels)) {
            if (sub.isRemoved()) {
                continue;
            }

            seenShips.add(sub.getUniqueId());

            try {
                considerShip(server, level, sub, tick);
            } catch (Throwable e) {
                LOGGER.warn("[IPShipPortal] ship {} failed", sub.getUniqueId(), e);
            }
        }
    }

    private static void considerShip(MinecraftServer server, ServerLevel level, ServerSubLevel sub, long tick) {
        UUID shipId = sub.getUniqueId();
        if (IPShipRagdollGuard.isRagdollSubLevel(sub)) {
            return;
        }

        AABB hull = IPSableBridge.worldAabb(sub).inflate(1.0);
        double volume = hull.getXsize() * hull.getYsize() * hull.getZsize();
        if (volume > MAX_SUBLEVEL_VOLUME) {
            return;
        }

        Vec3 center = hull.getCenter();
        Vec3 previousCenter = LAST_HULL_CENTER.put(shipId, center);
        if (previousCenter == null) {
            // first sight: the direction of travel is known from the next scan on
            return;
        }

        List<Portal> candidates = overlappingPortals(level, hull);
        if (candidates.isEmpty()) {
            SUPPRESSED_UNTIL_CLEAR.remove(shipId);
            STUCK_SINCE.remove(shipId);
            return;
        }

        if (SUPPRESSED_UNTIL_CLEAR.contains(shipId)) {
            long stuckSince = STUCK_SINCE.computeIfAbsent(shipId, id -> tick);
            if (tick - stuckSince > STUCK_TICKS) {
                if (nudgeClearOfPortal(level, sub, facingPortal(candidates, center))) {
                    SUPPRESSED_UNTIL_CLEAR.remove(shipId);
                    STUCK_SINCE.remove(shipId);
                    LAST_MOVE_TICK.put(shipId, tick);
                } else {
                    // stays suppressed; try again after another STUCK_TICKS
                    STUCK_SINCE.put(shipId, tick);
                }
            }
            return;
        }

        STUCK_SINCE.remove(shipId);
        Portal portal = enteredPortal(candidates, previousCenter, center);
        if (portal == null) {
            return;
        }

        Long lastMove = LAST_MOVE_TICK.get(shipId);
        if (lastMove == null || tick - lastMove >= COOLDOWN_TICKS) {
            ServerLevel destLevel = server.getLevel(portal.getDestDim());
            if (destLevel != null) {
                moveThroughPortal(level, destLevel, sub, portal, tick);
            }
        } else {
            LOGGER.debug("[IPShipPortal] ship {} is moving into {} but crossed {} ticks ago; not moved", shipId, portal, tick - lastMove);
        }
    }

    /**
     * Computes the arrival pose for {@code sub} on the other side of {@code portal} and performs the move, or
     * refuses the crossing when the hull fits nowhere near the destination portal.
     */
    private static void moveThroughPortal(ServerLevel level, ServerLevel destLevel, ServerSubLevel sub, Portal portal, long tick) {
        UUID shipId = sub.getUniqueId();
        // a ship carrying portals going through a portal shatters them first (docs/lessons/ship-mounted-portals.md)
        IPShipPortalAnchors.shatterCarriedPortals(shipId, "its ship is going through a portal");
        Vec3 destPos = portal.transformPoint(toVec3(sub));
        Quaterniond rotation = portalRotation(portal);
        Vec3 outDir = portal.getContentDirection().normalize();

        try {
            // push the hull fully out of the portal plane on the far side
            AABB rawHull = IPSableBridge.worldAabb(sub);
            double halfExtent = 0.5 * Math.max(rawHull.getXsize(), Math.max(rawHull.getYsize(), rawHull.getZsize()));
            destPos = destPos.add(outDir.scale(halfExtent + 2.0));
        } catch (Throwable ignored) {
        }

        try {
            Vec3 clear = IPShipArrivalClearance.findClearPosition(
                destLevel, shipId, IPSableBridge.worldAabb(sub), toVec3(sub), shipShape(sub), destPos, rotation, outDir,
                portalBoxes(destLevel, destPos, IPSableBridge.worldAabb(sub)),
                otherShipBoxes(destLevel, shipId, destPos, IPSableBridge.worldAabb(sub))
            );
            if (clear == null) {
                LOGGER.info(
                    "[IPShipPortal] no room for ship {} on the far side of the portal in {}; crossing refused",
                    shipId, destLevel.dimension().location()
                );
                LAST_MOVE_TICK.put(shipId, tick);
                pushBackOutOfPortal(sub, portal);
                return;
            }
            destPos = clear;
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] terrain clearance check failed for ship {}; arriving at the mirrored position: {}", shipId, e.toString());
        }

        LOGGER.info(
            "[IPShipPortal] ship {} entered portal in {} -> {} at {}{}",
            shipId,
            level.dimension().location(),
            destLevel.dimension().location(),
            destPos,
            rotation == null ? "" : " (turned)"
        );
        LAST_MOVE_TICK.put(shipId, tick);
        SUPPRESSED_UNTIL_CLEAR.add(shipId);
        LAST_HULL_CENTER.remove(shipId);
        IPSableBridge.Moved moved = IPSableBridge.moveThroughPortal(sub, level, destLevel, destPos, rotation);
        if (moved == null) {
            LOGGER.warn("[IPShipPortal] move failed for ship {} (see above)", shipId);
        } else {
            LOGGER.info("[IPShipPortal] ship {} arrived in {} (plot-space shift {})", shipId, destLevel.dimension().location(), moved.shift());
            IPShipThroughPortalSync.onShipLeftDimension(level.getServer(), shipId, level.dimension());
            IPPhysicsStaffHooks.onShipMoved(level, destLevel, shipId, portal, moved);
        }
    }

    /**
     * The portals a ship placed around {@code center} must keep its AABB clear of: every portal it could be
     * carried through, grown by the margin {@link #considerShip} adds to the hull. {@code hull} is the ship's
     * current world AABB, used for the search radius.
     */
    private static List<AABB> portalBoxes(ServerLevel level, Vec3 center, AABB hull) {
        List<AABB> boxes = new ArrayList<>();
        for (Portal portal : qouteall.imm_ptl.core.compat.IPPortalLookup.touching(level, searchArea(center, hull), portal -> true)) {
            if (isShipPortal(portal)) {
                boxes.add(portal.getBoundingBox().inflate(1.0));
            }
        }
        return boxes;
    }

    /** The world AABBs of the other ships around {@code center}, which the clearance search treats as solid. */
    private static List<AABB> otherShipBoxes(ServerLevel level, UUID shipId, Vec3 center, AABB hull) {
        AABB area = searchArea(center, hull);
        List<AABB> boxes = new ArrayList<>();
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container != null) {
            for (ServerSubLevel other : container.getAllSubLevels()) {
                if (!other.isRemoved() && !other.getUniqueId().equals(shipId)) {
                    AABB box = IPSableBridge.worldAabb(other);
                    if (box.intersects(area)) {
                        boxes.add(box);
                    }
                }
            }
        }
        return boxes;
    }

    private static AABB searchArea(Vec3 center, AABB hull) {
        double radius = Math.max(hull.getXsize(), Math.max(hull.getYsize(), hull.getZsize())) + KEEP_OUT_MARGIN;
        return new AABB(center, center).inflate(radius);
    }

    /**
     * The ship's blocks with a collision shape, as world offsets from its pose position, for the clearance
     * search. Null (the search then tests the whole hull box) when the plot cannot be read.
     */
    private static IPShipArrivalClearance.@Nullable Shape shipShape(ServerSubLevel sub) {
        try {
            Pose3dc pose = sub.logicalPose();
            Vector3dc position = pose.position();
            Vector3d plotPoint = new Vector3d();
            List<Vector3d> origins = new ArrayList<>();
            MutableBlockPos pos = new MutableBlockPos();
            for (PlotChunkHolder holder : sub.getPlot().getLoadedChunks()) {
                LevelChunk chunk = holder.getChunk();
                if (chunk == null) {
                    continue;
                }
                int baseX = chunk.getPos().getMinBlockX();
                int baseZ = chunk.getPos().getMinBlockZ();
                LevelChunkSection[] sections = chunk.getSections();
                for (int index = 0; index < sections.length; index++) {
                    LevelChunkSection section = sections[index];
                    if (section == null || section.hasOnlyAir()) {
                        continue;
                    }
                    int baseY = SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(index));
                    for (int y = 0; y < 16; y++) {
                        for (int z = 0; z < 16; z++) {
                            for (int x = 0; x < 16; x++) {
                                BlockState state = section.getBlockState(x, y, z);
                                if (state.isAir()) {
                                    continue;
                                }
                                pos.set(baseX + x, baseY + y, baseZ + z);
                                if (state.getCollisionShape(chunk, pos).isEmpty()) {
                                    continue;
                                }
                                pose.transformPosition(plotPoint.set(pos.getX(), pos.getY(), pos.getZ()));
                                origins.add(plotPoint.sub(position, new Vector3d()));
                            }
                        }
                    }
                }
            }
            if (origins.isEmpty()) {
                return null;
            }

            // the plot's unit edges in world space (the pose is affine, so the same for every block)
            Vector3d corner = pose.transformPosition(new Vector3d(0.0, 0.0, 0.0));
            Vector3d edgeX = pose.transformPosition(new Vector3d(1.0, 0.0, 0.0)).sub(corner);
            Vector3d edgeY = pose.transformPosition(new Vector3d(0.0, 1.0, 0.0)).sub(corner);
            Vector3d edgeZ = pose.transformPosition(new Vector3d(0.0, 0.0, 1.0)).sub(corner);
            return new IPShipArrivalClearance.Shape(origins, edgeX, edgeY, edgeZ);
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] could not read the blocks of ship {}; testing its whole hull box: {}", sub.getUniqueId(), e.toString());
            return null;
        }
    }

    /**
     * Refused crossing: removes the ship's velocity into the portal and gives it a small push back out, so it
     * does not drift through the frame in its own dimension.
     */
    private static void pushBackOutOfPortal(ServerSubLevel sub, Portal portal) {
        IPSableBridge.pushBackOutOfPortal(sub, portal.getNormal(), REFUSED_PUSH_BACK_SPEED);
    }

    /** The portal's rotation as a JOML quaternion, or null when the portal is parallel (no turn). */
    private static Quaterniond portalRotation(Portal portal) {
        try {
            DQuaternion portalRotation = portal.getRotation();
            LOGGER.debug(
                "[IPShipPortal] portal geometry: in={} out={} rotation={}",
                portal.getNormal().scale(-1.0),
                portal.getContentDirection(),
                portalRotation == null ? "null (parallel - no turn)" : portalRotation.toString()
            );
            return portalRotation == null
                ? null
                : new Quaterniond(portalRotation.getX(), portalRotation.getY(), portalRotation.getZ(), portalRotation.getW());
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] heading transform failed, keeping orientation: {}", e.toString());
            return null;
        }
    }

    /**
     * Teleports a ship that sat in a portal mouth for {@value #STUCK_TICKS} ticks along the portal normal, to the
     * nearest position there where it overlaps no terrain, portal or other ship. Left in place when there is none.
     *
     * @return whether the ship was moved
     */
    private static boolean nudgeClearOfPortal(ServerLevel level, ServerSubLevel sub, Portal portal) {
        try {
            AABB hull = IPSableBridge.worldAabb(sub);
            double halfExtent = 0.5 * Math.max(hull.getXsize(), Math.max(hull.getYsize(), hull.getZsize()));
            Vec3 normal = portal.getNormal().normalize();
            Vec3 shipPos = toVec3(sub);
            Vec3 target = shipPos.add(normal.scale(halfExtent + 3.0));
            target = IPShipArrivalClearance.findClearPosition(
                level, sub.getUniqueId(), hull, shipPos, shipShape(sub), target, null, normal,
                portalBoxes(level, target, hull), otherShipBoxes(level, sub.getUniqueId(), target, hull)
            );
            if (target == null) {
                LOGGER.info("[IPShipPortal] ship {} is stuck in the portal mouth, but there is no room to shove it clear", sub.getUniqueId());
                return false;
            }

            RigidBodyHandle body = RigidBodyHandle.of(sub);
            if (body == null) {
                return false;
            }

            body.teleport(new Vector3d(target.x, target.y, target.z), new Quaterniond(sub.logicalPose().orientation()));
            LOGGER.info("[IPShipPortal] ship {} was stuck in the portal mouth for 10s; shoved clear to {}", sub.getUniqueId(), target);
            return true;
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] could not free stuck ship: {}", e.toString());
            return false;
        }
    }

    private static Vec3 toVec3(ServerSubLevel sub) {
        Vector3d pos = sub.logicalPose().position();
        return new Vec3(pos.x(), pos.y(), pos.z());
    }

    /**
     * The teleportable, unscaled, intact portals whose bounding box intersects the hull. Breakable portals
     * whose blocks are gone are broken instead of listed.
     */
    private static List<Portal> overlappingPortals(ServerLevel level, AABB hull) {
        List<Portal> result = new ArrayList<>();
        for (Portal portal : qouteall.imm_ptl.core.compat.IPPortalLookup.touching(level, hull, portal -> true)) {
            if (isShipPortal(portal) && portal.getBoundingBox().intersects(hull)) {
                if (portal instanceof BreakablePortalEntity breakable && !breakable.isIntactNow()) {
                    // its blocks are gone (e.g. assembled into this very ship): never carry a ship through a dead portal
                    LOGGER.info("[IPShipPortal] ignoring and breaking {}: its portal blocks are no longer in place", portal);
                    breakable.breakPortalNow();
                    continue;
                }
                result.add(portal);
            }
        }
        return result;
    }

    /** A portal ships are carried through: teleportable, with a destination, unscaled. */
    private static boolean isShipPortal(Portal portal) {
        // ships do not go through ship-mounted portals (docs/lessons/ship-mounted-portals.md)
        return portal.getTeleportable() && portal.getDestDim() != null && !(Math.abs(portal.getScale() - 1.0) > 1.0E-4)
            && !IPShipPortalAnchors.isShipMounted(portal);
    }

    /** Signed distance of {@code point} from the portal plane, positive in front of the portal. */
    private static double sideOffset(Portal portal, Vec3 point) {
        return point.subtract(portal.getOriginPos()).dot(portal.getNormal());
    }

    /** The candidate the hull centre is furthest in front of (largest signed offset along the portal normal). */
    private static Portal facingPortal(List<Portal> candidates, Vec3 center) {
        Portal best = null;
        double bestSideOffset = 0.0;
        for (Portal portal : candidates) {
            double offset = sideOffset(portal, center);
            if (best == null || offset > bestSideOffset) {
                best = portal;
                bestSideOffset = offset;
            }
        }
        return best;
    }

    /**
     * The portal the ship is passing through, judged by the direction of travel: the candidate the hull
     * centre was in front of at the previous scan and has moved towards since (it may already have crossed
     * the plane; a frame has one portal entity per side and the one now facing the centre would deliver
     * the ship to the entry side of the destination). A ship that only touches a portal without moving into it
     * (standing still, falling past it, tumbling after landing beside it) is left alone.
     */
    private static @Nullable Portal enteredPortal(List<Portal> candidates, Vec3 previousCenter, Vec3 center) {
        Portal best = null;
        double bestApproach = STILL_EPSILON;
        for (Portal portal : candidates) {
            double before = sideOffset(portal, previousCenter);
            double approach = before - sideOffset(portal, center);
            if (before > 0.0 && approach > bestApproach) {
                best = portal;
                bestApproach = approach;
            }
        }
        if (best == null) {
            LOGGER.debug("[IPShipPortal] ship touches {} but is not moving into it; not moved", facingPortal(candidates, center));
        }
        return best;
    }
}
