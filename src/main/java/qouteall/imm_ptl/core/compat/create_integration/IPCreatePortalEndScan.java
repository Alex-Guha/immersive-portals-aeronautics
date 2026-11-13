/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.content.trains.track.TrackBlock;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalEnds.PLANE_EPSILON;
import static qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalEnds.SABLE;
import static qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalEnds.SHIP_COS;
import static qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalEnds.SHIP_GAP;

/**
 * The client's one walk over the portal blocks of every client world, for what Create parts draw at a portal: half
 * shafts, pipe stubs, track stubs and belt looks each take the portal blocks from it ({@link Scan}). Every 10 ticks
 * (some things change without a block change: belts reverse) and in the frame after a block change, so both sides
 * change together.
 */
public final class IPCreatePortalEndScan {
    private static final int SCAN_TICKS = 10;
    /** Portal blocks walked per portal at most: a 64x64 portal, a block wider each way and two deep on a ship. */
    private static final int MAX_CELLS = 66 * 66 * 2;

    private static int tick;
    /** A block that matters changed since the last walk; {@link #changedImportant}: one the player changed. */
    private static boolean changed;
    private static boolean changedImportant;

    private IPCreatePortalEndScan() {
    }

    /**
     * One side of a portal, as its portal blocks see it.
     *
     * @param frame the ship holding the portal blocks, null in the world
     * @param front the way the portal blocks face, in {@code frame}'s coordinates
     * @param farWorld the world the portal leads to, null while it is not loaded
     * @param farShip the ship carrying the far side, null when it is fixed in the world
     * @param toFarWorld a direction of {@code frame} to the far world's
     */
    public record Side(ClientLevel world, Portal portal, IPCreatePortalShafts.@Nullable Frame frame, Direction front,
                       @Nullable ClientLevel farWorld, @Nullable UUID farShip, Quaternionf toFarWorld) {
    }

    /** What one kind of part gathers during a walk. */
    public interface Scan {
        /** Whether this kind looks at the portal blocks of {@code side} at all. */
        boolean wants(Side side);

        /** A portal block of {@code side}, facing the way the side does, whose centre is {@code worldCentre}. */
        void cell(Side side, BlockPos cell, Vec3 worldCentre);

        /** The walk is over. {@code important}: a block the player changed started it. */
        void finish(Collection<ClientLevel> worlds, boolean important);
    }

    /** Client block change in any world (main thread). Only block entities, tracks and portal blocks matter. */
    public static void onBlockChanged(final Level level, final BlockPos pos, final BlockState oldState, final BlockState newState, final int flags) {
        if (oldState.hasBlockEntity() || newState.hasBlockEntity()
            || oldState.getBlock() == PortalPlaceholderBlock.instance || newState.getBlock() == PortalPlaceholderBlock.instance
            || oldState.getBlock() instanceof TrackBlock || newState.getBlock() instanceof TrackBlock) {
            changed = true;
            changedImportant |= (flags & Block.UPDATE_IMMEDIATE) != 0;
        }
        IPCreatePortalBeltLook.onBlockChanged(level, pos, oldState, newState);
    }

    public static void onClientTick(final IPGlobal.PostClientTickEvent event) {
        if (++tick % SCAN_TICKS == 0) {
            search(false);
        }
    }

    /** Before each frame: the walk for a block change, so both halves are meshed in the frame the block changes. */
    public static void onPreGameRender(final IPGlobal.PreGameRenderEvent event) {
        if (changed) {
            search(changedImportant);
        }
    }

    private static void search(final boolean important) {
        // IP's world list is empty from a vanilla dimension change (respawn packet) until IP sets it up again
        if (!ClientWorldLoader.getIsInitialized()) {
            return;
        }
        changed = false;
        changedImportant = false;
        final Map<ResourceKey<Level>, ClientLevel> worlds = new HashMap<>();
        for (final ClientLevel world : ClientWorldLoader.getClientWorlds()) {
            worlds.put(world.dimension(), world);
        }
        final List<Scan> scans = List.of(new IPCreatePortalShaftHalves.ShaftScan(), new IPCreatePortalPipeStubs.PipeScan(),
            new IPCreatePortalTrackStubs.TrackScan(), new IPCreatePortalBeltLook.BeltScan());
        for (final ClientLevel world : worlds.values()) {
            for (final Entity entity : world.entitiesForRendering()) {
                if (entity instanceof final Portal portal) {
                    walk(world, portal, worlds, scans);
                }
            }
        }
        for (final Scan scan : scans) {
            scan.finish(worlds.values(), important);
        }
    }

