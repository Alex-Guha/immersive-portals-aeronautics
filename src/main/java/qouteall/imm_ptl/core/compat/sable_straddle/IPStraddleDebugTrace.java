/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import org.joml.Vector3d;
import org.slf4j.Logger;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Test switch {@code -Dip_aeronautics.debug.handoffTrace=true}: logs, every tick, the pose and velocity of every ship
 * with an open straddle session and of every ship for {@value #TICKS_AFTER_HANDOFF} ticks after its hand-off, with the
 * state of its Create machinery (kinetic speeds, Aeronautics propeller spin). For headless tests of what a crossing does
 * to a ship (docs/lessons/straddle-sessions.md).
 */
final class IPStraddleDebugTrace {
    private static final Logger LOGGER = LogUtils.getLogger();
    static final boolean ENABLED = Boolean.getBoolean("ip_aeronautics.debug.handoffTrace");
    private static final long TICKS_AFTER_HANDOFF = 20;
    private static final boolean CREATE_LOADED = ModList.get().isLoaded("create");
    private static final boolean AERONAUTICS_LOADED = ModList.get().isLoaded("aeronautics");
    private static final Map<UUID, Long> HANDED_OFF = new HashMap<>();

    private IPStraddleDebugTrace() {
    }

    static void reset() {
        HANDED_OFF.clear();
    }

    static void onHandedOff(final UUID ship, final long tick) {
        if (ENABLED) {
            HANDED_OFF.put(ship, tick);
        }
    }

    static void tick(final MinecraftServer server, final long tick) {
        if (!ENABLED) {
            return;
        }
        final Set<UUID> traced = new HashSet<>(HANDED_OFF.keySet());
        IPStraddleSessions.forEach(session -> traced.add(session.key.ship()));
        for (final ServerLevel level : server.getAllLevels()) {
            final ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                continue;
            }
            for (final UUID id : traced) {
                if (container.getSubLevel(id) instanceof final ServerSubLevel ship && !ship.isRemoved()) {
                    final Long handedOff = HANDED_OFF.get(id);
                    final RigidBodyHandle body = RigidBodyHandle.of(ship);
                    LOGGER.info("[IPStraddle] trace {} tick {}{} in {}: position {}, velocity {}{}", id, tick,
                            handedOff == null ? "" : " (hand-off +" + (tick - handedOff) + ")", level.dimension().location(),
                            ship.logicalPose().position(), body == null ? "-" : body.getLinearVelocity(new Vector3d()), machinery(ship)
                                    + qouteall.imm_ptl.core.compat.sable_integration.IPShipRopeCompat.describeRopes(level, id));
                }
            }
        }
        HANDED_OFF.values().removeIf(handedOff -> tick - handedOff >= TICKS_AFTER_HANDOFF);
    }

    private static String machinery(final ServerSubLevel ship) {
        if (!CREATE_LOADED) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        for (final PlotChunkHolder holder : ship.getPlot().getLoadedChunks()) {
            for (final BlockEntity blockEntity : holder.getChunk().getBlockEntities().values()) {
                final String state = Impl.describe(blockEntity);
                if (state != null) {
                    out.append(out.isEmpty() ? ", machinery " : "; ").append(state);
                }
            }
        }
        return out.toString();
    }

    private static final class Impl {
        static String describe(final BlockEntity blockEntity) {
            if (!(blockEntity instanceof final com.simibubi.create.content.kinetics.base.KineticBlockEntity kinetic)) {
                return null;
            }
            String state = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(blockEntity.getBlockState().getBlock()).getPath()
                    + " speed " + kinetic.getSpeed() + (kinetic.hasNetwork() ? "" : " (no network)");
            if (AERONAUTICS_LOADED && blockEntity instanceof final dev.eriksonn.aeronautics.content.blocks.propeller.small.BasePropellerBlockEntity propeller) {
                state += String.format(" spin %.3f", propeller.rotationSpeed);
            }
            return state;
        }
    }
}
