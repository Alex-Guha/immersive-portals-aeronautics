/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import qouteall.imm_ptl.core.compat.sable_straddle.IPShipScale;
import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ServerPortalTickEvent;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.api.SubLevelAssemblyHelper;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.platform.SableEventPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.system.ticket.PhysicsChunkTicketManager;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.EntityLeaveLevelEvent;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.imm_ptl.core.portal.PortalManipulation;
import qouteall.imm_ptl.core.portal.animation.DeltaUnilateralPortalState;
import qouteall.imm_ptl.core.portal.animation.PortalAnimation;
import qouteall.imm_ptl.core.portal.animation.UnilateralPortalState;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;
import qouteall.q_misc_util.MiscHelper;
import qouteall.q_misc_util.my_util.DQuaternion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

/**
 * Server side of ship-mounted portals (docs/lessons/ship-mounted-portals.md): anchoring a portal to its carrier
 * ship, capture on assembly, restore on disassembly, and shattering.
 * <p>
 * A ship-mounted portal is an ordinary IP breakable portal cluster (two faces on each side) whose ship-side holder has
 * an {@link IPShipPortalDriver}. Its frame and portal blocks are in the carrier's plot, so the holders' block shape is
 * in plot coordinates while the entities are in the world, at the carrier's pose. The fixed side is untouched.
 * <p>
 * Custom portals (IP's portal wand, {@code /portal} commands; docs/lessons/ship-mounted-portals.md) are mounted the same way,
 * on any plain portal cluster, but have no frame: they belong to the blocks they touch ({@link #support}), are never
 * judged by their blocks, and are removed with the same effects wherever a nether portal shatters. Their other side
 * may be in the carrier's own dimension.
 * <p>
 * Both ends of a cluster may be on ships, one ship or two (docs/lessons/ship-mounted-portals.md): the holder then has a
 * second driver on the other side of its animation, for the far end's ship, and its other-side reference state is the
 * far end in that ship's plot. Each end is mounted, carried and detached on its own ({@link End}); a holder may keep
 * only its other-side driver when its own end leaves its ship.
 * <p>
 * Shattering reaches the portals of a cluster that are loaded, on a loaded ship or none. The rest finish when they can
 * ({@link IPShipPortalBrokenLinks}): nobody can see or use an end that is not loaded, so nothing loads it for this.
 */
public final class IPShipPortalAnchors {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** A carrier absent this long while its portal ticks is gone (Sable loads a ship with the chunks around it). */
    private static final int MISSING_CARRIER_TICKS = 600;
    /** The chunks around a ship's last position that must tick before it counts as missing (the ship may be longer). */
    private static final int MISSING_CHECK_CHUNKS = 2;
    private static final int INTEGRITY_INTERVAL = 10;

    /** Loaded holders (portals carrying a driver on either side of their animation), by portal UUID. */
    private static final Map<UUID, Portal> HOLDERS = new HashMap<>();
    /** Holders whose entity ticked this server tick. */
    private static final Set<UUID> TICKED = new HashSet<>();
    /** Holder UUID -> consecutive ticks one of its carriers was missing. */
    private static final Map<UUID, Integer> MISSING = new HashMap<>();
    /** Carriers Sable removed for good this server session. */
    private static final Set<UUID> REMOVED_CARRIERS = new HashSet<>();
    /**
     * Carriers Sable removed from a level since the last tick. A ship handed off through a portal is removed from the
     * level it leaves too ({@code REMOVED}) and loaded in the other: only one no level has by then is gone.
     */
    private static final Set<UUID> MAYBE_REMOVED = new HashSet<>();
    /** Mounts to shatter at the end of the tick (never from inside their own tick), with the reason. */
    private static final Map<Portal, String> TO_SHATTER = new LinkedHashMap<>();

    private IPShipPortalAnchors() {
    }

