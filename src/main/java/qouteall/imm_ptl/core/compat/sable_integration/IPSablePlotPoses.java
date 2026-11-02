/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectMaps;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.Nullable;

/**
 * Sable keeps one table of ship render poses for Flywheel's contraption visuals, by plot number
 * ({@code FlywheelCompatNeoForge.RENDER_POSES}), and at each level's Flywheel frame drops every entry that level has no
 * ship for. IP runs a Flywheel frame for every level on screen, so a portal view dropped the other level's entries.
 * They only come back when an entity joins the level, and a contraption with no entry is drawn at its plot coordinates
 * without the ship's pose: a carriage on a ship past a portal vanished, and plot numbers are per level, so ships in two
 * levels could also take each other's entries. Here each level has its own table.
 * <p>
 * {@code getInfo} is not told the level. The visual asking says which it is ({@link #enterVisual}); visuals run on
 * Flywheel's workers, so that is per thread.
 */
public final class IPSablePlotPoses {
    private static final Map<Level, Long2ObjectMap<Object>> TABLES = Collections.synchronizedMap(new WeakHashMap<>());
    private static final ThreadLocal<Level> VISUAL_LEVEL = new ThreadLocal<>();

    private IPSablePlotPoses() {
    }

    /** A contraption visual of {@code level} is about to ask for its ship's pose on this thread. */
    public static void enterVisual(final Level level) {
        VISUAL_LEVEL.set(level);
    }

    /** The level of the visual running on this thread; else the level Flywheel is serving ({@code Minecraft.level}). */
    public static @Nullable Level visualLevel() {
        final Level level = VISUAL_LEVEL.get();
        return level != null ? level : Minecraft.getInstance().level;
    }

    @SuppressWarnings("unchecked")
    public static <T> Long2ObjectMap<T> tableOf(final Level level) {
        return (Long2ObjectMap<T>) TABLES.computeIfAbsent(level, l -> Long2ObjectMaps.synchronize(new Long2ObjectOpenHashMap<>()));
    }
}
