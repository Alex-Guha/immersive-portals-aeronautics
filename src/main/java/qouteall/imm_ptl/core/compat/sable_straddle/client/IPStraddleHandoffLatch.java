/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The idea (keep drawing the old copy until the new frame can take over) follows IPSable 0.5.0's hand-off latch by
 * r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.network.client.SubLevelSnapshotInterpolator;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Hides the blink of a straddle hand-off (docs/lessons/straddle-handoff-client.md). The server moves a ship that is
 * wholly through a portal into the destination dimension: the client is told to stop tracking the old copy, and
 * receives the ship anew in the destination, where it is invisible until its meshes compile. Here the old copy's
 * removal is deferred (it keeps being drawn, through its retired session also as the far half in the destination)
 * until the new copy's meshes are compiled, the session turns out not to have ended in a hand-off, the slot is needed
 * again, or {@value #MAX_MILLIS} ms passed. Render thread only.
 * <p>
 * Both copies show one motion ({@link #link}): the new copy gets the old copy's recent pose snapshots, mapped through
 * the portal, so it interpolates on from where the old copy was instead of waiting on its first snapshot; and the old
 * copy is drawn at the new copy's pose mapped back ({@link #followedPose}) instead of freezing at its last snapshot
 * (its own dimension's interpolation clock may stall once nothing else is tracked there). Before 1.17.1 the ship froze
 * at the hand-off until the new copy's meshes compiled, then jumped on.
 */
public final class IPStraddleHandoffLatch {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long MAX_MILLIS = 3000L;
    /** Snapshots of the two copies are one server tick when their positions agree this well [blocks] through the portal. */
    private static final double MATCH_TOLERANCE = 1.0e-3;

    private record Pending(ClientLevel level, int x, int z, ClientSubLevel ship, long deadline) {
    }

    private static final Map<ClientSubLevel, Pending> PENDING = new IdentityHashMap<>();

    /**
     * A held copy and its replacement in the destination. {@code plotShift} is the replacement's rotation point minus
     * the held copy's (the ship may have moved plot). Diagnostics: when it was linked and how many frames followed.
     */
    private static final class Link {
        final ClientSubLevel replacement;
        final IPPortalIsometry isometry;
        final Vector3dc plotShift;
        final long linkedAt = System.currentTimeMillis();
        int followedFrames;
        float cachedPartialTick = Float.NaN;
        final Pose3d cached = new Pose3d();

        Link(final ClientSubLevel replacement, final IPPortalIsometry isometry, final Vector3dc plotShift) {
            this.replacement = replacement;
            this.isometry = isometry;
            this.plotShift = plotShift;
        }
    }

    private static final Map<ClientSubLevel, Link> LINKS = new IdentityHashMap<>();

    /**
     * New copies playing on their old copy's timeline: the tick their interpolator samples at (instead of their own
     * dimension's pointer), and how many ticks their own pointer has been out of reach.
     */
    private static final class Timeline {
        double pointer;
        int unreachableTicks;

        Timeline(final double pointer) {
            this.pointer = pointer;
        }
    }

    private static final Map<ClientSubLevel, Timeline> TIMELINES = new IdentityHashMap<>();
    /** How fast a new copy's timeline converges on its own dimension's pointer [ticks per tick]: a 10% speed change. */
    private static final double TIMELINE_CONVERGENCE = 0.1;
    /** A pointer this far [ticks] from the timeline is not running yet (or stalled); the timeline goes on alone. */
    private static final double TIMELINE_REACH = 20.0;
    private static final int TIMELINE_MAX_UNREACHABLE_TICKS = 100;


    private IPStraddleHandoffLatch() {
    }

    static void clear() {
        PENDING.clear();
        LINKS.clear();
        TIMELINES.clear();
    }

    /**
     * @return whether the removal of the ship in plot slot ({@code x}, {@code z}) of {@code level} is deferred (the
     * caller then skips it)
     */
    public static boolean deferRemoval(final Level level, final int x, final int z) {
        if (!(level instanceof final ClientLevel clientLevel)) {
            return false;
        }
        final SubLevelContainer container = SubLevelContainer.getContainer(clientLevel);
        if (container == null || !(container.getSubLevel(x, z) instanceof final ClientSubLevel ship)) {
            return false;
        }
        if (PENDING.containsKey(ship) || !IPStraddleClientSessions.mayBeHandingOff(ship) || handsOffWithinLevel(ship)) {
            return false;
        }
        PENDING.put(ship, new Pending(clientLevel, x, z, ship, System.currentTimeMillis() + MAX_MILLIS));
        // the new copy may have arrived first (a ship sent to portal viewers right at the hand-off)
        tryLink(ship);
        return true;
    }

    /**
     * A ship going through a portal into its own dimension cannot be held: Sable's container keys ships by UUID, so
     * the old and the new copy cannot coexist in one level (and the new copy's start-tracking would release the slot).
     */
    private static boolean handsOffWithinLevel(final ClientSubLevel ship) {
        for (final IPStraddleClientSessions.Session session : IPStraddleClientSessions.renderSessionsOf(ship)) {
            if (session.destDim().equals(session.shipDim())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Ship {@code id} is about to be tracked in plot slot ({@code x}, {@code z}) of {@code level}: a held copy in that
     * slot, or of that ship in that level, goes.
     */
    public static void releaseSlot(final Level level, final int x, final int z, final UUID id) {
        for (final Pending pending : List.copyOf(PENDING.values())) {
            if (pending.level() == level
                    && ((pending.x() == x && pending.z() == z) || pending.ship().getUniqueId().equals(id))) {
                release(pending);
            }
        }
    }

    public static boolean isPending(final UUID ship) {
        for (final ClientSubLevel pending : PENDING.keySet()) {
            if (pending.getUniqueId().equals(ship)) {
                return true;
            }
        }
        return false;
    }

    public static void tick() {
        if (PENDING.isEmpty()) {
            return;
        }
        for (final Link link : LINKS.values()) {
            link.cachedPartialTick = Float.NaN;
        }
        if (!TIMELINES.isEmpty()) {
            TIMELINES.keySet().removeIf(ClientSubLevel::isRemoved);
        }
        final long now = System.currentTimeMillis();
        for (final Pending pending : new ArrayList<>(PENDING.values())) {
            if (pending.ship().isRemoved()) {
                PENDING.remove(pending.ship());
                LINKS.remove(pending.ship());
            } else if (now > pending.deadline()
                    || !IPStraddleClientSessions.mayBeHandingOff(pending.ship())
                    || replacementReady(pending.ship())) {
                release(pending);
            }
        }
    }

    private static void release(final Pending pending) {
        PENDING.remove(pending.ship());
        final Link link = LINKS.remove(pending.ship());
        LOGGER.debug("[IPStraddle] client: hand-off latch of ship {} released after {} ms ({}){}",
                pending.ship().getUniqueId(), System.currentTimeMillis() - (pending.deadline() - MAX_MILLIS),
                replacementReady(pending.ship()) ? "new copy compiled" : "no compiled new copy",
                link == null ? ", never linked to the new copy"
                        : ", linked " + (System.currentTimeMillis() - link.linkedAt) + " ms, followed it for " + link.followedFrames + " pose reads");
        final SubLevelContainer container = SubLevelContainer.getContainer(pending.level());
        if (container != null && container.getSubLevel(pending.x(), pending.z()) == pending.ship()) {
            container.removeSubLevel(pending.x(), pending.z(), SubLevelRemovalReason.REMOVED);
        }
    }

    /**
     * Ship {@code id} was just tracked in {@code level} (Sable's start-tracking packet, after its handler): if a copy of
     * it in another dimension is held for a hand-off, link the two.
     */
    public static void onTracked(final Level level, final UUID id) {
        if (PENDING.isEmpty() || !(level instanceof ClientLevel)) {
            return;
        }
        for (final ClientSubLevel held : List.copyOf(PENDING.keySet())) {
            if (held.getUniqueId().equals(id) && held.getLevel() != level) {
                tryLink(held);
            }
        }
    }

    /**
     * Links a held copy to its replacement in the destination of one of its (active or just ended) sessions, if that
     * copy exists.
     */
    private static void tryLink(final ClientSubLevel held) {
        if (LINKS.containsKey(held)) {
            return;
        }
        for (final IPStraddleClientSessions.Session session : IPStraddleClientSessions.renderSessionsOf(held)) {
            final ClientLevel destLevel = ClientWorldLoader.getOptionalWorld(session.destDim());
            if (destLevel == held.getLevel()) {
                // a portal into the ship's own dimension: the copy found there would be the held one itself
                continue;
            }
            final ClientSubLevelContainer container = destLevel == null ? null : ClientSubLevelContainer.getContainer(destLevel);
            if (container != null && container.getSubLevel(held.getUniqueId()) instanceof final ClientSubLevel replacement
                    && !replacement.isRemoved()) {
                link(held, replacement, session.isometry(), container);
                return;
            }
        }
    }

    /**
     * Pairs a snapshot of the replacement with the held copy's snapshot at the same pose through the portal (the
     * latest such pair; the two copies' snapshot clocks are per dimension), then gives the replacement the held copy's
     * older snapshots, mapped. A replacement sent right at the hand-off starts with two snapshots of one pose (Sable's
     * start-tracking: last pose and pose, equal for a ship just loaded); the second goes, or the interpolator would
     * drop the next real snapshot as a duplicate tick and the ship would stand still for a tick.
     */
    private static void link(final ClientSubLevel held, final ClientSubLevel replacement, final IPPortalIsometry isometry,
                             final ClientSubLevelContainer container) {
        final SubLevelSnapshotInterpolator interpolator = replacement.getInterpolator();
        final List<SubLevelSnapshotInterpolator.Snapshot> history;
        synchronized (held.getInterpolator().buffer) {
            history = new ArrayList<>(held.getInterpolator().buffer);
        }
        int added = 0;
        boolean droppedDuplicate = false;
        SubLevelSnapshotInterpolator.Snapshot heldMatch = null;
        SubLevelSnapshotInterpolator.Snapshot newMatch = null;
        synchronized (interpolator.buffer) {
            final List<SubLevelSnapshotInterpolator.Snapshot> buffer = interpolator.buffer;
            if (buffer.size() == 2 && samePose(buffer.get(0).pose(), buffer.get(1).pose())) {
                buffer.remove(1);
                droppedDuplicate = true;
            }
            search:
            for (int h = history.size() - 1; h >= 0; h--) {
                final Pose3d mapped = isometry.mapPose(history.get(h).pose());
                for (final SubLevelSnapshotInterpolator.Snapshot candidate : buffer) {
                    if (mapped.position().distanceSquared(candidate.pose().position()) <= MATCH_TOLERANCE * MATCH_TOLERANCE) {
                        heldMatch = history.get(h);
                        newMatch = candidate;
                        break search;
                    }
                }
            }
            final Pose3dc heldReference = heldMatch != null ? heldMatch.pose()
                    : history.isEmpty() ? held.logicalPose() : history.getLast().pose();
            final Vector3d plotShift = new Vector3d(replacement.logicalPose().rotationPoint()).sub(heldReference.rotationPoint());
            LINKS.put(held, new Link(replacement, isometry, plotShift));
            if (heldMatch != null && !buffer.isEmpty()) {
                final int offset = newMatch.gameTick() - heldMatch.gameTick();
                final int firstTick = buffer.getFirst().gameTick();
                for (final SubLevelSnapshotInterpolator.Snapshot snapshot : history) {
                    if (snapshot.gameTick() + offset < firstTick) {
                        final Pose3d mapped = isometry.mapPose(snapshot.pose());
                        mapped.rotationPoint().add(plotShift);
                        buffer.add(new SubLevelSnapshotInterpolator.Snapshot(snapshot.gameTick() + offset, mapped));
                        added++;
                    }
                }
                buffer.sort(java.util.Comparator.comparingInt(SubLevelSnapshotInterpolator.Snapshot::gameTick));
            }
        }
        if (heldMatch != null) {
            continueTimeline(held, replacement, isometry, newMatch.gameTick() - heldMatch.gameTick());
        }
        LOGGER.debug("[IPStraddle] client: ship {} hand-off: new copy in {} linked to the held one ({}; {} older snapshot(s) carried over{}; {} new snapshot(s))",
                held.getUniqueId(), replacement.getLevel().dimension().location(),
                heldMatch == null ? "no snapshot pair matched" : "matched tick " + heldMatch.gameTick() + " -> " + newMatch.gameTick(),
                added, droppedDuplicate ? ", duplicate start snapshot dropped" : "", interpolator.buffer.size() - added);
    }

    /**
     * The replacement takes over exactly what the held copy shows (its last and logical poses, mapped) and plays on the
     * held copy's timeline: each dimension's client interpolation clock is its own and may be stale (a dimension in which
     * no ship moved lately), so seeding the replacement from its own clock put it several ticks back or ahead (1.17.2:
     * a jump back of a third of a block, then a rush forward).
     *
     * @param offset the replacement's snapshot tick minus the held copy's for one server tick
     */
    private static void continueTimeline(final ClientSubLevel held, final ClientSubLevel replacement,
                                         final IPPortalIsometry isometry, final int offset) {
        final ClientSubLevelContainer heldContainer = ClientSubLevelContainer.getContainer((ClientLevel) held.getLevel());
        if (heldContainer == null || !(held.lastPose() instanceof final Pose3d heldLast)
                || !(replacement.lastPose() instanceof final Pose3d replacementLast)) {
            return;
        }
        final Vector3dc plotShift = LINKS.get(held).plotShift;
        final Pose3d last = isometry.mapPose(heldLast);
        last.rotationPoint().add(plotShift);
        final Pose3d logical = isometry.mapPose(held.logicalPose());
        logical.rotationPoint().add(plotShift);
        replacementLast.set(last);
        replacement.logicalPose().set(logical);
        ((IPClientSubLevelExt) replacement).ip_invalidateRenderPose();
        TIMELINES.put(replacement, new Timeline(heldContainer.getInterpolation().mostRecentInterpolationTick + offset));
    }

    /**
     * @return the tick {@code ship}'s interpolator samples at this tick: {@code pointer} (its dimension's), or for a new
     * copy just handed off, its old copy's timeline, moved towards {@code pointer} by {@value #TIMELINE_CONVERGENCE} per
     * tick until they meet
     */
    public static double adjustPointer(final ClientSubLevel ship, final double pointer) {
        if (TIMELINES.isEmpty()) {
            return pointer;
        }
        final Timeline timeline = TIMELINES.get(ship);
        if (timeline == null) {
            return pointer;
        }
        timeline.pointer += 1.0;
        final double gap = pointer - timeline.pointer;
        if (Math.abs(gap) > TIMELINE_REACH) {
            if (++timeline.unreachableTicks > TIMELINE_MAX_UNREACHABLE_TICKS) {
                TIMELINES.remove(ship);
                return pointer;
            }
            return timeline.pointer;
        }
        timeline.pointer += Math.max(-TIMELINE_CONVERGENCE, Math.min(TIMELINE_CONVERGENCE, gap));
        if (Math.abs(pointer - timeline.pointer) < 1.0e-6) {
            TIMELINES.remove(ship);
            LOGGER.debug("[IPStraddle] client: ship {} back on its own dimension's interpolation clock", ship.getUniqueId());
        }
        return timeline.pointer;
    }

    private static boolean samePose(final Pose3dc a, final Pose3dc b) {
        return a.position().distanceSquared(b.position()) <= 1.0e-12 && Math.abs(a.orientation().dot(b.orientation())) >= 1.0 - 1.0e-12;
    }

    /**
     * @return the pose to draw a held copy with: its replacement's, mapped back through the portal; null when
     * {@code ship} is not a linked held copy
     */
    @Nullable
    public static Pose3dc followedPose(final ClientSubLevel ship, final float partialTick, final boolean currentFrame) {
        if (LINKS.isEmpty()) {
            return null;
        }
        final Link link = LINKS.get(ship);
        if (link == null || link.replacement.isRemoved() || link.replacement == ship) {
            return null;
        }
        if (link.cachedPartialTick == partialTick) {
            return link.cached;
        }
        final Pose3dc replacementPose = currentFrame ? link.replacement.renderPose() : link.replacement.renderPose(partialTick);
        final Pose3d pose = link.isometry.unmapPose(replacementPose);
        pose.rotationPoint().sub(link.plotShift);
        link.cached.set(pose);
        link.cachedPartialTick = partialTick;
        link.followedFrames++;
        return link.cached;
    }

    /**
     * @return whether a copy of {@code ship} in another dimension has compiled meshes
     */
    private static boolean replacementReady(final ClientSubLevel ship) {
        for (final ClientLevel level : ClientWorldLoader.getClientWorlds()) {
            if (level == ship.getLevel()) {
                continue;
            }
            final SubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }
            final SubLevel other = container.getSubLevel(ship.getUniqueId());
            if (other instanceof final ClientSubLevel copy && !copy.isRemoved() && isDrawable(copy)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDrawable(final ClientSubLevel ship) {
        final SubLevelRenderData data = ship.getRenderData();
        if (!(data instanceof final VanillaChunkedSubLevelRenderData chunked)) {
            return data != null;
        }
        boolean any = false;
        for (final SectionRenderDispatcher.RenderSection section : chunked.allRenderSections()) {
            if (section.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                return false;
            }
            any = true;
        }
        return any;
    }
}
