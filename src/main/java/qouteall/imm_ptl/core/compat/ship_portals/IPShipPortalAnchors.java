/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ServerPortalTickEvent;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.imm_ptl.core.portal.PortalManipulation;
import qouteall.imm_ptl.core.portal.animation.DeltaUnilateralPortalState;
import qouteall.imm_ptl.core.portal.animation.UnilateralPortalState;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Server side of ship-mounted portals (docs/plans/ship-mounted-portals.md, phase 1): anchoring a portal to its carrier
 * ship, capture on assembly, restore on disassembly, and shattering.
 * <p>
 * A ship-mounted portal is an ordinary IP breakable portal cluster (two faces on each side) whose ship-side holder has
 * an {@link IPShipPortalDriver}. Its frame and portal blocks are in the carrier's plot, so the holders' block shape is
 * in plot coordinates while the entities are in the world, at the carrier's pose. The fixed side is untouched.
 */
public final class IPShipPortalAnchors {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** A carrier absent this long while its portal ticks is gone (Sable loads a ship with the chunks around it). */
    private static final int MISSING_CARRIER_TICKS = 600;
    private static final int INTEGRITY_INTERVAL = 10;

    /** Loaded holders (ship-side portals carrying the driver), by portal UUID. */
    private static final Map<UUID, BreakablePortalEntity> HOLDERS = new HashMap<>();
    /** Holder UUID -> consecutive ticks its carrier was missing. */
    private static final Map<UUID, Integer> MISSING = new HashMap<>();
    /** Carriers Sable removed for good this server session. */
    private static final Set<UUID> REMOVED_CARRIERS = new HashSet<>();
    /**
     * Carriers Sable removed from a level since the last tick. A ship handed off through a portal is removed from the
     * level it leaves too ({@code REMOVED}) and loaded in the other: only one no level has by then is gone.
     */
    private static final Set<UUID> MAYBE_REMOVED = new HashSet<>();
    /** Holders to shatter at the end of the tick (never from inside their own tick), with the reason. */
    private static final Map<BreakablePortalEntity, String> TO_SHATTER = new LinkedHashMap<>();

    private IPShipPortalAnchors() {
    }

