/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * Let It Walk's walkers and ship moves into another plot.
 * <p>
 * A walker is a body ship with the walker core block entity and one ship per leg segment. The core saves, per leg,
 * where each joint sits on its parent and child ship and where each segment sits relative to the core, in those
 * ships' plot coordinates, and where each foot stands, in world coordinates (in the walker's surface frame while it
 * climbs or is scaled). Sable never moves a ship to another plot, so Let It Walk never updates them; our crossings
 * do, and the core then rebuilds its joint filters at the old plots, which Sable refuses by throwing (a crash), or
 * poses its legs thousands of blocks away. A broken core's limp legs ({@code WalkerRemnants}, saved per level by body
 * ship) are rebuilt from the same plot positions.
 * <p>
 * So after every move, once all ships moving together are loaded ({@link IPShipJoints#shiftLoadedJoints}), each core
 * on them has its plot positions moved by each ship's shift with Let It Walk's own {@code WalkerCopy.remapCore} (what
 * its schematic paste uses) and its feet and climbing surface mapped as the body moved; a body's limp legs follow it
 * to its new level. How the body moved is noted before it leaves ({@link #noteMove}). Everything goes through Let It
 * Walk's public classes by reflection (the limp legs through its private per-level data), so we build without it.
 */
public final class IPWalkerCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "let_it_walk";
    private static final String CORE_CLASS = "dev.letitwalk.content.WalkerCoreBlockEntity";
    private static final String COPY_CLASS = "dev.letitwalk.walker.WalkerCopy";
    private static final String REMNANTS_CLASS = "dev.letitwalk.walker.WalkerRemnants";
    private static final String DEMO_CLASS = "dev.letitwalk.demo.DemoWalkers";
    private static final String[] FOOT_KEYS = {"a", "f", "t"};
    /** A note older than this is from a move that never reached the fix-up (it failed before). */
    private static final long NOTE_TICKS = 100;
    /** Test switch: leave walkers as Let It Walk alone leaves them, to show what the fix-up prevents. */
    private static final boolean DISABLED = Boolean.getBoolean("ip_aeronautics.debug.noWalkerFix");

    private static volatile boolean resolved;
    private static volatile boolean usable;
    private static volatile boolean warned;
    @Nullable
    private static Class<?> coreClass;
    @Nullable
    private static Method saveLegs;
    @Nullable
    private static Method loadLegs;
    @Nullable
    private static Field surfaceUp;
    @Nullable
    private static Field toSurface;
    @Nullable
    private static Method remapCore;
    @Nullable
    private static Method remnantsOf;
    @Nullable
    private static Field remnantEntries;

    /** How a ship moved: its pose before, and after with its plot not yet shifted (as {@code mapSavedStrands} takes them). */
    private record Note(ResourceKey<Level> from, Pose3d before, Pose3d after, long tick) {
    }

    private static final Map<UUID, Note> NOTES = new HashMap<>();

    private IPWalkerCompat() {
    }

    /**
     * A ship leaves {@code from} for a pose {@code after} (its plot not yet shifted); call before it is removed.
     * Restoring a ship to where it was notes {@code before} as {@code after}.
     */
    public static void noteMove(ServerLevel from, UUID ship, Pose3dc before, Pose3dc after) {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return;
        }
        long tick = from.getServer().getTickCount();
        synchronized (NOTES) {
            NOTES.values().removeIf(note -> tick - note.tick() > NOTE_TICKS);
            NOTES.put(ship, new Note(from.dimension(), new Pose3d(before), new Pose3d(after), tick));
        }
    }

    /**
     * After the ships {@code shifts} names (ship to plot shift) were loaded into {@code level}, all of them: points every
     * walker core on them at the moved plots, and moves their limp legs' records along. A ship without a note (recovered
     * from the teleport journal) keeps its plot positions moved, but its feet are lifted, to be put down afresh.
     */
    public static void shiftLoadedWalkers(ServerLevel level, Map<UUID, BlockPos> shifts) {
        if (DISABLED || shifts.isEmpty() || !ModList.get().isLoaded(MOD_ID) || !resolve()) {
            return;
        }
        Map<UUID, Note> notes = new HashMap<>();
        synchronized (NOTES) {
            for (UUID id : shifts.keySet()) {
                Note note = NOTES.remove(id);
                if (note != null) {
                    notes.put(id, note);
                }
            }
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        // a ship not in this move has not moved: zero, so remapCore keeps its legs (it drops a leg naming an unknown ship)
        Function<UUID, Vec3> shiftOf = id -> Vec3.atLowerCornerOf(shifts.getOrDefault(id, BlockPos.ZERO));
        Function<UUID, UUID> sameId = id -> id;
        try {
            for (Map.Entry<UUID, BlockPos> moved : shifts.entrySet()) {
                if (container.getSubLevel(moved.getKey()) instanceof ServerSubLevel ship) {
                    for (BlockEntity blockEntity : blockEntities(ship)) {
                        if (coreClass.isInstance(blockEntity)) {
                            shiftCore(level, ship, blockEntity, notes.get(moved.getKey()), shiftOf, sameId);
                        }
                    }
                }
                Note note = notes.get(moved.getKey());
                if (note != null) {
                    moveRemnant(level, moved.getKey(), note, moved.getValue(), shiftOf, sameId);
                }
            }
        } catch (Throwable e) {
            warnOnce("pointing walkers at moved plots failed", e);
        }
    }

    private static void shiftCore(ServerLevel level, ServerSubLevel ship, BlockEntity core, @Nullable Note note,
                                  Function<UUID, Vec3> shiftOf, Function<UUID, UUID> sameId) throws ReflectiveOperationException {
        CompoundTag tag = new CompoundTag();
        tag.put("legs", (ListTag) saveLegs.invoke(core));
        remapCore.invoke(null, tag, shiftOf, sameId);
        ListTag legs = tag.getList("legs", Tag.TAG_COMPOUND);
        Vector3d up = (Vector3d) surfaceUp.get(core);
        Quaterniond frame = (Quaterniond) toSurface.get(core);
        if (note != null) {
            Quaterniond turn = new Quaterniond(note.after().orientation()).mul(new Quaterniond(note.before().orientation()).conjugate());
            Vector3d newUp = turn.transform(new Vector3d(up)).normalize();
            Quaterniond newFrame = surfaceFrame(newUp);
            double scaleBefore = frameScale(note.before());
            double scaleAfter = frameScale(note.after());
            for (int i = 0; i < legs.size(); i++) {
                CompoundTag leg = legs.getCompound(i);
                for (String key : FOOT_KEYS) {
                    Vector3d foot = getVec(leg, key);
                    // out of the old frame into the world, along with the body, into the new frame
                    Vector3d world = frame.transformInverse(foot).mul(scaleBefore);
                    Vector3d mapped = note.after().transformPosition(note.before().transformPositionInverse(world));
                    putVec(leg, key, newFrame.transform(mapped).div(scaleAfter));
                }
            }
            up.set(newUp);
            frame.set(newFrame);
        } else {
            for (int i = 0; i < legs.size(); i++) {
                legs.getCompound(i).putBoolean("grounded", false);
                legs.getCompound(i).putBoolean("swing", false);
            }
        }
        loadLegs.invoke(core, legs);
        core.setChanged();
        level.sendBlockUpdated(core.getBlockPos(), core.getBlockState(), core.getBlockState(), Block.UPDATE_CLIENTS);
        LOGGER.info("[IPShipPortal] let it walk: walker core at {} on ship {} follows its moved plots ({} legs{})",
            core.getBlockPos(), ship.getUniqueId(), legs.size(), note == null ? ", feet lifted" : "");
    }

    /** Moves the limp legs' record of the body ship {@code hull} from the level it left to {@code level}, re-pointed. */
    @SuppressWarnings("unchecked")
    private static void moveRemnant(ServerLevel level, UUID hull, Note note, BlockPos hullShift,
                                    Function<UUID, Vec3> shiftOf, Function<UUID, UUID> sameId) throws ReflectiveOperationException {
        ServerLevel from = level.getServer().getLevel(note.from());
        if (from == null) {
            return;
        }
        Object fromRemnants = remnantsOf.invoke(null, from);
        Map<UUID, CompoundTag> fromEntries = (Map<UUID, CompoundTag>) remnantEntries.get(fromRemnants);
        CompoundTag data = fromEntries.remove(hull);
        if (data == null) {
            return;
        }
        ((SavedData) fromRemnants).setDirty();
        data.putLong("core", BlockPos.of(data.getLong("core")).offset(hullShift).asLong());
        remapCore.invoke(null, data, shiftOf, sameId);
        Object toRemnants = remnantsOf.invoke(null, level);
        ((Map<UUID, CompoundTag>) remnantEntries.get(toRemnants)).put(hull, data);
        ((SavedData) toRemnants).setDirty();
        LOGGER.info("[IPShipPortal] let it walk: limp legs of ship {} follow it to {}", hull, level.dimension().location());
    }

    /** As Let It Walk's {@code WalkerFrame.toSurface}: the turn taking the surface normal to world up. */
    private static Quaterniond surfaceFrame(Vector3d up) {
        return new Quaterniond().rotationTo(up.x, up.y, up.z, 0.0, 1.0, 0.0);
    }

    /** As Let It Walk's {@code WalkerFrame.scaleOf}. */
    private static double frameScale(Pose3dc pose) {
        double scale = pose.scale().x();
        return Double.isFinite(scale) && scale > 0.001 ? scale : 1.0;
    }

    /**
     * Test hook ({@code /ip_aeronautics straddle debug walker}): builds and assembles one of Let It Walk's demo walkers
     * ({@code DemoWalkers.Type}, e.g. {@code built_spider}) at {@code at}, facing {@code facing}.
     *
     * @return Let It Walk's own result line, or why nothing was built
     */
    public static String buildDemo(ServerLevel level, BlockPos at, Direction facing, String type) {
        if (!ModList.get().isLoaded(MOD_ID)) {
            return "Let It Walk is not loaded";
        }
        try {
            Class<?> demo = Class.forName(DEMO_CLASS);
            Class<?> typeClass = Class.forName(DEMO_CLASS + "$Type");
            Object walkerType = null;
            for (Object constant : typeClass.getEnumConstants()) {
                if (((Enum<?>) constant).name().equalsIgnoreCase(type)) {
                    walkerType = constant;
                }
            }
            if (walkerType == null) {
                return "no demo walker " + type;
            }
            Method build = demo.getMethod("build", ServerLevel.class, BlockPos.class, Direction.class, typeClass);
            return ((Component) build.invoke(null, level, at, facing, walkerType)).getString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return "failed: " + e;
        }
    }

    /**
     * Test hook ({@code /ip_aeronautics straddle debug walkerdrive <keys>}): every walker in {@code level} takes
     * {@code keys} as a pilot's ({@code WalkerBrain.KEY_*}, gear in bits 8 to 10, bit 19 latches them as cruise).
     *
     * @return the number of walkers
     */
    public static int drive(ServerLevel level, int keys) {
        if (!ModList.get().isLoaded(MOD_ID) || !resolve()) {
            return 0;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return 0;
        }
        int driven = 0;
        try {
            Method receive = coreClass.getMethod("receiveInput", int.class, long.class);
            for (ServerSubLevel ship : container.getAllSubLevels()) {
                for (BlockEntity blockEntity : blockEntities(ship)) {
                    if (coreClass.isInstance(blockEntity)) {
                        receive.invoke(blockEntity, keys, level.getGameTime());
                        driven++;
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("driving walkers failed", e);
        }
        return driven;
    }

    /**
     * Test hook ({@code /ip_aeronautics straddle debug walkers}): one line per walker core on the ships of
     * {@code level}: its legs, how many of their joint anchors lie inside the plots of the ships they name (Sable
     * refuses a joint otherwise), and how many feet stand.
     */
    public static List<String> describe(ServerLevel level) {
        List<String> lines = new ArrayList<>();
        if (!ModList.get().isLoaded(MOD_ID) || !resolve()) {
            lines.add("Let It Walk is not loaded");
            return lines;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return lines;
        }
        try {
            for (ServerSubLevel ship : container.getAllSubLevels()) {
                for (BlockEntity blockEntity : blockEntities(ship)) {
                    if (coreClass.isInstance(blockEntity)) {
                        lines.add(describeCore(container, ship, blockEntity));
                    }
                }
            }
        } catch (Throwable e) {
            lines.add("failed: " + e);
        }
        if (lines.isEmpty()) {
            lines.add("no walker cores in " + level.dimension().location());
        }
        return lines;
    }

    private static String describeCore(ServerSubLevelContainer container, ServerSubLevel ship, BlockEntity core)
        throws ReflectiveOperationException {
        ListTag legs = (ListTag) saveLegs.invoke(core);
        int anchors = 0;
        int inside = 0;
        int missing = 0;
        int grounded = 0;
        for (int i = 0; i < legs.size(); i++) {
            CompoundTag leg = legs.getCompound(i);
            if (leg.getBoolean("grounded")) {
                grounded++;
            }
            for (Tag joint0 : leg.getCompound("built").getList("joints", Tag.TAG_COMPOUND)) {
                CompoundTag joint = (CompoundTag) joint0;
                for (String[] end : new String[][]{{"parent", "pp"}, {"child", "cp"}}) {
                    anchors++;
                    if (container.getSubLevel(joint.getUUID(end[0])) instanceof ServerSubLevel named) {
                        if (named.getPlot().contains(getVec(joint, end[1]))) {
                            inside++;
                        }
                    } else {
                        missing++;
                    }
                }
            }
        }
        var at = ship.logicalPose().position();
        return String.format(Locale.ROOT, "walker core at %s on ship %s (body at %.2f %.2f %.2f): %d legs, %d/%d joint anchors inside their ships' plots%s, %d feet grounded",
            core.getBlockPos().toShortString(), ship.getUniqueId(), at.x(), at.y(), at.z(), legs.size(), inside, anchors,
            missing > 0 ? " (" + missing + " name a ship not in this level)" : "", grounded);
    }

    private static List<BlockEntity> blockEntities(ServerSubLevel ship) {
        List<BlockEntity> blockEntities = new ArrayList<>();
        for (var holder : ship.getPlot().getLoadedChunks()) {
            blockEntities.addAll(holder.getChunk().getBlockEntities().values());
        }
        return blockEntities;
    }

    private static Vector3d getVec(CompoundTag tag, String key) {
        return new Vector3d(tag.getDouble(key + "x"), tag.getDouble(key + "y"), tag.getDouble(key + "z"));
    }

    private static void putVec(CompoundTag tag, String key, Vector3d v) {
        tag.putDouble(key + "x", v.x);
        tag.putDouble(key + "y", v.y);
        tag.putDouble(key + "z", v.z);
    }

    private static synchronized boolean resolve() {
        if (resolved) {
            return usable;
        }
        resolved = true;
        try {
            coreClass = Class.forName(CORE_CLASS);
            saveLegs = coreClass.getMethod("saveLegs");
            loadLegs = coreClass.getMethod("loadLegs", ListTag.class);
            surfaceUp = coreClass.getField("surfaceUp");
            toSurface = coreClass.getField("toSurface");
            remapCore = Class.forName(COPY_CLASS).getMethod("remapCore", CompoundTag.class, Function.class, Function.class);
            Class<?> remnants = Class.forName(REMNANTS_CLASS);
            remnantsOf = remnants.getDeclaredMethod("get", ServerLevel.class);
            remnantsOf.setAccessible(true);
            remnantEntries = remnants.getDeclaredField("entries");
            remnantEntries.setAccessible(true);
            usable = true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("Let It Walk's classes are not as expected (another version?); walkers will not follow ship moves", e);
        }
        return usable;
    }

    private static void warnOnce(String what, Throwable e) {
        if (!warned) {
            warned = true;
            LOGGER.warn("[IPShipPortal] let it walk: {}: {}", what, e.toString());
        }
    }
}
