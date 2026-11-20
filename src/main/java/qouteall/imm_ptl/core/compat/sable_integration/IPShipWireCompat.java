/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Drive By Wire (with Sable) and ship crossings.
 * <p>
 * Drive By Wire keeps its wires (a source block, a sink block and face, a channel) in one saved network per level,
 * keyed by block position, and remaps them only when Sable assembles blocks into a ship. A crossing removes the ship
 * from its level, which makes Drive By Wire drop every wire with an end on it, and loads it into a plot of the
 * destination level, whose network never had them: a ship arrived with its controller hub unwired. So the wires of the
 * ship are read before it leaves and laid again in the level it loads into, the ends on the ship shifted with its
 * plot. A wire to a block off the ship is kept only when the ship stays in its level (a signal does not reach into
 * another level); one to another ship crossing with it is not kept.
 * <p>
 * Read through Drive By Wire's public {@code WireNetworkManager} by reflection ({@code get(Level).save(tag)} lists the
 * wires, {@code createConnection} lays one), so we build without it.
 */
public final class IPShipWireCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String MOD_ID = "drivebywire";
    private static final String MANAGER_CLASS = "edn.stratodonut.drivebywire.wire.WireNetworkManager";

    private static volatile boolean resolved;
    @Nullable
    private static Method getManager;
    @Nullable
    private static Method saveNetwork;
    @Nullable
    private static Method createConnection;
    private static volatile boolean warned;

    /** One wire: its ends, whether each is on the ship, the sink's face and the channel. */
    private record Wire(BlockPos source, boolean sourceOnShip, BlockPos sink, boolean sinkOnShip, Direction face, String channel) {
    }

    /** The wires of a ship, read before it left its level. */
    public static final class Captured {
        static final Captured NONE = new Captured(null, List.of());
        @Nullable
        private final ServerLevel from;
        private final List<Wire> wires;

        private Captured(@Nullable ServerLevel from, List<Wire> wires) {
            this.from = from;
            this.wires = wires;
        }
    }

    private IPShipWireCompat() {
    }

    /** Reads the wires with an end on {@code sub} in {@code level}; call before the ship is removed from it. */
    public static Captured capture(ServerLevel level, ServerSubLevel sub) {
        if (!ModList.get().isLoaded(MOD_ID) || !resolve()) {
            return Captured.NONE;
        }
        try {
            CompoundTag network = (CompoundTag) saveNetwork.invoke(getManager.invoke(null, level), new CompoundTag());
            LevelPlot plot = sub.getPlot();
            List<Wire> wires = new ArrayList<>();
            for (Tag entry : network.getList("Connections", Tag.TAG_COMPOUND)) {
                CompoundTag connection = (CompoundTag) entry;
                BlockPos source = BlockPos.of(connection.getLong("Source"));
                BlockPos sink = BlockPos.of(connection.getLong("Sink"));
                boolean sourceOnShip = plot.contains(new ChunkPos(source));
                boolean sinkOnShip = plot.contains(new ChunkPos(sink));
                if (!sourceOnShip && !sinkOnShip) {
                    continue;
                }
                wires.add(new Wire(source, sourceOnShip, sink, sinkOnShip, Direction.from3DDataValue(connection.getByte("Direction")),
                    connection.getString("Channel")));
            }
            return wires.isEmpty() ? Captured.NONE : new Captured(level, wires);
        } catch (Throwable e) {
            warnOnce("could not read the wires of ship " + sub.getUniqueId(), e);
            return Captured.NONE;
        }
    }

    /**
     * Lays the captured wires in {@code level}, where the ship loaded with its blocks moved by {@code shift}. A wire to
     * a block off the ship is laid only when {@code level} is the one the ship left.
     */
    public static void restore(ServerLevel level, ServerSubLevel sub, Captured captured, BlockPos shift) {
        if (captured.wires.isEmpty()) {
            return;
        }
        int laid = 0;
        int dropped = 0;
        for (Wire wire : captured.wires) {
            if ((!wire.sourceOnShip() || !wire.sinkOnShip()) && level != captured.from) {
                dropped++;
                continue;
            }
            BlockPos source = wire.sourceOnShip() ? wire.source().offset(shift) : wire.source();
            BlockPos sink = wire.sinkOnShip() ? wire.sink().offset(shift) : wire.sink();
            try {
                Object result = createConnection.invoke(null, level, source, sink, wire.face(), wire.channel());
                if (result instanceof Enum<?> outcome && outcome.name().equals("OK")) {
                    laid++;
                } else {
                    LOGGER.info("[IPShipPortal] Drive By Wire: wire {} -> {} {} ({}) on ship {} not laid: {}", source.toShortString(),
                        sink.toShortString(), wire.face(), wire.channel(), sub.getUniqueId(), result);
                }
            } catch (Throwable e) {
                warnOnce("could not lay a wire of ship " + sub.getUniqueId(), e);
                break;
            }
        }
        LOGGER.info("[IPShipPortal] Drive By Wire: laid {} of {} wire(s) of ship {} in {}{}", laid, captured.wires.size(), sub.getUniqueId(),
            level.dimension().location(), dropped == 0 ? "" : "; " + dropped + " to blocks left in " + captured.from.dimension().location() + " dropped");
    }

    private static boolean resolve() {
        if (!resolved) {
            synchronized (IPShipWireCompat.class) {
                if (!resolved) {
                    try {
                        Class<?> manager = Class.forName(MANAGER_CLASS);
                        getManager = manager.getMethod("get", Level.class);
                        saveNetwork = manager.getMethod("save", CompoundTag.class);
                        createConnection = manager.getMethod("createConnection", Level.class, BlockPos.class, BlockPos.class, Direction.class,
                            String.class);
                    } catch (Throwable e) {
                        getManager = null;
                        saveNetwork = null;
                        createConnection = null;
                        LOGGER.warn("[IPShipPortal] Drive By Wire is installed but its wire network API was not found ({}); "
                            + "wires on ships will not survive portal crossings", e.toString());
                    }
                    resolved = true;
                }
            }
        }
        return createConnection != null;
    }

    private static void warnOnce(String what, Throwable e) {
        if (!warned) {
            warned = true;
            LOGGER.warn("[IPShipPortal] Drive By Wire: {}: {}", what, e.toString());
        }
    }
}
