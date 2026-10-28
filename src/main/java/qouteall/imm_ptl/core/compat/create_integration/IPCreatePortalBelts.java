/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltHelper;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.belt.behaviour.DirectBeltInputBehaviour;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import com.simibubi.create.foundation.blockEntity.behaviour.BlockEntityBehaviour;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4d;
import org.joml.Quaterniond;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.IPPortalLookup;
import qouteall.imm_ptl.core.compat.mixin_create.IPBeltInventoryAccessor;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipMount;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Create's belts through IP portals. A belt whose end opens onto a portal (the end's face within a block of the portal
 * plane, facing it, inside the opening) hands its items to whatever takes belt input at the same spot past the portal (a
 * belt running on, a belt's side, a depot, a chute), as Create hands items from one belt to the next. Items run on to
 * the plane before they are handed over, and a belt running on from the far portal takes them at its own plane, so an
 * item crosses at one spot without a jump. Nothing past the portal that takes belt input: Create ejects the item into
 * the portal as before (it flies through as an item entity). The far side not loaded: the belt waits, as at a blocked
 * end.
 * <p>
 * Both sides look the far side up, so the client's items also run on to the plane rather than stopping at the belt's
 * end until the server hands them over. A belt on a ship goes through a portal mounted on that same ship, to the
 * fixed far side, and back (2.6.12.8), as shafts and pipes do. Portals at scale 1 and on the block grid. Mixin: {@code MixinBeltInventory_PortalBelts}. Plan and next steps:
 * docs/plans/belts.md.
 */
public final class IPCreatePortalBelts {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String TAG = "[IPPortalBelts] ";
    /** {@code -Dip_aeronautics.debug.portalBelts=true}: logs every item handed through a portal and where it lands. */
    private static final boolean DEBUG = Boolean.getBoolean("ip_aeronautics.debug.portalBelts");
    private static final double PLANE_EPSILON = 1.0e-3;
    /** Slack for a belt at a ship-mounted portal, whose pose is worked out in doubles each tick, as for pipes. */
    private static final double SHIP_COS = 1 - 1.0e-3;
    private static final double SHIP_GAP = 0.05;
    private static final boolean SABLE = net.neoforged.fml.ModList.get().isLoaded("sable");

    /** How far an item drawn on a belt reaches from its position along the belt (block items at half size, turned). */
    public static final float ITEM_REACH = 0.75f;
    /** Ticks a belt's {@link Ends} are kept before they are looked up again. */
    private static final int ENDS_REFRESH_TICKS = 10;
    /** Client: belts with items within reach of a portal at either end (for drawing them cut at the plane). */
    private static final Map<BeltBlockEntity, Ends> CLIENT_ENDS = new WeakHashMap<>();

    /** The exit an item is being handed through on this thread, while Create's insertion runs. */
    private static final ThreadLocal<Exit> HANDING = new ThreadLocal<>();

    /** Create's {@code BeltInventory.Ending} values (a private enum) that we return. */
    public static final Object ENDING_BLOCKED = ending("BLOCKED");

    private IPCreatePortalBelts() {
    }

    /**
     * Where a belt's end leads through a portal: the far block at the same spot past it ({@code farPos}), the way items
     * move on there ({@code farFacing}, away from the far plane), and how far the near end's face and the far block's
     * face lie from their planes (half a block at a nether portal, none at a frameless portal on a block face).
     * {@code into}: the direction from the near end into the portal; {@code nearOnPlane}/{@code farOnPlane}: where the
     * middle of the end's face meets the plane, on each side. Each side in its own coordinates: a ship's plot
     * ({@code nearFrame}/{@code farFrame}, as for shafts, {@link IPCreatePortalShafts.Frame}) or the world (null);
     * {@code nearToFar} takes near coordinates to far ones through the portal (for a portal mounted on the ship, a fixed
     * map however the ship moves).
     */
    public record Exit(
        Portal portal, Level farLevel, BlockPos farPos, Direction farFacing, float nearGap, float farGap, Vec3 into,
        Vec3 nearOnPlane, Vec3 farOnPlane, IPCreatePortalShafts.@Nullable Frame nearFrame,
        IPCreatePortalShafts.@Nullable Frame farFrame, Matrix4d nearToFar
    ) {
    }

    /** A belt's portal exits at its start (items moving against its facing) and at its end, as last looked up. */
    public record Ends(@Nullable Exit start, @Nullable Exit end, long checked) {
    }

    /** The portal exit at the end a belt's items move towards ({@code positive}: along its facing), or null. */
    public static @Nullable Exit exit(final BeltBlockEntity controller, final boolean positive) {
        final Level level = controller.getLevel();
        final BlockState state = controller.getBlockState();
        if (level == null || controller.beltLength < 1 || !state.hasProperty(BeltBlock.SLOPE)) {
            return null;
        }
        final BeltSlope slope = state.getValue(BeltBlock.SLOPE);
        if (slope == BeltSlope.VERTICAL || slope == BeltSlope.SIDEWAYS) {
            return null; // carries no items
        }
        final Direction facing = state.getValue(BlockStateProperties.HORIZONTAL_FACING);
        final BlockPos end = BeltHelper.getPositionForOffset(controller, positive ? controller.beltLength - 1 : 0);
        return through(level, end, positive ? facing : facing.getOpposite());
    }

    /**
     * The exit through a portal of the face {@code side} of {@code pos}, or null. Worked out in world space. A belt on
     * a ship goes only through a portal mounted on that ship, to the fixed far side; a world belt at the fixed side of
     * a ship-mounted portal to the ship, as for shafts and pipes.
     */
    private static @Nullable Exit through(final Level level, final BlockPos pos, final Direction side) {
        final IPCreatePortalShafts.Frame frame = SABLE ? IPCreateShaftShips.frameAt(level, pos) : null;
        final Vec3 nLocal = Vec3.atLowerCornerOf(side.getNormal());
        final Vec3 faceLocal = Vec3.atCenterOf(pos).add(nLocal.scale(0.5));
        final Vec3 n = frame == null ? nLocal : frame.toWorldDirection(nLocal).normalize();
        final Vec3 face = frame == null ? faceLocal : frame.toWorld(faceLocal);
        final AABB search = new AABB(face, face.add(n)).inflate(0.25);
        for (final Portal portal : IPPortalLookup.touching(level, search, p -> p.isTeleportable() && !p.isRemoved())) {
            final Exit exit = via(portal, frame, nLocal, face, n);
            if (exit != null) {
                return exit;
            }
        }
        return null;
    }

    private static @Nullable Exit via(
        final Portal portal, final IPCreatePortalShafts.@Nullable Frame frame, final Vec3 nLocal, final Vec3 face, final Vec3 n
    ) {
        if (Math.abs(portal.getScale() - 1) > 1.0e-6) {
            return null;
        }
        final IPShipMount mount = IPShipPortals.clusterMountOf(portal);
        if (frame != null && (mount == null || !frame.ship().equals(mount.carrier()))) {
            return null;
        }
        final double cos = mount != null ? SHIP_COS : 1 - PLANE_EPSILON;
        final double slack = mount != null ? SHIP_GAP : PLANE_EPSILON;
        final Vec3 normal = portal.getNormal();
        if (normal.dot(n) > -cos) {
            return null;
        }
        final double gap = portal.getDistanceToPlane(face);
        if (gap < -slack || gap >= 1 - PLANE_EPSILON) {
            return null;
        }
        final Vec3 onPlane = face.subtract(normal.scale(gap));
        if (!portal.getPortalShape().isBoxInPortalProjection(portal.getThisSideState(), AABB.ofSize(onPlane, 0.1, 0.1, 0.1))) {
            return null;
        }
        final Level farLevel = portal.getDestWorld();
        if (farLevel == null) {
            return null;
        }
        final Vec3 farOnPlane = portal.transformPoint(onPlane);
        final Vec3 farDir = portal.transformLocalVecNonScale(n);
        final Near near = new Near(portal, frame, nLocal, frame == null ? onPlane : frame.toLocal(onPlane), (float) Math.max(gap, 0));
        if (mount == null || frame != null) {
            return farEnd(near, farLevel, farOnPlane, farDir, null, cos, slack);
        }
        if (!SABLE) {
            return null;
        }
        // a world belt at a ship-mounted portal: the far end is on the portal's ship
        final AABB around = new AABB(farOnPlane, farOnPlane.add(farDir.scale(1.5))).inflate(1);
        for (final IPCreatePortalShafts.Frame ship : IPCreateShaftShips.shipsTouching(farLevel, around)) {
            if (ship.ship().equals(mount.carrier())) {
                final Exit found = farEnd(near, farLevel, farOnPlane, farDir, ship, cos, slack);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The near side of an exit being worked out, in the near belt's coordinates. */
    private record Near(Portal portal, IPCreatePortalShafts.@Nullable Frame frame, Vec3 into, Vec3 onPlane, float gap) {
    }

    /** The block past the far plane in {@code farFrame} (null: the world) whose face towards the plane lies within a block of it. */
    private static @Nullable Exit farEnd(
        final Near near, final Level farLevel, final Vec3 farOnPlane, final Vec3 farDir, final IPCreatePortalShafts.@Nullable Frame farFrame,
        final double cos, final double slack
    ) {
        final Vec3 dir = farFrame == null ? farDir : farFrame.toLocalDirection(farDir).normalize();
        final Vec3 plane = farFrame == null ? farOnPlane : farFrame.toLocal(farOnPlane);
        final Direction farOut = Direction.getNearest(dir.x, dir.y, dir.z);
        final Vec3 farN = Vec3.atLowerCornerOf(farOut.getNormal());
        if (farN.dot(dir) < cos) {
            return null; // rotated off the block grid
        }
        for (final double reach : new double[]{0.5, 1.5}) {
            final BlockPos candidate = BlockPos.containing(plane.add(farN.scale(reach)));
            final double farGap = Vec3.atCenterOf(candidate).subtract(farN.scale(0.5)).subtract(plane).dot(farN);
            if (farGap >= -slack && farGap < 1 - PLANE_EPSILON && (farFrame == null || farFrame.holds(farLevel, candidate))) {
                final Matrix4d nearToFar = inverse(frameMatrix(farFrame)).mul(portalMatrix(near.portal())).mul(frameMatrix(near.frame()));
                return new Exit(near.portal(), farLevel, candidate, farOut, near.gap(), (float) Math.max(farGap, 0), near.into(),
                    near.onPlane(), plane, near.frame(), farFrame, nearToFar);
            }
        }
        return null;
    }

    /** A frame's coordinates to the world's (the identity for the world). */
    private static Matrix4d frameMatrix(final IPCreatePortalShafts.@Nullable Frame frame) {
        if (frame == null) {
            return new Matrix4d();
        }
        final Vec3 origin = frame.toWorld(Vec3.ZERO);
        return new Matrix4d().translation(origin.x, origin.y, origin.z).rotate(new Quaterniond(frame.rotation()));
    }

    /** A portal's map of points from its side to the other, as {@link Portal#transformPoint} (scale 1). */
    private static Matrix4d portalMatrix(final Portal portal) {
        final Vec3 origin = portal.getOriginPos();
        final Vec3 dest = portal.getDestPos();
        return new Matrix4d().translation(dest.x, dest.y, dest.z)
            .rotate(new Quaterniond(portal.getRotationD().toMcQuaternion()))
            .translate(-origin.x, -origin.y, -origin.z);
    }

    private static Matrix4d inverse(final Matrix4d matrix) {
        return matrix.invertAffine(new Matrix4d());
    }

    /** What takes belt input past the portal, if its chunk is loaded. */
    public static @Nullable DirectBeltInputBehaviour farInput(final Exit exit) {
        if (!exit.farLevel().isLoaded(exit.farPos())) {
            return null;
        }
        return BlockEntityBehaviour.get(exit.farLevel(), exit.farPos(), DirectBeltInputBehaviour.TYPE);
    }

    /** Whether {@code input} is the far side of {@code exit}. */
    public static boolean isFar(final @Nullable Exit exit, final DirectBeltInputBehaviour input) {
        return exit != null && input.getWorld() == exit.farLevel() && input.getPos().equals(exit.farPos());
    }

    /**
     * An item just handed to a far belt running on from the portal: Create put it at the belt's first face
     * ({@code BeltBlockEntity.tryInsertingFromSide}, the last item queued); move it back to the far plane, where it left
     * the near belt. Items handed onto a belt's side keep Create's place.
     */
    public static void placeAtPlane(final Exit exit, final DirectBeltInputBehaviour input) {
        if (!(input.blockEntity instanceof BeltBlockEntity far) || far.getMovementFacing() != exit.farFacing()) {
            if (DEBUG) {
                LOGGER.info(TAG + "handed to {} at {} {}", input.blockEntity.getType(), exit.farLevel().dimension().location(),
                    exit.farPos().toShortString());
            }
            return;
        }
        final BeltBlockEntity controller = far.getControllerBE();
        if (controller == null || controller.getInventory() == null) {
            return;
        }
        final List<TransportedItemStack> queued = ((IPBeltInventoryAccessor) controller.getInventory()).ip$toInsert();
        if (queued.isEmpty()) {
            return;
        }
        final TransportedItemStack stack = queued.get(queued.size() - 1);
        final float position = far.getDirectionAwareBeltMovementSpeed() > 0
            ? far.index - exit.farGap()
            : far.index + 1 + exit.farGap();
        stack.beltPosition = position;
        stack.prevBeltPosition = position;
        if (DEBUG) {
            LOGGER.info(TAG + "tick {}: handed {} to the belt at {} {}, segment {} of {}, at {} (plane {} from its face)",
                exit.farLevel().getGameTime(), stack.stack, exit.farLevel().dimension().location(), exit.farPos().toShortString(), far.index, far.beltLength, position,
                exit.farGap());
        }
    }

    /** Runs Create's insertion of an item handed through {@code exit} ({@link #handingThrough} answers inside it). */
    public static <T> T handing(final Exit exit, final Supplier<T> insertion) {
        final Exit before = HANDING.get();
        HANDING.set(exit);
        try {
            return insertion.get();
        } finally {
            HANDING.set(before);
        }
    }

    /**
     * Whether a far belt's {@code canInsertAtFromSide} check is for an item handed through a portal, entering end-on.
     * Create keeps an item entering end-on from the next belt out until the last one it let in has moved a block past
     * the belt's first face (1.26 blocks apart); from the plane that would be 1.5. Such items are 1 block apart, as on
     * one belt, so a full belt through a portal carries as much as without one ({@link #occupiedAtPlane}).
     */
    public static @Nullable Exit handingThrough(final Level level, final Direction side) {
        final Exit exit = HANDING.get();
        return exit != null && exit.farLevel() == level && exit.farFacing() == side ? exit : null;
    }

    /** An item already within a block of where an item handed through {@code exit} lands on the far belt. */
    public static boolean occupiedAtPlane(
        final Exit exit, final int segment, final boolean positive, final Iterable<TransportedItemStack> items,
        final Iterable<TransportedItemStack> queued
    ) {
        final float landing = positive ? segment - exit.farGap() : segment + 1 + exit.farGap();
        for (final Iterable<TransportedItemStack> list : List.of(items, queued)) {
            for (final TransportedItemStack stack : list) {
                if (Math.abs(stack.beltPosition - landing) < 1) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * Client, each tick of a belt's inventory: keeps the belt among those drawn cut at a portal while any of its items
     * is within reach of an end, looking its ends up again every {@link #ENDS_REFRESH_TICKS} ticks.
     */
    public static void noteClientItems(
        final BeltBlockEntity controller, final List<TransportedItemStack> items, final @Nullable TransportedItemStack lazy
    ) {
        final Level level = controller.getLevel();
        if (level == null) {
            return;
        }
        boolean near = lazy != null && nearAnEnd(controller, lazy);
        for (final TransportedItemStack stack : items) {
            near = near || nearAnEnd(controller, stack);
        }
        if (!near) {
            CLIENT_ENDS.remove(controller);
            return;
        }
        final long now = level.getGameTime();
        final Ends known = CLIENT_ENDS.get(controller);
        if (known == null || now - known.checked() >= ENDS_REFRESH_TICKS || now < known.checked()) {
            CLIENT_ENDS.put(controller, new Ends(exit(controller, false), exit(controller, true), now));
        }
    }

    private static boolean nearAnEnd(final BeltBlockEntity controller, final TransportedItemStack stack) {
        return stack.beltPosition < 1 + ITEM_REACH || stack.beltPosition > controller.beltLength - 1 - ITEM_REACH;
    }

    /** Client: a belt's ends while it has items within reach of them, else null. */
    public static @Nullable Ends clientEnds(final BeltBlockEntity controller) {
        final Ends ends = CLIENT_ENDS.get(controller);
        return ends == null || (ends.start() == null && ends.end() == null) ? null : ends;
    }

    /** Client: every belt with items near a portal at an end. */
    public static Map<BeltBlockEntity, Ends> clientBelts() {
        return CLIENT_ENDS;
    }

    /** Where the plane of {@code exit} lies along a belt, in Create's item positions ({@code atEnd}: the exit at its end). */
    public static float planePosition(final BeltBlockEntity controller, final Exit exit, final boolean atEnd) {
        return atEnd ? controller.beltLength + exit.nearGap() : -exit.nearGap();
    }

    private static Object ending(final String name) {
        try {
            final Class<?> type = Class.forName("com.simibubi.create.content.kinetics.belt.transport.BeltInventory$Ending");
            for (final Object value : type.getEnumConstants()) {
                if (((Enum<?>) value).name().equals(name)) {
                    return value;
                }
            }
        } catch (final ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
        throw new IllegalStateException("BeltInventory.Ending." + name);
    }
}
