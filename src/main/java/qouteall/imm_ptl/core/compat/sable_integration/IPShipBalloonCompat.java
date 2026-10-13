/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.Balloon;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.ServerBalloon;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.map.BalloonMap;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.map.SavedBalloon;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Create Aeronautics hot-air balloons and ship crossings.
 * <p>
 * A balloon (its hot-air volume and the lifting gas in it) lives in the {@code BalloonMap} of its level, keyed by
 * the plot-space position of its controller. Removing a ship from a level makes Aeronautics drop its balloons, and
 * the burners build fresh, empty ones once the ship is loaded in the destination: the ship arrived without lift and
 * sank for the 10-25 s the gas took to refill. Aeronautics already keeps the gas of a ship whose chunks unload: the
 * balloon is parked as a {@link SavedBalloon} in the map's unloaded list, and a burner creating a balloon whose volume
 * contains a parked controller position takes the gas over. A crossing uses the same path: the balloons of the
 * ship are snapshotted before it leaves and parked in the destination level (shifted with the plot) right after it
 * loads, before its burners tick.
 */
public final class IPShipBalloonCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean AERONAUTICS_LOADED = ModList.get().isLoaded("aeronautics");

    /** The balloons of a ship, snapshotted before it left its level (Aeronautics' {@code SavedBalloon}s). */
    public static final class Captured {
        static final Captured NONE = new Captured(List.of());
        private final List<?> balloons;

        private Captured(List<?> balloons) {
            this.balloons = balloons;
        }
    }

    private IPShipBalloonCompat() {
    }

    /** Snapshots the balloons of {@code sub} in {@code level}; call before the ship is removed from it. */
    public static Captured capture(ServerLevel level, ServerSubLevel sub) {
        if (!AERONAUTICS_LOADED) {
            return Captured.NONE;
        }
        try {
            return new Captured(Impl.capture(level, sub));
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] aeronautics: capturing the balloons of ship {} failed: {}", sub.getUniqueId(), e.toString());
            return Captured.NONE;
        }
    }

    /**
     * Parks the captured balloons in {@code level} for the ship {@code sub} just loaded there, shifted by the plot
     * shift of the load, so its burners take the gas over. Call right after the load, before the level ticks.
     */
    public static void restore(ServerLevel level, ServerSubLevel sub, Captured captured, BlockPos shift) {
        if (!AERONAUTICS_LOADED || captured.balloons.isEmpty()) {
            return;
        }
        try {
            Impl.restore(level, sub, captured.balloons, shift);
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] aeronautics: restoring the balloons of ship {} failed: {}", sub.getUniqueId(), e.toString());
        }
    }

    private static final class Impl {
        static List<SavedBalloon> capture(ServerLevel level, ServerSubLevel sub) {
            LevelPlot plot = sub.getPlot();
            List<SavedBalloon> saved = new ArrayList<>();
            for (Balloon balloon : BalloonMap.MAP.get(level).getBalloons()) {
                BlockPos controller = balloon.getControllerPos();
                if (balloon instanceof ServerBalloon serverBalloon && plot.contains(controller.getX(), controller.getZ())) {
                    saved.add(BalloonMap.saveBalloon(serverBalloon));
                }
            }
            return saved;
        }

        static void restore(ServerLevel level, ServerSubLevel sub, List<?> balloons, BlockPos shift) {
            BalloonMap map = BalloonMap.MAP.get(level);
            LevelPlot plot = sub.getPlot();

            // parked balloons left in this plot by an earlier occupant would hand their gas to this ship's burners
            Iterator<SavedBalloon> iterator = map.getUnloadedBalloons().iterator();
            while (iterator.hasNext()) {
                BlockPos controller = iterator.next().controllerPos();
                if (plot.contains(controller.getX(), controller.getZ())) {
                    iterator.remove();
                }
            }

            double gas = 0.0;
            for (Object entry : balloons) {
                SavedBalloon balloon = (SavedBalloon) entry;
                BoundingBox3ic bounds = balloon.bounds();
                map.getUnloadedBalloons().add(new SavedBalloon(
                    new BoundingBox3i(
                        bounds.minX() + shift.getX(), bounds.minY() + shift.getY(), bounds.minZ() + shift.getZ(),
                        bounds.maxX() + shift.getX(), bounds.maxY() + shift.getY(), bounds.maxZ() + shift.getZ()
                    ),
                    balloon.controllerPos().offset(shift),
                    balloon.gasData()
                ));
                for (var holder : balloon.gasData()) {
                    gas += holder.data().amount;
                }
            }
            map.markDirty();
            LOGGER.info(
                "[IPShipPortal] aeronautics: carried {} balloon(s) of ship {} into {} ({} m3 of lifting gas)",
                balloons.size(), sub.getUniqueId(), level.dimension().location(), String.format("%.1f", gas)
            );
        }
    }
}