    public static void init() {
        IPShipPortalDriver.init();
        NeoForge.EVENT_BUS.addListener(ServerPortalTickEvent.class, event -> onHolderTick(event.portal));
        NeoForge.EVENT_BUS.addListener(EntityJoinLevelEvent.class, event -> {
            if (!event.getLevel().isClientSide() && event.getEntity() instanceof final Portal portal
                && isHolder(portal)) {
                HOLDERS.put(portal.getUUID(), portal);
            }
        });
        NeoForge.EVENT_BUS.addListener(EntityLeaveLevelEvent.class, event -> {
            if (!event.getLevel().isClientSide() && event.getEntity() instanceof Portal) {
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

    public static void clearState() {
        HOLDERS.clear();
        TICKED.clear();
        MISSING.clear();
        REMOVED_CARRIERS.clear();
        MAYBE_REMOVED.clear();
        TO_SHATTER.clear();
    }

    /** End of a server tick: moves far ends whose holder did not tick, shatters what was scheduled. */
    public static void tick(final MinecraftServer server) {
        finishBrokenLinks(server);
        tickUntickedHolders(server);
        if (!MAYBE_REMOVED.isEmpty()) {
            for (final UUID carrierId : new ArrayList<>(MAYBE_REMOVED)) {
                if (!loadedAnywhere(server, carrierId)) {
                    REMOVED_CARRIERS.add(carrierId);
                    for (final Portal holder : new ArrayList<>(HOLDERS.values())) {
                        if (carries(holder, carrierId)) {
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
        final List<Map.Entry<Portal, String>> due = new ArrayList<>(TO_SHATTER.entrySet());
        TO_SHATTER.clear();
        for (final Map.Entry<Portal, String> entry : due) {
            try {
                shatterNow(entry.getKey(), entry.getValue());
            } catch (final RuntimeException e) {
                LOGGER.warn("[IPShipPortal] shattering {} failed", entry.getKey(), e);
            }
        }
    }

    // ---- queries ----

    /** The driver of {@code portal}'s own end (this side of its animation), or null. */
    @Nullable
    public static IPShipPortalDriver driverOf(final Portal portal) {
        return IPShipPortals.mountOf(portal) instanceof final IPShipPortalDriver driver ? driver : null;
    }

    /** The driver on {@code holder}'s own end ({@code thisSide}) or on its far end, or null. */
    @Nullable
    static IPShipPortalDriver driverOf(final Portal holder, final boolean thisSide) {
        return thisSide ? driverOf(holder)
                : IPShipPortals.otherSideMountOf(holder) instanceof final IPShipPortalDriver driver ? driver : null;
    }

    /** True for a portal that holds a ship-mounted cluster's animation (a driver on either side). */
    static boolean isHolder(final Portal portal) {
        return driverOf(portal, true) != null || driverOf(portal, false) != null;
    }

    /** True when either end of {@code holder}'s cluster is mounted on {@code carrierId}. */
    private static boolean carries(final Portal holder, final UUID carrierId) {
        for (final boolean thisSide : SIDES) {
            final IPShipPortalDriver driver = driverOf(holder, thisSide);
            if (driver != null && driver.carrier.equals(carrierId)) {
                return true;
            }
        }
        return false;
    }

    private static final boolean[] SIDES = {true, false};

    public static boolean isShipMounted(final Portal portal) {
        return IPShipPortals.isShipMounted(portal);
    }

    /**
     * True for the portals of a cluster mounted on {@code ship} that are in its level: a carrier never goes through its
     * own portal, at either end.
     */
    public static boolean isCarriedBy(final Portal portal, final SubLevel ship) {
        final Portal holder = portal.getAnimationHolder();
        return holder != null && portal.level() == ship.getLevel() && carries(holder, ship.getUniqueId());
    }

    /**
     * The carriers of a ship-mounted portal, as a straddle session needs them: {@code source} carries the portal's own
     * side (it moves; a ship goes from that carrier's world through it), {@code destination} its far side (the
     * destination moves with it). Either is null for a side fixed in the world, never both; both may be one ship.
     */
    public record Mount(@Nullable ServerSubLevel source, @Nullable ServerSubLevel destination) {
    }

    /** @return the carriers of {@code portal}'s cluster, all of them loaded on this server, or null */
    @Nullable
    public static Mount mountFor(final Portal portal) {
        final Portal holder = portal.getAnimationHolder();
        if (holder == null) {
            return null;
        }
        final boolean holderSide = isHolderSide(portal, holder);
        final IPShipPortalDriver own = driverOf(holder, holderSide);
        final IPShipPortalDriver far = driverOf(holder, !holderSide);
        final ServerSubLevel source = loadedCarrier(own);
        final ServerSubLevel destination = loadedCarrier(far);
        if ((own == null && far == null) || (own != null && source == null) || (far != null && destination == null)) {
            return null;
        }
        return new Mount(source, destination);
    }

    @Nullable
    private static ServerSubLevel loadedCarrier(@Nullable final IPShipPortalDriver driver) {
        final ServerLevel level = driver == null ? null : MiscHelper.getServer().getLevel(driver.dimension);
        final SubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
        return container != null && container.getSubLevel(driver.carrier) instanceof final ServerSubLevel carrier
                && !carrier.isRemoved() ? carrier : null;
    }

    /**
     * Whether {@code portal} is on the holder's own end of the cluster whose holder is {@code holder}: the holder and
     * its flipped portal (the reverse and parallel portals are the far end). Not by level: with both ends in one
     * dimension all four are in one level.
     */
    static boolean isHolderSide(final Portal portal, final Portal holder) {
        return IPShipPortals.isOnHolderSide(portal, holder);
    }

    /** True when the end of its cluster that {@code portal} is on is mounted on a ship. */
    static boolean isEndMounted(final Portal portal) {
        final Portal holder = portal.getAnimationHolder();
        return holder != null && driverOf(holder, isHolderSide(portal, holder)) != null;
    }

    /**
     * One end of a mounted cluster, in its holder's terms: the holder's own side ({@code thisSide}) or its other side.
     * Its plot state is the holder's reference state on that side, its world state the holder's state on that side.
     */
    private record End(Portal holder, boolean thisSide) {
        @Nullable
        IPShipPortalDriver driver() {
            return driverOf(this.holder, this.thisSide);
        }

        @Nullable
        UnilateralPortalState plotState() {
            return this.thisSide ? this.holder.animation.thisSideReferenceState : this.holder.animation.otherSideReferenceState;
        }

        UnilateralPortalState worldState() {
            return this.thisSide ? this.holder.getThisSideState() : this.holder.getOtherSideState();
        }

        /** A portal on this end (the holder, or its reverse portal if loaded). */
        @Nullable
        Portal face() {
            return this.thisSide ? this.holder : PortalExtension.get(this.holder).reversePortal;
        }
    }

    // ---- anchoring ----

    /**
     * Makes {@code holder} (a ship-side portal of a bound cluster) follow {@code carrierId}. {@code plotState} is its state
     * in the carrier's plot; {@code currentDelta} is the delta that gives its current world state, kept until the
     * driver first sees the carrier.
     */
    public static void attach(final Portal holder, final UUID carrierId, final UnilateralPortalState plotState,
                              final DeltaUnilateralPortalState currentDelta) {
        attach(holder, true, carrierId, plotState, currentDelta);
    }

    /**
     * Mounts one end of {@code holder}'s cluster on {@code carrierId}: its own end ({@code thisSide}) or its far end
     * (the other side of its animation). {@code plotState} is that side of the holder in the carrier's plot;
     * {@code currentDelta} the delta that gives its current world state.
     */
    static void attach(final Portal holder, final boolean thisSide, final UUID carrierId, final UnilateralPortalState plotState,
                       final DeltaUnilateralPortalState currentDelta) {
        final IPShipPortalDriver driver = new IPShipPortalDriver(thisSide ? holder.level().dimension() : holder.getDestDim(),
                carrierId, plotState.position(),
                currentDelta.offset() == null ? Vec3.ZERO : currentDelta.offset(),
                currentDelta.rotation() == null ? DQuaternion.identity : currentDelta.rotation(),
                currentDelta.sizeScaling() == null ? 1.0 : currentDelta.sizeScaling().x);
        final PortalAnimation animation = holder.animation;
        // a side without a driver of its own has no reference state: IP takes it from the portal's current state
        if (thisSide) {
            animation.thisSideAnimations.removeIf(d -> d instanceof IPShipPortalDriver);
            animation.thisSideAnimations.add(driver);
            animation.thisSideReferenceState = plotState;
            if (driverOf(holder, false) == null) {
                animation.otherSideReferenceState = null;
            }
        } else {
            animation.otherSideAnimations.removeIf(d -> d instanceof IPShipPortalDriver);
            animation.otherSideAnimations.add(driver);
            animation.otherSideReferenceState = plotState;
            if (driverOf(holder, true) == null) {
                animation.thisSideReferenceState = null;
            }
        }
        PortalExtension.forClusterPortals(holder, p -> p.animation.defaultAnimation.durationTicks = 0);
        PortalExtension.forClusterPortals(holder, Portal::reloadAndSyncToClientNextTick);
        HOLDERS.put(holder.getUUID(), holder);
        MISSING.remove(holder.getUUID());
        LOGGER.info("[IPShipPortal] {} mounted on ship {} at {} (plot {})", holder, carrierId,
                thisSide ? "its own end" : "its far end", plotState.position());
    }

    /**
     * Ends the anchoring of one end of {@code holder}'s cluster: that end stays at {@code worldState} (the holder's
     * state on that side), with its frame's shape {@code worldShape} (null for a custom portal, which has none). The
     * other end stays mounted if it is; otherwise the cluster is a static pair again.
     */
    private static void detach(final Portal holder, final boolean thisSide, final UnilateralPortalState worldState,
                               @Nullable final BlockPortalShape worldShape, final String why) {
        final PortalAnimation animation = holder.animation;
        if (thisSide) {
            animation.thisSideAnimations.removeIf(d -> d instanceof IPShipPortalDriver);
            animation.thisSideReferenceState = null;
            holder.setThisSideState(worldState);
            if (holder instanceof final BreakablePortalEntity breakable && worldShape != null) {
                setShipSideShape(breakable, worldShape);
            }
        } else {
            animation.otherSideAnimations.removeIf(d -> d instanceof IPShipPortalDriver);
            animation.otherSideReferenceState = null;
            holder.setPortalState(UnilateralPortalState.combine(holder.getThisSideState(), worldState));
            if (PortalExtension.get(holder).reversePortal instanceof final BreakablePortalEntity reverse && worldShape != null) {
                setShipSideShape(reverse, worldShape);
            }
        }
        if (!isHolder(holder)) {
            animation.thisSideReferenceState = null;
            animation.otherSideReferenceState = null;
            HOLDERS.remove(holder.getUUID());
            MISSING.remove(holder.getUUID());
        }
        holder.reloadPortal();
        LOGGER.info("[IPShipPortal] {} is no longer on a ship at {} ({})", holder,
                thisSide ? "its own end" : "its far end", why);
    }

    /**
     * Mounts the end of {@code portal}'s cluster that {@code portal} is on: on the cluster's holder if it has one (its
     * own end or its far end), else with {@code portal} as the new holder. {@code toPlot} maps a world state of that end
     * into the carrier's plot.
     */
    static void mountEnd(final Portal portal, final UUID carrierId, final UnaryOperator<UnilateralPortalState> toPlot) {
        final Portal existing = portal.getAnimationHolder();
        final Portal holder = existing != null ? existing : portal;
        final boolean thisSide = isHolderSide(portal, holder);
        final UnilateralPortalState world = thisSide ? holder.getThisSideState() : holder.getOtherSideState();
        final UnilateralPortalState plot = toPlot.apply(world);
        attach(holder, thisSide, carrierId, plot, DeltaUnilateralPortalState.fromDiff(plot, world));
    }

    private static void setShipSideShape(final BreakablePortalEntity holder, final BlockPortalShape shape) {
        holder.blockPortalShape = shape;
        if (PortalExtension.get(holder).flippedPortal instanceof final BreakablePortalEntity flipped) {
            flipped.blockPortalShape = shape;
        }
    }

    // ---- shattering ----

    /** Shatters every portal mounted on {@code carrierId} now (call outside portal ticks). */
    public static void shatterCarriedPortals(final UUID carrierId, final String reason) {
        for (final Portal holder : new ArrayList<>(HOLDERS.values())) {
            if (carries(holder, carrierId)) {
                shatterNow(holder, reason);
            }
        }
    }

    private static void scheduleShatter(final Portal holder, final String reason) {
        TO_SHATTER.putIfAbsent(holder, reason);
    }

    /**
     * Breaks or removes all four portals of the cluster at once, with sound and particles at both sides (nether portals
     * break with their blocks, custom portals are removed).
     */
    public static void shatterNow(final Portal portal, final String reason) {
        final IPShipPortalBrokenLinks broken = IPShipPortalBrokenLinks.of(portal.getServer());
        if (portal.isRemoved() || broken.contains(portal.getUUID())) {
            return;
        }
        LOGGER.info("[IPShipPortal] shattering {}: {}", portal, reason);
        final List<Portal> members = new ArrayList<>();
        PortalExtension.forClusterPortals(portal, p -> {
            if (!members.contains(p)) {
                members.add(p);
            }
        });
        final Set<UUID> memberIds = members.stream().map(Portal::getUUID).collect(Collectors.toSet());
        for (final Portal member : members) {
            for (final UUID id : clusterIds(member)) {
                if (!memberIds.contains(id)) {
                    broken.add(id);
                }
            }
        }
        final Portal holder = portal.getAnimationHolder();
        final List<Portal> breaking = new ArrayList<>();
        for (final Portal member : members) {
            HOLDERS.remove(member.getUUID());
            MISSING.remove(member.getUUID());
            if (waitsForItsShip(holder, member)) {
                broken.add(member.getUUID());
                makeInert(member);
            } else {
                breaking.add(member);
            }
        }
        final Set<String> effectsAt = new HashSet<>();
        for (final Portal member : breaking) {
            shatterEffectsOnce(effectsAt, member);
        }
        breaking.forEach(IPShipPortalAnchors::breakPortal);
    }

    /** The ids of the portals linked to {@code portal}, whether loaded or not. */
    private static Set<UUID> clusterIds(final Portal portal) {
        final PortalExtension extension = PortalExtension.get(portal);
        final Set<UUID> ids = new HashSet<>();
        for (final UUID id : new UUID[]{extension.flippedPortalId, extension.reversePortalId, extension.parallelPortalId}) {
            if (id != null) {
                ids.add(id);
            }
        }
        if (portal instanceof final BreakablePortalEntity breakable && !breakable.isOneWay()) {
            ids.add(breakable.reversePortalId);
        }
        return ids;
    }

    /**
     * Whether {@code member}'s end is on a ship that is not loaded but still exists. Its portal blocks are in that ship's
     * plot, out of reach until it is back, and a frame with portal blocks left on it cannot be lit again.
     */
    private static boolean waitsForItsShip(@Nullable final Portal holder, final Portal member) {
        final IPShipPortalDriver driver = holder == null ? null : driverOf(holder, isHolderSide(member, holder));
        return driver != null && !REMOVED_CARRIERS.contains(driver.carrier) && loadedCarrier(driver) == null;
    }

    /** A portal waiting to finish breaking leads nowhere any more: it must not be seen or crossed meanwhile. */
    private static void makeInert(final Portal portal) {
        if (portal.isVisible()) {
            portal.setIsVisible(false);
            portal.setTeleportable(false);
            portal.reloadAndSyncToClient();
        }
    }

    private static void breakPortal(final Portal portal) {
        if (!(portal instanceof final BreakablePortalEntity breakable)) {
            portal.remove(Entity.RemovalReason.KILLED);
            return;
        }
        breakable.unbreakable = false;
        try {
            breakable.breakPortalNow();
        } catch (final RuntimeException e) {
            // a removed ship's plot is gone with its portal blocks: Sable refuses block changes there
            LOGGER.debug("[IPShipPortal] {} could not clear its portal blocks ({}); removing it", portal, e.toString());
            portal.remove(Entity.RemovalReason.KILLED);
        }
    }

    /** End of a server tick: portals left over from a shattered cluster break once they are loaded and can. */
    private static void finishBrokenLinks(final MinecraftServer server) {
        final IPShipPortalBrokenLinks broken = IPShipPortalBrokenLinks.of(server);
        if (broken.isEmpty()) {
            return;
        }
        final Set<String> effectsAt = new HashSet<>();
        for (final UUID id : broken.portals()) {
            for (final ServerLevel level : server.getAllLevels()) {
                if (level.getEntity(id) instanceof final Portal portal) {
                    if (portal instanceof final BreakablePortalEntity breakable && IPShipPortals.isOnUnloadedShip(breakable)) {
                        makeInert(portal);
                    } else {
                        LOGGER.info("[IPShipPortal] finishing the break of {}", portal);
                        shatterEffectsOnce(effectsAt, portal);
                        breakPortal(portal);
                        broken.remove(id);
                    }
                    break;
                }
            }
        }
    }

    private static void shatterEffectsOnce(final Set<String> effectsAt, final Portal portal) {
        final String place = portal.level().dimension().location() + " " + BlockPos.containing(portal.getOriginPos());
        if (effectsAt.add(place) && portal.level() instanceof final ServerLevel level) {
            shatterEffects(level, portal);
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

    /** A holder's entity ticks (before its animation updates). */
    private static void onHolderTick(final Portal portal) {
        if (portal.level().isClientSide() || !isHolder(portal)) {
            return;
        }
        final Portal holder = mergeHolders(portal);
        TICKED.add(holder.getUUID());
        if (holder != portal) {
            return; // its mount went to a holder that ticked already
        }
        HOLDERS.putIfAbsent(portal.getUUID(), portal);
        check(portal, portal.tickCount);
    }

    /**
     * Shatters a holder's cluster when portals on ships are switched off, or one of its carriers is removed, missing
     * too long or has its frame broken. {@code tick} paces the frame check.
     */
    private static void check(final Portal holder, final int tick) {
        if (!IPShipPortals.isEnabled()) {
            scheduleShatter(holder, "ship-mounted portals are switched off");
            return;
        }
        if (tick % INTEGRITY_INTERVAL == 0 && IPShipPortals.hasRangeLimit(holder)) {
            final double strain = IPShipPortals.linkStrain(holder);
            final int range = IPConfig.getConfig().shipPortalRangeChunks;
            if (strain > range) {
                scheduleShatter(holder, String.format("its link is under a strain of %.1f chunks, past the range of %d", strain, range));
                return;
            }
        }
        UUID missingCarrier = null;
        for (final boolean thisSide : SIDES) {
            final IPShipPortalDriver driver = driverOf(holder, thisSide);
            if (driver == null) {
                continue;
            }
            if (REMOVED_CARRIERS.contains(driver.carrier)) {
                scheduleShatter(holder, "its ship was removed");
                return;
            }
            if (!driver.carrierSeen()) {
                if (shouldBeLoaded(new End(holder, thisSide), driver)) {
                    missingCarrier = driver.carrier;
                }
                continue;
            }
            // custom portals have no frame: they stay on their ship whatever happens to the blocks around them
            final Portal face = new End(holder, thisSide).face();
            if (face instanceof final BreakablePortalEntity breakable && tick % INTEGRITY_INTERVAL == 0 && !breakable.isIntactNow()) {
                scheduleShatter(holder, "its frame on the ship is broken");
                return;
            }
        }
        if (missingCarrier == null) {
            MISSING.remove(holder.getUUID());
            return;
        }
        final int missing = MISSING.merge(holder.getUUID(), 1, Integer::sum);
        if (missing == MISSING_CARRIER_TICKS) {
            REMOVED_CARRIERS.add(missingCarrier);
            scheduleShatter(holder, "its ship has not been loaded for " + MISSING_CARRIER_TICKS + " ticks");
        }
    }

    /**
     * Whether {@code end}'s ship would be loaded by now if it still existed. Sable keeps a ship loaded only while every
     * chunk it overlaps is block-ticking, so a ship that is merely unloaded must not count as missing: the chunks around
     * its last position have to tick and Sable must not be holding it, ready to load.
     */
    private static boolean shouldBeLoaded(final End end, final IPShipPortalDriver driver) {
        final ServerLevel level = MiscHelper.getServer().getLevel(driver.dimension);
        if (level == null) {
            return true;
        }
        final ChunkPos center = new ChunkPos(BlockPos.containing(end.worldState().position()));
        for (int x = center.x - MISSING_CHECK_CHUNKS; x <= center.x + MISSING_CHECK_CHUNKS; x++) {
            for (int z = center.z - MISSING_CHECK_CHUNKS; z <= center.z + MISSING_CHECK_CHUNKS; z++) {
                if (!PhysicsChunkTicketManager.isChunkLoadedEnough(level, x, z)) {
                    return false;
                }
            }
        }
        return !(SubLevelContainer.getContainer(level) instanceof final ServerSubLevelContainer container)
                || container.getHoldingChunkMap().getHoldingSubLevel(driver.carrier) == null;
    }

    /**
     * End of the server tick: a holder with its far end on a ship moves that end, so it must update even when its own
     * entity did not tick (a player at the far end, nobody near the holder: IP keeps its chunks loaded for the view,
     * but they need not tick entities). Its animation runs here once, still in this tick, so it never runs twice.
     * The far end then lags its ship by a tick on the server while entities there tick.
     */
    private static void tickUntickedHolders(final MinecraftServer server) {
        for (final Portal holder : new ArrayList<>(HOLDERS.values())) {
            if (holder.isRemoved() || TICKED.contains(holder.getUUID()) || driverOf(holder, false) == null
                    || mergeHolders(holder) != holder) {
                continue;
            }
            holder.animation.tick(holder);
            check(holder, server.getTickCount());
        }
        TICKED.clear();
    }

    /**
     * A cluster has one holder. A second one appears when one end was mounted while the other end's portals were not
     * loaded (nobody could tell that end was on a ship already); once the two are linked, the one that has not ticked
     * this tick hands its mount to the other as its far end (two holders would both update the cluster, and IP clears
     * every driver of a portal updated twice in a tick).
     *
     * @return the holder that keeps the mount: {@code holder}, or the cluster's other holder
     */
    private static Portal mergeHolders(final Portal holder) {
        final PortalExtension ext = PortalExtension.get(holder);
        for (final Portal other : new Portal[]{ext.reversePortal, ext.parallelPortal, ext.flippedPortal}) {
            if (other == null || other == holder || other.isRemoved() || !isHolder(other)) {
                continue;
            }
            final boolean otherFirst = TICKED.contains(other.getUUID());
            final Portal into = otherFirst ? other : holder;
            final Portal from = otherFirst ? holder : other;
            final IPShipPortalDriver moved = driverOf(from);
            final boolean far = !isHolderSide(from, into);
            if (moved != null && far && driverOf(into, false) == null && from.animation.thisSideReferenceState != null) {
                // the far end in the holder's terms: the end's plot state, with the holder's orientation convention
                // for its other side (its other side and the other portal's own side are the same end right now:
                // whichever updated last set the other with rectifyClusterPortals)
                final UnilateralPortalState fromWorld = from.getThisSideState();
                final UnilateralPortalState fromPlot = from.animation.thisSideReferenceState;
                final UnilateralPortalState intoWorld = into.getOtherSideState();
                final DQuaternion toPlot = fromPlot.orientation().hamiltonProduct(fromWorld.orientation().getConjugated());
                // a scaled carrier's end is larger in the world than in its plot (Create: Pocket Sized)
                final double toPlotSize = fromWorld.width() == 0 ? 1.0 : fromPlot.width() / fromWorld.width();
                final UnilateralPortalState plot = new UnilateralPortalState.Builder().from(intoWorld)
                        .position(fromPlot.position()).orientation(toPlot.hamiltonProduct(intoWorld.orientation()))
                        .width(intoWorld.width() * toPlotSize).height(intoWorld.height() * toPlotSize)
                        .thickness(intoWorld.thickness() * toPlotSize).build();
                into.animation.otherSideAnimations.add(moved);
                into.animation.otherSideReferenceState = plot;
                LOGGER.info("[IPShipPortal] {} takes the mount of {} on ship {} as its far end", into, from, moved.carrier);
            } else {
                LOGGER.warn("[IPShipPortal] {} and {} both hold the cluster; dropping the mount of {}", into, from, from);
            }
            from.animation.thisSideAnimations.removeIf(d -> d instanceof IPShipPortalDriver);
            from.animation.otherSideAnimations.removeIf(d -> d instanceof IPShipPortalDriver);
            from.animation.thisSideReferenceState = null;
            from.animation.otherSideReferenceState = null;
            HOLDERS.remove(from.getUUID());
            MISSING.remove(from.getUUID());
            HOLDERS.put(into.getUUID(), into);
            PortalExtension.forClusterPortals(into, Portal::reloadAndSyncToClientNextTick);
            return into;
        }
        return holder;
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
     * portal moved only partly breaks. Custom portals (no frame) go with the majority of the blocks they touch
     * ({@link #support}) and are never broken here.
     */
    public static void onBlocksMoving(final ServerLevel level, final SubLevelAssemblyHelper.AssemblyTransform transform,
                                      final Iterable<BlockPos> blocks, final List<Portal> worldPortals) {
        final List<End> ends = new ArrayList<>();
        for (final Portal holder : HOLDERS.values()) {
            if (holder.isRemoved()) {
                continue;
            }
            for (final boolean thisSide : SIDES) {
                final End end = new End(holder, thisSide);
                final IPShipPortalDriver driver = end.driver();
                if (driver != null && driver.dimension == level.dimension() && end.plotState() != null) {
                    ends.add(end);
                }
            }
        }
        if (worldPortals.isEmpty() && ends.isEmpty()) {
            return;
        }

        // the iterable may reuse a mutable position (BlockPos.betweenClosed), hence the copies
        final Set<BlockPos> moved = new HashSet<>();
        for (final BlockPos block : blocks) {
            moved.add(block.immutable());
        }
        if (moved.isEmpty()) {
            return;
        }

        for (final End end : ends) {
            if (end.holder().isRemoved() || end.driver() == null) {
                continue; // shattered with the other end
            }
            if (end.holder() instanceof BreakablePortalEntity) {
                if (end.face() instanceof final BreakablePortalEntity face && face.blockPortalShape != null) {
                    moveNetherMount(level, transform, moved, end, face);
                } else {
                    LOGGER.warn("[IPShipPortal] cannot tell whether {}'s far end moves: its portals there are not loaded", end.holder());
                }
            } else {
                moveCustomMount(level, transform, moved, end);
            }
        }

        final Set<UUID> handled = new HashSet<>();
        captureCustomPortals(level, transform, moved, worldPortals, handled);
        for (final Portal p : worldPortals) {
            if (!(p instanceof final BreakablePortalEntity portal) || portal.isRemoved() || handled.contains(portal.getUUID())) {
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
            if (isEndMounted(portal)) {
                shatterNow(portal, "it is on a ship already");
                continue;
            }
            final BlockPortalShape plotShape = transformShape(portal.blockPortalShape, transform);
            final SubLevel carrier = Sable.HELPER.getContaining(level, plotShape.anchor);
            if (carrier == null) {
                shatterNow(portal, "its frame is being assembled into a plot without a ship");
                continue;
            }
            carryPortalBlocks(level, portal.blockPortalShape, plotShape, moved);
            setShipSideShape(portal, plotShape);
            // its other side may be on a ship already: then this is the cluster's far end
            mountEnd(portal, carrier.getUniqueId(), world -> transformState(world, transform));
        }
    }

    /**
     * A mounted nether portal end whose frame is among the moved blocks goes with it, or breaks if only part of it moves.
     * {@code face} is a portal on that end (its block shape is the end's frame).
     */
    private static void moveNetherMount(final ServerLevel level, final SubLevelAssemblyHelper.AssemblyTransform transform,
                                        final Set<BlockPos> moved, final End end, final BreakablePortalEntity face) {
        final Coverage coverage = coverage(face.blockPortalShape, moved);
        if (coverage == Coverage.NONE) {
            return;
        }
        if (coverage == Coverage.PARTIAL) {
            shatterNow(end.holder(), "its frame was split by a ship being assembled or disassembled");
            return;
        }
        final UnilateralPortalState newState = transformState(end.plotState(), transform);
        final BlockPortalShape newShape = transformShape(face.blockPortalShape, transform);
        carryPortalBlocks(level, face.blockPortalShape, newShape, moved);
        if (IPSableCompatHelper.isPlotSpacePosition(newState.position().x, newState.position().z)) {
            final SubLevel newCarrier = Sable.HELPER.getContaining(level, newShape.anchor);
            if (newCarrier == null) {
                shatterNow(end.holder(), "its frame moved into a plot without a ship");
                return;
            }
            setShipSideShape(face, newShape);
            attach(end.holder(), end.thisSide(), newCarrier.getUniqueId(), newState,
                    DeltaUnilateralPortalState.fromDiff(newState, end.worldState()));
        } else {
            detach(end.holder(), end.thisSide(), newState, newShape, "disassembled");
        }
    }

    /**
     * A mounted custom portal end goes with the majority of the blocks it touches: into the world on disassembly, onto
     * the new ship on a split. One that touches no blocks goes with its ship's disassembly.
     */
    private static void moveCustomMount(final ServerLevel level, final SubLevelAssemblyHelper.AssemblyTransform transform,
                                        final Set<BlockPos> moved, final End end) {
        final IPShipPortalDriver driver = end.driver();
        final UnilateralPortalState plotState = end.plotState();
        final Set<BlockPos> support = support(level, plotState).blocks();
        BlockPos anchor = carriedBlock(support, moved);
        if (support.isEmpty()) {
            final BlockPos any = moved.iterator().next();
            final SubLevel from = Sable.HELPER.getContaining(level, any);
            final BlockPos to = transform.apply(any);
            if (from != null && from.getUniqueId().equals(driver.carrier) && !IPSableCompatHelper.isPlotSpacePosition(to.getX(), to.getZ())) {
                anchor = any;
            }
        }
        if (anchor == null) {
            return;
        }
        final UnilateralPortalState newState = transformState(plotState, transform);
        final BlockPos newAnchor = transform.apply(anchor);
        if (!IPSableCompatHelper.isPlotSpacePosition(newAnchor.getX(), newAnchor.getZ())) {
            detach(end.holder(), end.thisSide(), newState, null, "disassembled");
            return;
        }
        final SubLevel newCarrier = Sable.HELPER.getContaining(level, newAnchor);
        if (newCarrier == null) {
            scheduleShatter(end.holder(), "its blocks moved into a plot without a ship");
            return;
        }
        attach(end.holder(), end.thisSide(), newCarrier.getUniqueId(), newState,
                DeltaUnilateralPortalState.fromDiff(newState, end.worldState()));
    }

    /**
     * Custom world portals whose blocks are being assembled into a ship are mounted on it, each end on its own: both ends
     * on the moved blocks go onto the new ship together, and an end whose other end is on a ship already becomes the
     * cluster's far end.
     */
    private static void captureCustomPortals(final ServerLevel level, final SubLevelAssemblyHelper.AssemblyTransform transform,
                                             final Set<BlockPos> moved, final List<Portal> worldPortals, final Set<UUID> handled) {
        if (!IPShipPortals.isEnabled()) {
            return;
        }
        final Map<Portal, BlockPos> captured = new LinkedHashMap<>();
        for (final Portal portal : worldPortals) {
            if (!isCustom(portal) || portal.isRemoved()) {
                continue;
            }
            final BlockPos anchor = carriedBlock(support(level, portal.getThisSideState()).blocks(), moved);
            if (anchor != null) {
                captured.put(portal, anchor);
            }
        }
        for (final Map.Entry<Portal, BlockPos> entry : captured.entrySet()) {
            final Portal portal = entry.getKey();
            if (handled.contains(portal.getUUID())) {
                continue;
            }
            final PortalExtension ext = PortalExtension.get(portal);
            handled.add(portal.getUUID());
            if (ext.flippedPortal != null) {
                handled.add(ext.flippedPortal.getUUID());
            }
            String refusal = whyNotCustomMount(portal);
            final SubLevel carrier = refusal == null ? Sable.HELPER.getContaining(level, transform.apply(entry.getValue())) : null;
            if (refusal == null && carrier == null) {
                refusal = "its blocks are being assembled into a plot without a ship";
            }
            if (refusal != null) {
                LOGGER.info("[IPShipPortal] not mounting {} on the ship being assembled, it stays in the world: {}", portal, refusal);
                continue;
            }
            mountEnd(portal, carrier.getUniqueId(), world -> transformState(world, transform));
        }
    }

    // ---- custom portals ----

    /** How far from a custom portal's rectangle a block's cube may be and still hold the portal (its support). */
    private static final double SUPPORT_REACH = 0.5;
    private static final int SUPPORT_SAMPLES = 64;

    /**
     * A portal made with IP's portal wand or {@code /portal} commands: the plain portal entity type, not global, not a
     * mirror, an end portal or a nether portal (docs/lessons/ship-mounted-portals.md, "Custom portals").
     */
    public static boolean isCustom(final Portal portal) {
        return portal.getType() == Portal.ENTITY_TYPE && !portal.getIsGlobal();
    }

    /** @return why {@code portal}'s end of its cluster cannot be mounted on a ship, or null if it can (custom portals only) */
    @Nullable
    public static String whyNotCustomMount(final Portal portal) {
        if (!isCustom(portal)) {
            return "only portals made with the portal wand or /portal commands can be mounted";
        }
        if (!PortalExtension.get(portal).bindCluster) {
            return "its portals are not bound as a cluster (/portal set_portal_nbt {bindCluster:true})";
        }
        final Portal holder = portal.getAnimationHolder();
        if (holder != null && !isHolder(holder)) {
            return "it has an animation";
        }
        if (isEndMounted(portal)) {
            return "this side of it is on a ship already";
        }
        return null;
    }

    /**
     * The blocks around a custom portal ({@code state} in world or plot coordinates, read in {@code level}): those with
     * collision whose cube is within {@value #SUPPORT_REACH} of the portal's rectangle hold it ({@code blocks}), except
     * blocks in the opening (across the plane, centre inside the rectangle), which are passing through it
     * ({@code obstructed}): a ship straddling a portal never holds it. A frame overlapping the rectangle's edges a little
     * still holds it.
     */
    record Support(Set<BlockPos> blocks, boolean obstructed) {
    }

    private static final double SUPPORT_EPSILON = 0.05;

    static Support support(final Level level, final UnilateralPortalState state) {
        final Vec3 axisW = state.getAxisW();
        final Vec3 axisH = state.getAxisH();
        final Vec3 normal = state.getNormal();
        final double reachW = state.width() / 2 - SUPPORT_EPSILON;
        final double reachH = state.height() / 2 - SUPPORT_EPSILON;
        final double reachN = halfExtent(normal) - SUPPORT_EPSILON;
        final int nw = Math.max(1, Math.min(SUPPORT_SAMPLES, (int) Math.ceil(state.width() / 0.25)));
        final int nh = Math.max(1, Math.min(SUPPORT_SAMPLES, (int) Math.ceil(state.height() / 0.25)));
        final Set<BlockPos> checked = new HashSet<>();
        final Set<BlockPos> found = new HashSet<>();
        boolean obstructed = false;
        final BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i <= nw; i++) {
            for (int j = 0; j <= nh; j++) {
                final Vec3 p = state.position()
                        .add(axisW.scale(state.width() * ((double) i / nw - 0.5)))
                        .add(axisH.scale(state.height() * ((double) j / nh - 0.5)));
                for (int x = Mth.floor(p.x - SUPPORT_REACH); x <= Mth.floor(p.x + SUPPORT_REACH); x++) {
                    for (int y = Mth.floor(p.y - SUPPORT_REACH); y <= Mth.floor(p.y + SUPPORT_REACH); y++) {
                        for (int z = Mth.floor(p.z - SUPPORT_REACH); z <= Mth.floor(p.z + SUPPORT_REACH); z++) {
                            pos.set(x, y, z);
                            if (!checked.add(pos.immutable())) {
                                continue;
                            }
                            final BlockState block = level.getBlockState(pos);
                            if (block.isAir() || block.getCollisionShape(level, pos).isEmpty()) {
                                continue;
                            }
                            final Vec3 c = Vec3.atCenterOf(pos).subtract(state.position());
                            if (Math.abs(c.dot(normal)) < reachN && Math.abs(c.dot(axisW)) < reachW && Math.abs(c.dot(axisH)) < reachH) {
                                obstructed = true;
                            } else {
                                found.add(pos.immutable());
                            }
                        }
                    }
                }
            }
        }
        return new Support(found, obstructed);
    }

    /** Half the extent of a unit block cube along the unit vector {@code d}. */
    private static double halfExtent(final Vec3 d) {
        return 0.5 * (Math.abs(d.x) + Math.abs(d.y) + Math.abs(d.z));
    }

    /** A moved block of {@code support} if more than half of it moves, else null. */
    @Nullable
    private static BlockPos carriedBlock(final Set<BlockPos> support, final Set<BlockPos> moved) {
        BlockPos any = null;
        int in = 0;
        for (final BlockPos pos : support) {
            if (moved.contains(pos)) {
                in++;
                any = pos;
            }
        }
        return in * 2 > support.size() ? any : null;
    }

    /**
     * Mounts each end of the custom portal {@code portal}'s cluster that is not on a ship yet and touches a ship's blocks
     * on that ship (the one it touches most), whichever end {@code portal} is on ({@code /ip_aeronautics shipportals
     * mount}). Both ends may go onto ships, one or two.
     *
     * @return null when mounted, else why not
     */
    @Nullable
    public static String mountCustom(final Portal portal) {
        if (!IPShipPortals.isEnabled()) {
            return "ship-mounted portals are switched off";
        }
        final PortalExtension ext = PortalExtension.get(portal);
        final List<ShipSide> sides = new ArrayList<>();
        String refusal = null;
        boolean anyFree = false;
        for (final Portal[] end : new Portal[][]{{portal, ext.flippedPortal}, {ext.reversePortal, ext.parallelPortal}}) {
            final Portal face = end[0] != null ? end[0] : end[1];
            if (face == null) {
                continue; // that end is not loaded
            }
            final String why = whyNotCustomMount(face);
            if (why != null) {
                refusal = refusal == null ? why : refusal;
                continue;
            }
            anyFree = true;
            final ShipSide side = shipSideOf(end[0], end[1]);
            if (side != null) {
                sides.add(side);
            }
        }
        if (sides.isEmpty()) {
            return anyFree ? "no side of it that is not on a ship yet touches a ship's blocks (a ship passing through it does not count)"
                    : refusal;
        }
        for (final ShipSide side : sides) {
            final Pose3dc pose = side.ship().logicalPose();
            mountEnd(side.portal(), side.ship().getUniqueId(), world -> toPlot(world, pose));
        }
        return null;
    }

    private record ShipSide(Portal portal, SubLevel ship, UnilateralPortalState plotState) {
    }

    /** The ship whose blocks hold one side of a cluster most (none passing through it), from either of its two faces. */
    @Nullable
    private static ShipSide shipSideOf(@Nullable final Portal face, @Nullable final Portal otherFace) {
        final ShipSide side = face == null ? null : shipSideOf(face);
        return side != null || otherFace == null ? side : shipSideOf(otherFace);
    }

    /** The ship whose blocks hold {@code portal}'s side most (none passing through it), or null. */
    @Nullable
    private static ShipSide shipSideOf(final Portal portal) {
        final Level level = portal.level();
        final UnilateralPortalState world = portal.getThisSideState();
        ShipSide best = null;
        int bestCount = 0;
        for (final SubLevel ship : Sable.HELPER.getAllIntersecting(level, new BoundingBox3d(portal.getBoundingBox().inflate(1.0)))) {
            if (ship.isRemoved()) {
                continue;
            }
            final UnilateralPortalState plot = toPlot(world, ship.logicalPose());
            final Support support = support(level, plot);
            if (!support.obstructed() && support.blocks().size() > bestCount) {
                best = new ShipSide(portal, ship, plot);
                bestCount = support.blocks().size();
            }
        }
        return best;
    }

    /** Ends a custom portal's mount by command, at both ends: it stays where it is now. @return null when done, else why not */
    @Nullable
    public static String unmountCustom(final Portal portal) {
        final Portal holder = portal.getAnimationHolder();
        if (holder == null || !isHolder(holder)) {
            return "it is not mounted on a ship";
        }
        if (!isCustom(holder)) {
            return "nether portals on ships come off by disassembling the ship";
        }
        for (final boolean thisSide : SIDES) {
            if (driverOf(holder, thisSide) != null) {
                detach(holder, thisSide, new End(holder, thisSide).worldState(), null, "unmounted by command");
            }
        }
        return null;
    }

    /** {@code world} (a portal side in the world) in the plot of the ship at {@code pose}, sizes divided by its scale. */
    static UnilateralPortalState toPlot(final UnilateralPortalState world, final Pose3dc pose) {
        final double scale = IPShipScale.of(pose);
        return new UnilateralPortalState(world.dimension(), pose.transformPositionInverse(world.position()),
                PortalManipulation.getPortalOrientationQuaternion(pose.transformNormalInverse(world.getAxisW()).normalize(),
                        pose.transformNormalInverse(world.getAxisH()).normalize()),
                world.width() / scale, world.height() / scale, world.thickness() / scale);
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
     * For the status command and headless tests: one line per loaded holder, with each end's position, whether it is on
     * a ship, and its distance from where its frame is on that ship now.
     */
    public static List<String> describe() {
        final List<String> lines = new ArrayList<>();
        for (final Portal holder : HOLDERS.values()) {
            if (!isHolder(holder)) {
                continue;
            }
            final Vec3 origin = holder.getOriginPos();
            final Portal reverse = PortalExtension.get(holder).reversePortal;
            final String back = reverse == null ? "far side not loaded"
                    : String.format("far side leads back %.6f from it", reverse.getDestPos().distanceTo(origin));
            lines.add(String.format("%s %s at %.4f %.4f %.4f (%s), leads to %s %s %.4f %.4f %.4f (%s), strain %.1f chunks",
                    holder.getUUID(), onShip(driverOf(holder, true)), origin.x, origin.y, origin.z, fromFrame(driverOf(holder, true), origin),
                    onShip(driverOf(holder, false)), holder.getDestDim().location(),
                    holder.getDestPos().x, holder.getDestPos().y, holder.getDestPos().z,
                    driverOf(holder, false) == null ? back : fromFrame(driverOf(holder, false), holder.getDestPos()) + ", " + back,
                    IPShipPortals.linkStrain(holder))
                    + String.format(", %.4f x %.4f, scale %.4f", holder.getWidth(), holder.getHeight(), holder.getScale()));
        }
        return lines;
    }

    /** For the mount command: the ships {@code portal}'s side and its other side are on. */
    public static String describeEnds(final Portal portal) {
        final Portal holder = portal.getAnimationHolder();
        if (holder == null) {
            return "not on a ship";
        }
        final boolean own = isHolderSide(portal, holder);
        return "this side " + onShip(driverOf(holder, own)) + ", the other side " + onShip(driverOf(holder, !own));
    }

    private static String onShip(@Nullable final IPShipPortalDriver driver) {
        return driver == null ? "fixed" : "on ship " + driver.carrier;
    }

    /** How far {@code origin} (an end's position) is from where its ship's pose puts its plot origin now. */
    private static String fromFrame(@Nullable final IPShipPortalDriver driver, final Vec3 origin) {
        if (driver == null) {
            return "fixed";
        }
        final ServerLevel level = MiscHelper.getServer().getLevel(driver.dimension);
        final SubLevelContainer container = level == null ? null : SubLevelContainer.getContainer(level);
        final SubLevel ship = container == null ? null : container.getSubLevel(driver.carrier);
        if (ship == null) {
            return "ship not loaded";
        }
        final org.joml.Vector3d expected = ship.logicalPose().transformPosition(
                new org.joml.Vector3d(driver.plotOrigin.x, driver.plotOrigin.y, driver.plotOrigin.z));
        return String.format("%.6f from its frame", origin.distanceTo(new Vec3(expected.x, expected.y, expected.z)));
    }
}
