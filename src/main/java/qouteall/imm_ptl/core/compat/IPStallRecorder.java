/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat;

import com.mojang.logging.LogUtils;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import net.minecraft.server.MinecraftServer;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.server.ServerLifecycleHooks;
import org.slf4j.Logger;

/**
 * Logs what the game is doing when it stops. A freeze leaves nothing in the log, and one that never ends is killed from
 * outside, so a thread that has not ticked for {@value #STALL_MS} ms gets its stack logged by a daemon of ours, with the
 * other main thread's and the heap, and again every {@value #REPEAT_MS} ms while it lasts ({@code [IPStall]}).
 * On in branch test jars (a version with a suffix) and with {@code -Dip_aeronautics.debug.stalls=true}.
 */
public final class IPStallRecorder {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final long STALL_MS = 3000;
    private static final long REPEAT_MS = 5000;
    private static final int MAX_REPORTS = 8;
    private static final int MAX_FRAMES = 70;
    private static final ConcurrentHashMap<Thread, Long> LAST_BEAT = new ConcurrentHashMap<>();

    private IPStallRecorder() {
    }

    private static boolean isOn() {
        return Boolean.getBoolean("ip_aeronautics.debug.stalls") || isTestJar();
    }

    /** A branch test jar: a version with a suffix ({@code 2.8.8.1-seat-sync}). */
    public static boolean isTestJar() {
        return ModList.get().getModContainerById("ip_aeronautics").map(mod -> mod.getModInfo().getVersion().toString().contains("-")).orElse(false);
    }

    public static void init() {
        if (!isOn()) {
            return;
        }
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> beat());
        final Thread daemon = new Thread(IPStallRecorder::watch, "IP stall recorder");
        daemon.setDaemon(true);
        daemon.start();
        LOGGER.info("[IPStall] recording stalls of over {} ms", STALL_MS);
    }

    /** The calling thread is alive (once a tick). */
    public static void beat() {
        LAST_BEAT.put(Thread.currentThread(), System.currentTimeMillis());
    }

    private static void watch() {
        final ConcurrentHashMap<Thread, Long> lastReport = new ConcurrentHashMap<>();
        final ConcurrentHashMap<Thread, Integer> reports = new ConcurrentHashMap<>();
        while (true) {
            try {
                Thread.sleep(500);
                final long now = System.currentTimeMillis();
                for (final var entry : LAST_BEAT.entrySet()) {
                    final Thread thread = entry.getKey();
                    if (!thread.isAlive()) {
                        LAST_BEAT.remove(thread);
                        continue;
                    }
                    final long stalled = now - entry.getValue();
                    if (stalled < STALL_MS || isPausedServer(thread)) {
                        reports.remove(thread);
                        continue;
                    }
                    if (now - lastReport.getOrDefault(thread, 0L) < REPEAT_MS || isIdle(thread) || reports.merge(thread, 1, Integer::sum) > MAX_REPORTS) {
                        continue;
                    }
                    lastReport.put(thread, now);
                    report(thread, stalled);
                }
            } catch (final InterruptedException e) {
                return;
            } catch (final Throwable t) {
                LOGGER.warn("[IPStall] stopped: {}", t.toString());
                return;
            }
        }
    }

    /** A singleplayer server stops ticking while the game is paused. */
    private static boolean isPausedServer(final Thread thread) {
        final MinecraftServer server = ServerLifecycleHooks.getCurrentServer();
        return server != null && server.getRunningThread() == thread && server.isPaused();
    }

    /** A server waiting for its next tick has simply nothing to do. */
    private static boolean isIdle(final Thread thread) {
        final Thread.State state = thread.getState();
        return (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) && Arrays.stream(thread.getStackTrace())
            .limit(14).anyMatch(frame -> frame.getMethodName().equals("waitUntilNextTick") || frame.getMethodName().equals("managedBlock"));
    }

    private static void report(final Thread stalled, final long ms) {
        final StringBuilder text = new StringBuilder();
        final Runtime runtime = Runtime.getRuntime();
        final long gcMs = ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(bean -> Math.max(0, bean.getCollectionTime())).sum();
        text.append(String.format("%s has not ticked for %d ms; heap %d of %d MB, %d ms in GC so far", stalled.getName(), ms,
            (runtime.totalMemory() - runtime.freeMemory()) / (1 << 20), runtime.maxMemory() / (1 << 20), gcMs));
        append(text, stalled);
        for (final Thread other : LAST_BEAT.keySet()) {
            if (other != stalled) {
                append(text, other);
            }
        }
        LOGGER.warn("[IPStall] {}", text);
    }

    private static void append(final StringBuilder text, final Thread thread) {
        text.append("\n  ").append(thread.getName()).append(' ').append(thread.getState()).append(":\n")
            .append(Arrays.stream(thread.getStackTrace()).limit(MAX_FRAMES).map(frame -> "    at " + frame).collect(Collectors.joining("\n")));
    }
}