    private static void walk(final ClientLevel world, final Portal portal, final Map<ResourceKey<Level>, ClientLevel> worlds, final List<Scan> scans) {
        if (portal.isRemoved() || !portal.isTeleportable()
                || !IPCreatePortalEnds.blockForBlock(portal, IPShipPortals.sideScale(portal, true))) {
            return;
        }
        final UUID ownShip = IPShipPortals.ownSideCarrier(portal);
        // the portal's blocks: in the world, or (a side of a ship-mounted portal on a ship) in that ship's plot
        if (ownShip == null) {
            walkFrame(world, portal, null, portal.getBoundingBox().deflate(PLANE_EPSILON), worlds, scans);
        } else if (SABLE) {
            for (final IPCreatePortalShafts.Frame ship : IPCreateShaftShips.shipsTouching(world, portal.getBoundingBox().inflate(1))) {
                if (ship.ship().equals(ownShip)) {
                    walkFrame(world, portal, ship, plotBounds(portal, ship), worlds, scans);
                }
            }
        }
    }

    /**
     * The portal's area in {@code ship}'s plot. Taken from its corners: the bounds of its world box would grow with the
     * ship's tilt, past {@link #MAX_CELLS} for a portal 14 blocks wide at 45 degrees.
     */
    private static AABB plotBounds(final Portal portal, final IPCreatePortalShafts.Frame ship) {
        final Vec3 w = portal.getAxisW().scale(portal.getWidth() / 2);
        final Vec3 h = portal.getAxisH().scale(portal.getHeight() / 2);
        final Vec3 origin = portal.getOriginPos();
        AABB bounds = null;
        for (int i = 0; i < 4; i++) {
            final Vec3 corner = ship.toLocal(origin.add((i & 1) == 0 ? w : w.reverse()).add((i & 2) == 0 ? h : h.reverse()));
            final AABB point = new AABB(corner, corner);
            bounds = bounds == null ? point : bounds.minmax(point);
        }
        return bounds.inflate(0.5);
    }

    /**
     * Where a ship's plot point {@code local} is drawn: by the ship's render pose, which also places the ship's portals on
     * the client (their animation follows it every frame). By its logical pose (the frame's {@code toWorld}) the portal
     * blocks of a moving ship lay 0.07-0.2 blocks off the plane and were skipped now and then, and the shaft piece in
     * them vanished. By the render pose they lie 0.0000 off the plane every time.
     */
    private static Vec3 drawnAt(final IPCreatePortalShafts.Frame frame, final Vec3 local) {
        if (frame instanceof final IPCreateShaftShips.ShipFrame shipFrame
                && shipFrame.subLevel() instanceof final dev.ryanhcode.sable.sublevel.ClientSubLevel client) {
            return client.renderPose().transformPosition(local);
        }
        return frame.toWorld(local);
    }

    private static void walkFrame(final ClientLevel world, final Portal portal, final IPCreatePortalShafts.@Nullable Frame frame, final AABB cells,
                                  final Map<ResourceKey<Level>, ClientLevel> worlds, final List<Scan> scans) {
        final boolean ship = IPShipPortals.isShipMounted(portal);
        final Vec3 normal = frame == null ? portal.getNormal() : frame.toLocalDirection(portal.getNormal()).normalize();
        final Direction front = Direction.getNearest(normal.x, normal.y, normal.z);
        if (Vec3.atLowerCornerOf(front.getNormal()).dot(normal) < (ship ? SHIP_COS : 1 - PLANE_EPSILON)) {
            return;
        }
        final Quaternionf toFarWorld = portal.getRotationD().toMcQuaternion();
        if (frame != null) {
            toFarWorld.mul(frame.rotation());
        }
        final Side side = new Side(world, portal, frame, front, worlds.get(portal.getDestDim()), IPShipPortals.farSideCarrier(portal), toFarWorld);
        final List<Scan> wanting = scans.stream().filter(scan -> scan.wants(side)).toList();
        final BlockPos min = BlockPos.containing(cells.minX, cells.minY, cells.minZ);
        final BlockPos max = BlockPos.containing(cells.maxX, cells.maxY, cells.maxZ);
        if (wanting.isEmpty() || (long) (max.getX() - min.getX() + 1) * (max.getY() - min.getY() + 1) * (max.getZ() - min.getZ() + 1) > MAX_CELLS) {
            return;
        }
        // in the frame's own blocks: a scaled ship's (Create: Pocket Sized) are larger in the world
        final double unit = frame == null ? 1.0 : frame.scale();
        final double planeSlack = (ship ? SHIP_GAP : PLANE_EPSILON) * unit;
        for (final BlockPos cell : BlockPos.betweenClosed(min, max)) {
            if (frame != null && !frame.holds(world, cell)) {
                continue;
            }
            final Vec3 centre = Vec3.atCenterOf(cell);
            final Vec3 worldCentre = frame == null ? centre : drawnAt(frame, centre);
            final double distance = portal.getDistanceToPlane(worldCentre);
            if (Math.abs(distance) > planeSlack
                || !IPCreatePortalPipes.isPlaceholderFacing(world.getBlockState(cell), front)) {
                continue;
            }
            for (final Scan scan : wanting) {
                scan.cell(side, cell, worldCentre);
            }
        }
    }
}
