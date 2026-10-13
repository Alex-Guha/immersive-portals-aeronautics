/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The substep loop follows IPSable 0.5.0's IplFusedStep by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.sable_physics;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.object.ArbitraryPhysicsObject;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import dev.ryanhcode.sable.platform.SableEventPublishPlatform;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import ipaeronautics.rapier.RapierPhysicsPipeline;
import net.minecraft.CrashReport;
import net.minecraft.CrashReportCategory;
import net.minecraft.ReportedException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.mixin_sable.IPSubLevelContainerAccessor;
import qouteall.imm_ptl.core.compat.mixin_sable.IPSubLevelPhysicsSystemAccessor;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * The fused physics step (docs/lessons/physics-backend.md). With our ipa_rapier backend every level's pipeline is a
 * chart of one shared native world, which must be stepped once per substep for all levels together, with every
 * level's forces applied before that step and every level's poses read after it.
 * <p>
 * Sable runs each level's sub-level container tick at the head of that level's own tick: the sub-levels' own ticks
 * (bounding boxes, extreme-Y removal), queued removals, then the observers (the physics system first: tickets,
 * {@code lastPose}, actors, all substeps; then tracking, ticket loading, Aeronautics' balloons, ...), then the
 * holding chunk map. Here the first such container tick in a server tick runs every level's container tick, in
 * {@code getAllLevels()} order, phase by phase:
 * <ol>
 *     <li>every level's sub-level ticks and removals;</li>
 *     <li>every level's physics system tick, its substep loop ({@code tickPipelinePhysics}) collected instead of run,
 *     then the collected loops interleaved, one native world step per substep (armed on the last pipeline, so every
 *     chart's contraption poses are uploaded first);</li>
 *     <li>every level's remaining observers and holding chunk map.</li>
 * </ol>
 * The other levels' own container ticks are skipped. So each level still sees exactly Sable's order (a fresh
 * sub-level's bounding box is set before the physics tickets read it; {@code lastPose} is taken right before its
 * physics, which Sable uses for ship velocity and to carry entities standing on ships). Only the moment changes: a
 * non-first level's Sable tick runs at the head of the first level's tick in the same server tick, before any
 * level's gameplay, instead of at the head of its own; forces queued by gameplay land in the next tick either way.
 * <p>
 * A paused level skips its physics phases, but its bodies keep moving with the shared world (logged once).
 */
public final class IPFusedPhysicsStep {
    private static final Logger LOGGER = LogUtils.getLogger();

    private record Entry(ServerLevel level, SubLevelPhysicsSystem system, ServerSubLevelContainer container) {
    }

    private static final List<Entry> COLLECTED = new ArrayList<>();
    private static boolean driving;
    // Weak: singleplayer starts a new server per world in the same JVM, with tick counts restarting at 0.
    private static WeakReference<MinecraftServer> lastServer = new WeakReference<>(null);
    private static int lastTick = -1;
    private static boolean loggedPaused;
    private static boolean loggedSubsteps;

    private IPFusedPhysicsStep() {
    }

    /**
     * Head of {@code ServerSubLevelContainer.tick}.
     *
     * @return true to skip that tick (it ran, or runs now, inside the fused step)
     */
    public static boolean onContainerTick(final ServerSubLevelContainer container) {
        if (driving || !IPPhysicsPipelineProvider.isOwnBackendActive()) {
            return false;
        }

        final MinecraftServer server = container.getLevel().getServer();
        final int tick = server.getTickCount();
        if (server == lastServer.get() && tick == lastTick) {
            return true;
        }
        lastServer = new WeakReference<>(server);
        lastTick = tick;

        drive(server);
        return true;
    }

    /**
     * Head of {@code SubLevelPhysicsSystem.tickPipelinePhysics}.
     *
     * @return true to skip it: it runs later, interleaved with the other levels' loops
     */
    public static boolean collect(final SubLevelPhysicsSystem system, final ServerSubLevelContainer container) {
        if (!driving) {
            return false;
        }
        COLLECTED.add(new Entry(system.getLevel(), system, container));
        return true;
    }

    private static void drive(final MinecraftServer server) {
        driving = true;
        COLLECTED.clear();
        try {
            final List<ServerSubLevelContainer> containers = new ArrayList<>();
            for (final ServerLevel level : server.getAllLevels()) {
                final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container != null) {
                    containers.add(container);
                }
            }

            // Phase 1, as in SubLevelContainer.tick: the sub-levels' own ticks, then queued removals.
            for (final ServerSubLevelContainer container : containers) {
                container.getAllSubLevels().forEach(SubLevel::tick);
                container.processSubLevelRemovals();
            }

            // Phase 2: every level's physics system tick (substep loops collected), then the fused substeps.
            boolean anyPaused = false;
            for (final ServerSubLevelContainer container : containers) {
                for (final SubLevelObserver observer : observers(container)) {
                    if (observer instanceof final SubLevelPhysicsSystem system) {
                        anyPaused |= system.getPaused();
                        system.tick(container);
                    }
                }
            }

            if (anyPaused && !loggedPaused) {
                loggedPaused = true;
                LOGGER.info("[IPPhysics] a dimension has physics paused: its phases are skipped, but its bodies keep "
                        + "moving with the shared physics world (logged once)");
            }

            if (!COLLECTED.isEmpty()) {
                stepAll(List.copyOf(COLLECTED));
            }

            // Phase 3: the remaining observers in their order, then ServerSubLevelContainer.tick's holding map.
            for (final ServerSubLevelContainer container : containers) {
                for (final SubLevelObserver observer : observers(container)) {
                    if (!(observer instanceof SubLevelPhysicsSystem)) {
                        observer.tick(container);
                    }
                }
                container.getHoldingChunkMap().processChanges();
            }
        } finally {
            COLLECTED.clear();
            driving = false;
        }
    }

    private static List<SubLevelObserver> observers(final ServerSubLevelContainer container) {
        // A copy: an observer may add or remove observers while ticking.
        return List.copyOf(((IPSubLevelContainerAccessor) container).ip_getObservers());
    }

    private static void stepAll(final List<Entry> entries) {
        final int substeps = entries.get(0).system().getConfig().substepsPerTick;
        if (!loggedSubsteps) {
            for (final Entry entry : entries) {
                if (entry.system().getConfig().substepsPerTick != substeps) {
                    loggedSubsteps = true;
                    LOGGER.warn("[IPPhysics] substeps per tick differ between dimensions; all use {} from {} (logged once)",
                            substeps, entries.get(0).level().dimension().location());
                    break;
                }
            }
        }
        final double timeStep = 1.0 / 20.0 / substeps;

        // The world step goes with the last of our pipelines (all of them, unless a level kept Sable's own).
        int armed = -1;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).system().getPipeline() instanceof RapierPhysicsPipeline) {
                armed = i;
            }
        }
        final int armedIndex = armed;

        for (final Entry entry : entries) {
            guarded(entry, () -> entry.system().getPipeline().prePhysicsTicks());
        }

        for (int substep = 0; substep < substeps; substep++) {
            for (final Entry entry : entries) {
                // As in Sable: currentSubstep is set before the phases (getPartialPhysicsTick reads it).
                ((IPSubLevelPhysicsSystemAccessor) entry.system()).ip_setCurrentSubstep(substep);
                guarded(entry, () -> preSubstep(entry, timeStep));
            }

            SubLevelPhysicsSystem.IN_PHYSICS_STEP = true;
            try {
                for (int i = 0; i < entries.size(); i++) {
                    final Entry entry = entries.get(i);
                    final boolean last = i == armedIndex;
                    guarded(entry, () -> {
                        RapierPhysicsPipeline.stepWorld = last;
                        try {
                            entry.system().getPipeline().physicsTick(timeStep);
                        } finally {
                            RapierPhysicsPipeline.stepWorld = true;
                        }
                    });
                }
            } finally {
                SubLevelPhysicsSystem.IN_PHYSICS_STEP = false;
            }

            for (final Entry entry : entries) {
                guarded(entry, () -> postSubstep(entry, timeStep));
            }
        }

        for (final Entry entry : entries) {
            guarded(entry, () -> entry.system().getPipeline().postPhysicsTicks());
            ((IPSubLevelPhysicsSystemAccessor) entry.system()).ip_setCurrentSubstep(substeps);
        }
    }

    /**
     * The first half of one iteration of Sable's {@code tickPipelinePhysics} loop, up to the pipeline step.
     */
    private static void preSubstep(final Entry entry, final double timeStep) {
        final SubLevelPhysicsSystem system = entry.system();
        final ServerSubLevelContainer container = entry.container();

        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            subLevel.prePhysicsTickBegin();
        }

        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            subLevel.updateMergedMassData((float) system.getPartialPhysicsTick());
        }

        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            subLevel.prePhysicsTick(system, system.getPhysicsHandle(subLevel), timeStep);
        }

        SableEventPublishPlatform.INSTANCE.prePhysicsTick(system, timeStep);

        for (final ServerSubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel.isRemoved()) continue;
            subLevel.applyQueuedForces(system, system.getPhysicsHandle(subLevel), timeStep);
        }
    }

    /**
     * The second half of one iteration of Sable's {@code tickPipelinePhysics} loop, after the pipeline step.
     */
    private static void postSubstep(final Entry entry, final double timeStep) {
        final SubLevelPhysicsSystem system = entry.system();
        final IPSubLevelPhysicsSystemAccessor access = (IPSubLevelPhysicsSystemAccessor) system;

        entry.container().processSubLevelRemovals();
        access.ip_updateAllPoses(entry.container());

        final Collection<ArbitraryPhysicsObject> wakeUps = access.ip_getQueuedWakeUps();
        for (final ArbitraryPhysicsObject object : wakeUps) {
            object.wakeUp();
        }
        wakeUps.clear();

        SableEventPublishPlatform.INSTANCE.postPhysicsTick(system, timeStep);
    }

    /**
     * Sable's crash report for its physics tick, naming the level whose phase failed.
     */
    private static void guarded(final Entry entry, final Runnable phase) {
        SubLevelPhysicsSystem.currentlySteppingSystem = entry.system();
        try {
            phase.run();
        } catch (final Exception e) {
            final CrashReport crashReport = CrashReport.forThrowable(e, "Sable ticking physics (fused step)");
            final CrashReportCategory category = crashReport.addCategory("Current physics state");
            category.setDetail("Dimension", entry.level().dimension());
            throw new ReportedException(crashReport);
        } finally {
            SubLevelPhysicsSystem.currentlySteppingSystem = null;
        }
    }
}