    public static void init() {
        IPShipPortalDriver.init();
        NeoForge.EVENT_BUS.addListener(ServerPortalTickEvent.class, event -> onHolderTick(event.portal));
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, event -> {
            if (!event.getLevel().isClientSide() && event.getEntity() instanceof final BreakablePortalEntity portal
                && driverOf(portal) != null) {
                HOLDERS.put(portal.getUUID(), portal);
            }
        });
        NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent.class, event -> {
            if (!event.getLevel().isClientSide() && event.getEntity() instanceof BreakablePortalEntity) {
                HOLDERS.remove(event.getEntity().getUUID());
            }
        });
        SableEventPlatform.INSTANCE.onSubLevelContainerReady((level, container) -> {
            if (level instanceof ServerLevel) {
                container.addObserver(new SubLevelObserver() {
                    @Override
                    public void onSubLevelRemoved(final SubLevel subLevel, final SubLevelRemovalReason reason) {
                        if (reason == SubLevelRemovalReason.REMOVED) {
                            onCarrierRemoved(subLevel.getUniqueId());
                        }
                    }
                });
            }
        });
    }

    public static void onServerStarted() {
        HOLDERS.clear();
        MISSING.clear();
        REMOVED_CARRIERS.clear();
        MAYBE_REMOVED.clear();
        TO_SHATTER.clear();
    }

    /** End of a server tick: shatters what was scheduled. */
    public static void tick(final MinecraftServer server) {
        if (!MAYBE_REMOVED.isEmpty()) {
            for (final UUID carrierId : new ArrayList<>(MAYBE_REMOVED)) {
                if (!loadedAnywhere(server, carrierId)) {
                    REMOVED_CARRIERS.add(carrierId);
                    for (final BreakablePortalEntity holder : new ArrayList<>(HOLDERS.values())) {
                        final IPShipPortalDriver driver = driverOf(holder);
                        if (driver != null && driver.carrier.equals(carrierId)) {
                            scheduleShatter(holder, "its ship was removed");
                        }
                    }
                }
            }
            MAYBE_REMOVED.clear();
        }
        if (TO_SHATTER.isEmpty()) {
            return;
        }
        final List<Map.Entry<BreakablePortalEntity, String>> due = new ArrayList<>(TO_SHATTER.entrySet());
        TO_SHATTER.clear();
        for (final Map.Entry<BreakablePortalEntity, String> entry : due) {
            try {
                shatterNow(entry.getKey(), entry.getValue());
            } catch (final RuntimeException e) {
                LOGGER.warn("[IPShipPortal] shattering {} failed", entry.getKey(), e);
            }
        }
    }

    // ---- queries ----

    @Nullable
    public static IPShipPortalDriver driverOf(final Portal portal) {
        return IPShipPortals.mountOf(portal) instanceof final IPShipPortalDriver driver ? driver : null;
    }

    public static boolean isShipMounted(final Portal portal) {
        return IPShipPortals.isShipMounted(portal);
    }

    /** True for the ship-side portals mounted on {@code ship} (in its level). */
    public static boolean isCarriedBy(final Portal portal, final SubLevel ship) {
        final IPShipMount mount = IPShipPortals.clusterMountOf(portal);
        return mount != null && mount.carrier().equals(ship.getUniqueId()) && portal.level() == ship.getLevel();
    }

    /**
     * The carrier of a ship-mounted portal, as a straddle session needs it: {@code carrierIsSource} for the ship-side
     * portals (they move; a ship goes from the carrier's world to the fixed side), false for the fixed side's portals
     * (still, but their destination moves with the carrier).
     */
    public record Mount(ServerSubLevel carrier, boolean carrierIsSource) {
    }

    /** @return the mount of {@code portal}'s cluster with its carrier loaded on this server, or null */
    @Nullable
    public static Mount mountFor(final Portal portal) {
        final Portal holder = portal.getAnimationHolder();
        final IPShipPortalDriver driver = holder == null ? null : driverOf(holder);
        if (driver == null || !(holder.level() instanceof final ServerLevel carrierLevel)) {
            return null;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(carrierLevel);
        if (container == null || !(container.getSubLevel(driver.carrier) instanceof final ServerSubLevel carrier) || carrier.isRemoved()) {
            return null;
        }
        return new Mount(carrier, portal.level() == carrierLevel);
    }

    // ---- anchoring ----

    /**
     * Makes {@code holder} (a ship-side portal of a bound cluster) follow {@code carrierId}. {@code plotState} is its state
     * in the carrier's plot; {@code currentDelta} is the delta that gives its current world state, kept until the
     * driver first sees the carrier.
     */
    public static void attach(final BreakablePortalEntity holder, final UUID carrierId, final UnilateralPortalState plotState,
                              final DeltaUnilateralPortalState currentDelta) {
        final IPShipPortalDriver driver = new IPShipPortalDriver(holder.level().dimension(), carrierId, plotState.position(),
                currentDelta.offset() == null ? Vec3.ZERO : currentDelta.offset(),
                currentDelta.rotation() == null ? DQuaternion.identity : currentDelta.rotation());
        holder.animation.thisSideAnimations.removeIf(d -> d instanceof IPShipPortalDriver);
        holder.animation.thisSideAnimations.add(driver);
        holder.animation.thisSideReferenceState = plotState;
        holder.animation.otherSideReferenceState = null;
        PortalExtension.forClusterPortals(holder, p -> p.animation.defaultAnimation.durationTicks = 0);
        PortalExtension.forClusterPortals(holder, Portal::reloadAndSyncToClientNextTick);
        HOLDERS.put(holder.getUUID(), holder);
        MISSING.remove(holder.getUUID());
        LOGGER.info("[IPShipPortal] {} mounted on ship {} (plot {})", holder, carrierId, plotState.position());
    }

    /** Ends the anchoring: the cluster becomes a static pair at {@code worldState} with its ship-side shape {@code worldShape}. */
    private static void detach(final BreakablePortalEntity holder, final UnilateralPortalState worldState, final BlockPortalShape worldShape) {
        holder.animation.thisSideAnimations.removeIf(d -> d instanceof IPShipPortalDriver);
        holder.animation.thisSideReferenceState = null;
        holder.animation.otherSideReferenceState = null;
        holder.setThisSideState(worldState);
        setShipSideShape(holder, worldShape);
        holder.reloadPortal();
        HOLDERS.remove(holder.getUUID());
        MISSING.remove(holder.getUUID());
        LOGGER.info("[IPShipPortal] {} is no longer on a ship (disassembled)", holder);
    }

    private static void setShipSideShape(final BreakablePortalEntity holder, final BlockPortalShape shape) {
        holder.blockPortalShape = shape;
        if (PortalExtension.get(holder).flippedPortal instanceof final BreakablePortalEntity flipped) {
            flipped.blockPortalShape = shape;
        }
    }

    // ---- shattering ----

    /** Schedules shattering every portal mounted on {@code carrierId}, now if called outside portal ticks. */
    public static void shatterCarriedPortals(final UUID carrierId, final String reason) {
        for (final BreakablePortalEntity holder : new ArrayList<>(HOLDERS.values())) {
            final IPShipPortalDriver driver = driverOf(holder);
            if (driver != null && driver.carrier.equals(carrierId)) {
                shatterNow(holder, reason);
            }
        }
    }

    /** True when portals are mounted on {@code carrierId} (loaded ones). */
    public static boolean hasCarriedPortals(final UUID carrierId) {
        for (final BreakablePortalEntity holder : HOLDERS.values()) {
            final IPShipPortalDriver driver = driverOf(holder);
            if (driver != null && driver.carrier.equals(carrierId)) {
                return true;
            }
        }
        return false;
    }

    private static void scheduleShatter(final BreakablePortalEntity holder, final String reason) {
        TO_SHATTER.putIfAbsent(holder, reason);
    }

    /** Breaks all four portals of the cluster at once, with sound and particles at both sides. */
    public static void shatterNow(final BreakablePortalEntity portal, final String reason) {
        if (portal.isRemoved()) {
            return;
        }
        LOGGER.info("[IPShipPortal] shattering {}: {}", portal, reason);
        final List<BreakablePortalEntity> members = new ArrayList<>();
        PortalExtension.forClusterPortals(portal, p -> {
            if (p instanceof final BreakablePortalEntity b && !members.contains(b)) {
                members.add(b);
            }
        });
        final Set<String> effectsAt = new HashSet<>();
        for (final BreakablePortalEntity member : members) {
            final String place = member.level().dimension().location() + " " + BlockPos.containing(member.getOriginPos());
            if (effectsAt.add(place) && member.level() instanceof final ServerLevel level) {
                shatterEffects(level, member);
            }
        }
        for (final BreakablePortalEntity member : members) {
            HOLDERS.remove(member.getUUID());
            MISSING.remove(member.getUUID());
            member.unbreakable = false;
            try {
                member.breakPortalNow();
            } catch (final RuntimeException e) {
                // a removed ship's plot is gone with its portal blocks: Sable refuses block changes there
                LOGGER.debug("[IPShipPortal] {} could not clear its portal blocks ({}); removing it", member, e.toString());
                member.remove(Entity.RemovalReason.KILLED);
            }
        }
    }

    private static void shatterEffects(final ServerLevel level, final Portal portal) {
        final Vec3 o = portal.getOriginPos();
        level.playSound(null, o.x, o.y, o.z, SoundEvents.GLASS_BREAK, SoundSource.BLOCKS, 1.5F, 0.6F);
        final AABB box = portal.getBoundingBox();
        level.sendParticles(ParticleTypes.REVERSE_PORTAL, o.x, o.y, o.z, 80,
                box.getXsize() * 0.35, box.getYsize() * 0.35, box.getZsize() * 0.35, 0.05);
    }

    // ---- per-tick checks ----

    private static void onHolderTick(final Portal portal) {
        if (!(portal instanceof final BreakablePortalEntity holder) || portal.level().isClientSide()) {
            return;
        }
        final IPShipPortalDriver driver = driverOf(holder);
        if (driver == null) {
            return;
        }
        HOLDERS.putIfAbsent(holder.getUUID(), holder);
        if (!IPShipPortals.isEnabled()) {
            scheduleShatter(holder, "ship-mounted portals are switched off");
            return;
        }
        if (REMOVED_CARRIERS.contains(driver.carrier)) {
            scheduleShatter(holder, "its ship was removed");
            return;
        }
        if (driver.carrierSeen()) {
            MISSING.remove(holder.getUUID());
            if (holder.tickCount % INTEGRITY_INTERVAL == 0 && !holder.isIntactNow()) {
                scheduleShatter(holder, "its frame on the ship is broken");
            }
        } else {
            final int missing = MISSING.merge(holder.getUUID(), 1, Integer::sum);
            if (missing == MISSING_CARRIER_TICKS) {
                scheduleShatter(holder, "its ship " + driver.carrier + " has not been loaded for " + MISSING_CARRIER_TICKS + " ticks");
            }
        }
    }

    private static void onCarrierRemoved(final UUID carrierId) {
        MAYBE_REMOVED.add(carrierId);
    }

    private static boolean loadedAnywhere(final MinecraftServer server, final UUID shipId) {
        for (final ServerLevel level : server.getAllLevels()) {
            final SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && container.getSubLevel(shipId) != null) {
                return true;
            }
        }
        return false;
    }

    // ---- assembly and disassembly ----

    /**
     * Sable moves {@code blocks} by {@code transform} (assembly: world to a new plot; disassembly: plot to world; nested
     * assembly or splits: plot to plot). Called at the start of {@code SubLevelAssemblyHelper.moveBlocks}, before anything
     * moved. A lit world portal moved whole into a ship is mounted on it, unless its other side is already on a ship; a
     * mounted portal moved whole goes with its blocks (back to a static pair in the world, or onto the new ship). A
     * portal moved only partly breaks.
     */
    public static void onBlocksMoving(final ServerLevel level, final SubLevelAssemblyHelper.AssemblyTransform transform,
                                      final Iterable<BlockPos> blocks, final List<BreakablePortalEntity> worldPortals) {
        final List<BreakablePortalEntity> holders = new ArrayList<>();
        for (final BreakablePortalEntity holder : HOLDERS.values()) {
            if (holder.level() == level && !holder.isRemoved() && holder.blockPortalShape != null) {
                holders.add(holder);
            }
        }
        if (worldPortals.isEmpty() && holders.isEmpty()) {
            return;
        }

        // the iterable may reuse a mutable position (BlockPos.betweenClosed), hence the copies
        final Set<BlockPos> moved = new HashSet<>();
        for (final BlockPos block : blocks) {
            moved.add(block.immutable());
        }

        for (final BreakablePortalEntity holder : holders) {
            final Coverage coverage = coverage(holder.blockPortalShape, moved);
            if (coverage == Coverage.NONE) {
                continue;
            }
            if (coverage == Coverage.PARTIAL) {
                shatterNow(holder, "its frame was split by a ship being assembled or disassembled");
                continue;
            }
            final UnilateralPortalState plotState = holder.animation.thisSideReferenceState;
            if (plotState == null) {
                continue;
            }
            final UnilateralPortalState newState = transformState(plotState, transform);
            final BlockPortalShape newShape = transformShape(holder.blockPortalShape, transform);
            carryPortalBlocks(level, holder.blockPortalShape, newShape, moved);
            if (IPSableCompatHelper.isPlotSpacePosition(newState.position().x, newState.position().z)) {
                final SubLevel newCarrier = Sable.HELPER.getContaining(level, newShape.anchor);
                if (newCarrier == null) {
                    shatterNow(holder, "its frame moved into a plot without a ship");
                    continue;
                }
                setShipSideShape(holder, newShape);
                attach(holder, newCarrier.getUniqueId(), newState,
                        DeltaUnilateralPortalState.fromDiff(newState, holder.getThisSideState()));
            } else {
                detach(holder, newState, newShape);
            }
        }

        final Set<UUID> handled = new HashSet<>();
        for (final BreakablePortalEntity portal : worldPortals) {
            if (portal.isRemoved() || handled.contains(portal.getUUID())) {
                continue;
            }
            final Coverage coverage = coverage(portal.blockPortalShape, moved);
            if (coverage == Coverage.NONE) {
                continue;
            }
            handled.add(portal.getUUID());
            final Portal flipped = PortalExtension.get(portal).flippedPortal;
            if (flipped != null) {
                handled.add(flipped.getUUID());
            }
            if (!IPShipPortals.isEnabled()) {
                LOGGER.info("[IPShipPortal] breaking {} because its blocks are being assembled into a physics object", portal);
                portal.breakPortalNow();
                continue;
            }
            if (coverage == Coverage.PARTIAL) {
                shatterNow(portal, "only part of its frame is being assembled into a ship");
                continue;
            }
            if (isShipMounted(portal)) {
                shatterNow(portal, "its other side is already on a ship");
                continue;
            }
            final UnilateralPortalState plotState = transformState(portal.getThisSideState(), transform);
            final BlockPortalShape plotShape = transformShape(portal.blockPortalShape, transform);
            final SubLevel carrier = Sable.HELPER.getContaining(level, plotShape.anchor);
            if (carrier == null) {
                shatterNow(portal, "its frame is being assembled into a plot without a ship");
                continue;
            }
            carryPortalBlocks(level, portal.blockPortalShape, plotShape, moved);
            setShipSideShape(portal, plotShape);
            attach(portal, carrier.getUniqueId(), plotState,
                    DeltaUnilateralPortalState.fromDiff(plotState, portal.getThisSideState()));
        }
    }

    private enum Coverage { NONE, PARTIAL, WHOLE }

    /**
     * How much of a portal moves, judged by its frame: Create Aeronautics' assemblers (Create's contraption search) skip
     * blocks without collision, so they move a whole frame without its portal blocks ({@link #carryPortalBlocks}).
     */
    private static Coverage coverage(@Nullable final BlockPortalShape shape, final Set<BlockPos> moved) {
        if (shape == null) {
            return Coverage.NONE;
        }
        int in = 0;
        int total = 0;
        for (final BlockPos pos : shape.frameAreaWithoutCorner) {
            total++;
            if (moved.contains(pos)) {
                in++;
            }
        }
        if (in == total) {
            return Coverage.WHOLE;
        }
        if (in > 0) {
            return Coverage.PARTIAL;
        }
        for (final BlockPos pos : shape.area) {
            if (moved.contains(pos)) {
                return Coverage.PARTIAL;
            }
        }
        return Coverage.NONE;
    }

    /** Portal blocks to put back after the current block move: the level and the shape they go in. */
    private static final List<Map.Entry<ServerLevel, BlockPortalShape>> TO_FILL = new ArrayList<>();

    /**
     * A portal moves with its frame; its portal blocks that are not among the moved blocks (left out by the assembler)
     * are cleared where they are and put back at the destination once the move is done ({@link #afterBlocksMoved}).
     */
    private static void carryPortalBlocks(final ServerLevel level, final BlockPortalShape from, final BlockPortalShape to,
                                          final Set<BlockPos> moved) {
        boolean leftBehind = false;
        for (final BlockPos pos : from.area) {
            if (!moved.contains(pos)) {
                leftBehind = true;
                if (level.getBlockState(pos).getBlock() == PortalPlaceholderBlock.instance) {
                    level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
                }
            }
        }
        if (leftBehind) {
            TO_FILL.add(Map.entry(level, to));
        }
    }

    /** Sable's block move is done: puts back the portal blocks it did not carry. */
    public static void afterBlocksMoved() {
        if (TO_FILL.isEmpty()) {
            return;
        }
        final List<Map.Entry<ServerLevel, BlockPortalShape>> due = new ArrayList<>(TO_FILL);
        TO_FILL.clear();
        for (final Map.Entry<ServerLevel, BlockPortalShape> entry : due) {
            for (final BlockPos pos : entry.getValue().area) {
                if (entry.getKey().getBlockState(pos).isAir()) {
                    entry.getKey().setBlockAndUpdate(pos, PortalPlaceholderBlock.instance.defaultBlockState()
                            .setValue(PortalPlaceholderBlock.AXIS, entry.getValue().axis));
                }
            }
        }
    }

    private static UnilateralPortalState transformState(final UnilateralPortalState state,
                                                        final SubLevelAssemblyHelper.AssemblyTransform transform) {
        final Vec3 o = state.position();
        final Vec3 newOrigin = transform.apply(o);
        final Vec3 axisW = transform.apply(o.add(state.getAxisW())).subtract(newOrigin).normalize();
        final Vec3 axisH = transform.apply(o.add(state.getAxisH())).subtract(newOrigin).normalize();
        return new UnilateralPortalState(state.dimension(), newOrigin,
                PortalManipulation.getPortalOrientationQuaternion(axisW, axisH),
                state.width(), state.height(), state.thickness());
    }

    private static BlockPortalShape transformShape(final BlockPortalShape shape,
                                                   final SubLevelAssemblyHelper.AssemblyTransform transform) {
        final Set<BlockPos> area = shape.area.stream().map(transform::apply).collect(Collectors.toSet());
        Direction.Axis axis = shape.axis;
        if (axis != Direction.Axis.Y) {
            final Vec3 along = Vec3.atLowerCornerOf(Direction.get(Direction.AxisDirection.POSITIVE, axis).getNormal());
            final Vec3 turned = transform.apply(along).subtract(transform.apply(Vec3.ZERO));
            axis = Math.abs(turned.x) > Math.abs(turned.z) ? Direction.Axis.X : Direction.Axis.Z;
        }
        return new BlockPortalShape(area, axis);
    }

    /**
     * For the status command and headless tests: one line per loaded ship-side holder, with its origin and its distance
     * from where its frame is on the carrier now.
     */
    public static List<String> describe() {
        final List<String> lines = new ArrayList<>();
        for (final BreakablePortalEntity holder : HOLDERS.values()) {
            final IPShipPortalDriver driver = driverOf(holder);
            if (driver == null) {
                continue;
            }
            final SubLevelContainer container = SubLevelContainer.getContainer(holder.level());
            final SubLevel ship = container == null ? null : container.getSubLevel(driver.carrier);
            final Vec3 origin = holder.getOriginPos();
            String where = "ship not loaded";
            if (ship != null) {
                final org.joml.Vector3d expected = ship.logicalPose().transformPosition(
                        new org.joml.Vector3d(driver.plotOrigin.x, driver.plotOrigin.y, driver.plotOrigin.z));
                where = String.format("%.6f from its frame", origin.distanceTo(new Vec3(expected.x, expected.y, expected.z)));
            }
            final Portal reverse = PortalExtension.get(holder).reversePortal;
            final String back = reverse == null ? "far side not loaded"
                    : String.format("far side leads back %.6f from it", reverse.getDestPos().distanceTo(origin));
            lines.add(String.format("%s on ship %s at %.4f %.4f %.4f (%s), leads to %s %.4f %.4f %.4f (%s)",
                    holder.getUUID(), driver.carrier, origin.x, origin.y, origin.z, where,
                    holder.getDestDim().location(), holder.getDestPos().x, holder.getDestPos().y, holder.getDestPos().z, back));
        }
        return lines;
    }
}
