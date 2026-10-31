/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import de.nick1st.imm_ptl.events.ClientCleanupEvent;
import dev.engine_room.flywheel.lib.visualization.VisualizationHelper;
import dev.ryanhcode.sable.api.sublevel.ClientSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.render.SubLevelRenderData;
import dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Client-side render watchdog for Sable ships (ticked from {@code MixinSableContainerTick_RenderWatchdog}).
 * On {@code ClientboundFinalizeSubLevelPacket} schedules one render rebuild {@value #FINALIZE_REBUILD_DELAY_TICKS}
 * ticks later (retrying if the plot isn't present yet); every {@value #SWEEP_INTERVAL_TICKS} ticks samples each
 * plot's non-empty sections and "heals" plots whose count of uncompiled sections has stalled; each change of
 * that count is logged per ship.
 * Rebuilds mark the existing sections dirty ({@code SubLevelRenderData.rebuild()}) instead of recreating the
 * render data, so the old meshes stay on screen until the new ones are compiled (no blink). Refreshes also
 * queue Flywheel visual rebuilds for plot entities. Also the caller of
 * {@link IPSablePortalSync#tickPortalSideWorlds()} on every container tick.
 */
public class IPShipRenderWatchdog {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final Object LOCK = new Object();
    private static final List<PendingRefresh> PENDING = new ArrayList<>();
    private static final int FINALIZE_RETRIES = 10;
    private static final int FINALIZE_REBUILD_DELAY_TICKS = 40;
    private static final int FINALIZE_RETRY_DELAY_TICKS = 20;
    private static final int SWEEP_INTERVAL_TICKS = 20;
    private static final int STABLE_SAMPLES = 3;
    private static final int HEAL_COOLDOWN_SWEEPS = 10;
    private static final int MAX_HEALS = 8;
    private static final int MAX_SECTIONS_SCANNED = 8192;
    private static final Map<Object, IPShipRenderWatchdog.ContainerState> SWEEP_STATE = new IdentityHashMap<>();
    /** Levels whose Flywheel manager was created since their container last ticked (weak: levels unload). */
    private static final Set<Object> FLYWHEEL_RESETS = Collections.newSetFromMap(new WeakHashMap<>());

    /** The client left its worlds: their containers must not stay reachable from here. */
    public static void init() {
        NeoForge.EVENT_BUS.addListener(ClientCleanupEvent.class, event -> {
            synchronized (LOCK) {
                SWEEP_STATE.clear();
            }
        });
    }

    public static void onFinalized(Object level, long plotCoordinate) {
        try {
            synchronized (LOCK) {
                PENDING.add(new PendingRefresh(level, plotCoordinate, FINALIZE_REBUILD_DELAY_TICKS, FINALIZE_RETRIES));
            }
        } catch (Throwable ignored) {
        }
    }

    /**
     * Flywheel made a new manager for {@code level} (any thread): rebuilds every ship of it at its container's next
     * tick. Ship sections compiled while the old manager held their parts' visuals do not draw those parts themselves,
     * so after Flywheel reset the level (its backend switched on or off with a shader pack, F3+A, IP reloading a
     * world's renderer) they were drawn by neither until the ship was synced again (BUGS #8). Recompiling hands them
     * to the new manager, or to their renderer when Flywheel cannot take them in that compile.
     */
    public static void onFlywheelManagerCreated(Object level) {
        synchronized (LOCK) {
            FLYWHEEL_RESETS.add(level);
        }
    }

    public static void tick(Object containerObj) {
        try {
            if (!(containerObj instanceof ClientSubLevelContainer)) {
                return;
            }

            IPSablePortalSync.tickPortalSideWorlds();
            SubLevelContainer container = (SubLevelContainer) containerObj;
            ArrayList<PendingRefresh> due = null;
            boolean flywheelReset;
            synchronized (LOCK) {
                flywheelReset = FLYWHEEL_RESETS.remove(container.getLevel());
                if (!PENDING.isEmpty()) {
                    Iterator<PendingRefresh> iterator = PENDING.iterator();

                    while (iterator.hasNext()) {
                        PendingRefresh entry = iterator.next();
                        // Immersive Aeronautics: plot coordinates repeat across dimensions; only the
                        // container of the level the finalize arrived for counts down and applies the entry
                        Object entryLevel = entry.level.get();
                        if (entryLevel == null) {
                            iterator.remove();
                            continue;
                        }
                        if (entryLevel != container.getLevel()) {
                            continue;
                        }
                        entry.ticksLeft--;
                        if (entry.ticksLeft <= 0) {
                            if (due == null) {
                                due = new ArrayList<>();
                            }

                            due.add(entry);
                            iterator.remove();
                        }
                    }
                }
            }

            if (flywheelReset) {
                int ships = 0;
                for (SubLevel subLevel : container.getAllSubLevels()) {
                    if (subLevel instanceof ClientSubLevel clientSubLevel && !subLevel.isRemoved() && clientSubLevel.isFinalized()) {
                        ChunkPos plotPos = clientSubLevel.getPlot().plotPos;
                        refresh(container, clientSubLevel, plotPos.x, plotPos.z, "new Flywheel manager");
                        ships++;
                    }
                }
                if (ships > 0) {
                    LOGGER.info("[IPShipRenderWatchdog] Flywheel reset {}: rebuilt {} ship(s) so their parts get visuals again",
                        container.getLevel().dimension().location(), ships);
                }
            }

            if (due != null) {
                for (PendingRefresh entry : due) {
                    int plotX = ChunkPos.getX(entry.plotCoordinate);
                    int plotZ = ChunkPos.getZ(entry.plotCoordinate);
                    SubLevel subLevel = container.getSubLevel(plotX, plotZ);
                    if (subLevel instanceof ClientSubLevel clientSubLevel && !subLevel.isRemoved()) {
                        refresh(container, clientSubLevel, plotX, plotZ, "scheduled");
                    } else if (entry.retriesLeft > 0) {
                        // plot not present yet: try again shortly
                        synchronized (LOCK) {
                            PENDING.add(new PendingRefresh(entry.level.get(), entry.plotCoordinate, FINALIZE_RETRY_DELAY_TICKS, entry.retriesLeft - 1));
                        }
                    }
                }
            }

            sweep(container);
        } catch (Throwable e) {
            LOGGER.warn("[IPShipRenderWatchdog] error: {}", e.toString());
        }
    }

    private static void refresh(SubLevelContainer container, ClientSubLevel subLevel, int plotX, int plotZ, String reason) {
        SubLevelRenderData renderData = subLevel.getRenderData();
        if (renderData != null) {
            // marks every section dirty; compiled meshes stay visible until replaced
            renderData.rebuild();
        } else {
            subLevel.updateRenderData();
        }
        LOGGER.debug("[IPShipRenderWatchdog] rebuilt render data for plot {}, {} ({})", plotX, plotZ, reason);

        try {
            IPShipRenderWatchdog.FlwRefresh.refreshEntities(container, plotX, plotZ);
        } catch (Throwable ignored) {
        }
    }

    private static void sweep(SubLevelContainer container) {
        IPShipRenderWatchdog.ContainerState state;
        synchronized (LOCK) {
            state = SWEEP_STATE.get(container);
            if (state == null) {
                state = new IPShipRenderWatchdog.ContainerState();
                SWEEP_STATE.put(container, state);
            }

            if (++state.timer < SWEEP_INTERVAL_TICKS) {
                return;
            }

            state.timer = 0;
        }

        HashSet<Long> livePlots = new HashSet<>();

        for (SubLevel subLevel : container.getAllSubLevels()) {
            try {
                if (subLevel instanceof ClientSubLevel clientSubLevel && !subLevel.isRemoved() && clientSubLevel.isFinalized()) {
                    ChunkPos plotPos = clientSubLevel.getPlot().plotPos;
                    long plotKey = ChunkPos.asLong(plotPos.x, plotPos.z);
                    livePlots.add(plotKey);
                    IPShipRenderWatchdog.PlotState plotState = state.plots.get(plotKey);
                    if (plotState == null) {
                        plotState = new IPShipRenderWatchdog.PlotState();
                        state.plots.put(plotKey, plotState);
                    }

                    // Immersive Aeronautics: counted from the render data's own sections and the level's chunks
                    // (the fork's plot-chunk lookup found nothing on the client); changes are logged per ship
                    int[] counts = countSectionStates(clientSubLevel);
                    if (counts[0] != plotState.loggedNonAir || counts[1] != plotState.loggedUncompiled) {
                        plotState.loggedNonAir = counts[0];
                        plotState.loggedUncompiled = counts[1];
                        LOGGER.debug(
                            "[IPShipRenderWatchdog] ship {} in {}: {} non-air section(s), {} uncompiled",
                            subLevel.getUniqueId(), container.getLevel().dimension().location(), counts[0], counts[1]
                        );
                    }
                    if (plotState.cooldown > 0) {
                        plotState.cooldown--;
                        plotState.lastUncompiled = counts[1];
                    } else {
                        int uncompiled = counts[1];
                        if (uncompiled <= 0) {
                            plotState.lastUncompiled = 0;
                            plotState.stable = 0;
                            plotState.heals = 0;
                        } else if (uncompiled == plotState.lastUncompiled) {
                            plotState.stable++;
                            plotState.lastUncompiled = uncompiled;
                            if (plotState.stable >= STABLE_SAMPLES && plotState.heals < MAX_HEALS) {
                                plotState.heals++;
                                plotState.stable = 0;
                                plotState.cooldown = HEAL_COOLDOWN_SWEEPS;
                                refresh(container, clientSubLevel, plotPos.x, plotPos.z, "stuck: " + uncompiled + " uncompiled section(s), heal " + plotState.heals + "/" + MAX_HEALS);
                            }
                        } else {
                            plotState.stable = 0;
                            plotState.heals = 0;
                            plotState.lastUncompiled = uncompiled;
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        if (state.plots.size() > livePlots.size()) {
            state.plots.keySet().retainAll(livePlots);
        }
    }

    /**
     * {non-air sections, uncompiled non-air sections} of a ship, read from its render data's own sections and
     * the level's chunks, i.e. what the compiler sees.
     */
    private static int[] countSectionStates(ClientSubLevel subLevel) {
        int[] counts = new int[2];
        ClientLevel level = subLevel.getLevel();
        if (!(subLevel.getRenderData() instanceof VanillaChunkedSubLevelRenderData chunked) || level == null) {
            return counts;
        }
        int scanned = 0;
        for (SectionRenderDispatcher.RenderSection section : chunked.allRenderSections()) {
            if (++scanned > MAX_SECTIONS_SCANNED) {
                break;
            }
            BlockPos origin = section.getOrigin();
            LevelChunk chunk = level.getChunk(origin.getX() >> 4, origin.getZ() >> 4);
            int sectionIndex = chunk.getSectionIndex(origin.getY());
            if (sectionIndex < 0 || sectionIndex >= chunk.getSectionsCount() || chunk.getSection(sectionIndex).hasOnlyAir()) {
                continue;
            }
            counts[0]++;
            if (section.getCompiled() == SectionRenderDispatcher.CompiledSection.UNCOMPILED) {
                counts[1]++;
            }
        }
        return counts;
    }

    private static final class ContainerState {
        int timer;
        final Map<Long, IPShipRenderWatchdog.PlotState> plots = new HashMap<>();
    }

    private static class FlwRefresh {
        static void refreshEntities(SubLevelContainer container, int plotX, int plotZ) {
            SubLevel subLevel = container.getSubLevel(plotX, plotZ);
            if (subLevel != null) {
                LevelPlot plot = subLevel.getPlot();
                if (container.getLevel() instanceof ClientLevel clientLevel) {
                    int queued = 0;

                    for (Entity entity : clientLevel.entitiesForRendering()) {
                        ChunkPos chunkPos = entity.chunkPosition();
                        if (IPSableCompatHelper.isPlotSpaceChunk(chunkPos) && container.getPlot(chunkPos) == plot) {
                            VisualizationHelper.queueUpdate(entity);
                            queued++;
                        }
                    }

                    if (queued > 0) {
                        LOGGER.debug("[IPShipRenderWatchdog] queued flywheel visual rebuild for {} plot entities at {}, {}", queued, plotX, plotZ);
                    }
                }
            }
        }
    }

    /** A scheduled {@code updateRenderData()} for a plot, counting down in container ticks. */
    private static final class PendingRefresh {
        final WeakReference<Object> level;
        final long plotCoordinate;
        int ticksLeft;
        final int retriesLeft;

        PendingRefresh(Object level, long plotCoordinate, int ticksLeft, int retriesLeft) {
            this.level = new WeakReference<>(level);
            this.plotCoordinate = plotCoordinate;
            this.ticksLeft = ticksLeft;
            this.retriesLeft = retriesLeft;
        }
    }

    private static final class PlotState {
        int lastUncompiled = -1;
        int loggedNonAir = -1;
        int loggedUncompiled = -1;
        int stable;
        int heals;
        int cooldown;
    }
}
