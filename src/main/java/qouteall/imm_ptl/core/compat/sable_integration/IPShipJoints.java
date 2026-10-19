/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Ships joined to each other by physics joints: Simulated's swivel bearings, docking connectors and plungers, or any
 * other {@code PhysicsPipeline.addConstraint} between two ships. Jointed ships cross a portal together, like roped ones
 * ({@code IPStraddleController}): a ship handed off alone loses its joints, since its body is rebuilt in the
 * destination and its partner is not there. The joint's owner rebuilds it there from its saved data once both ships
 * have arrived, as after a world reload.
 * <p>
 * Fed by our physics backend ({@code ipaeronautics.rapier.RapierPhysicsPipeline.addConstraint}), so it knows the joints
 * only while that backend is active, which straddling needs anyway. A joint stops counting once its handle is removed
 * or one of its ships is: removing a ship's body drops its Rapier joint but keeps Sable's entry, so
 * {@link PhysicsConstraintHandle#isValid()} alone stays true for a dead joint. Joints to the world do not join ships.
 */
public final class IPShipJoints {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean SIMULATED_LOADED = ModList.get().isLoaded("simulated");

    private record Joint(ServerSubLevel a, ServerSubLevel b, PhysicsConstraintHandle handle) {
        boolean live() {
            return !this.a.isRemoved() && !this.b.isRemoved() && this.handle.isValid();
        }
    }

    private static final List<Joint> JOINTS = new ArrayList<>();

    private IPShipJoints() {
    }

    /** A server starts: the previous one's joints are gone with its physics scenes. */
    public static synchronized void reset() {
        JOINTS.clear();
    }

    /** Our pipeline made a joint; recorded when it joins two ships. */
    public static synchronized void track(@Nullable PhysicsPipelineBody bodyA, @Nullable PhysicsPipelineBody bodyB,
                                          PhysicsConstraintHandle handle) {
        JOINTS.removeIf(joint -> !joint.live());
        if (bodyA instanceof ServerSubLevel a && bodyB instanceof ServerSubLevel b) {
            JOINTS.add(new Joint(a, b, handle));
        }
    }

    /** @return the ships joined to {@code ship} directly by a live joint, without {@code ship} */
    public static synchronized Set<UUID> partners(ServerSubLevel ship) {
        JOINTS.removeIf(joint -> !joint.live());
        Set<UUID> partners = new HashSet<>();
        for (Joint joint : JOINTS) {
            if (joint.a() == ship) {
                partners.add(joint.b().getUniqueId());
            } else if (joint.b() == ship) {
                partners.add(joint.a().getUniqueId());
            }
        }
        return partners;
    }

    /**
     * After ships crossed together, some into other plots ({@code shifts}: ship to plot shift): points the joints'
     * saved plot positions of other ships at those ships' new plots. Each ship's own blocks were shifted in its saved
     * data. Swivel bearings: the plate's position on the rotor ship, and the plate's link back to its bearing.
     */
    public static void shiftLoadedJoints(ServerLevel level, Map<UUID, BlockPos> shifts) {
        if (!SIMULATED_LOADED || shifts.values().stream().allMatch(BlockPos.ZERO::equals)) {
            return;
        }
        try {
            Impl.shiftLoadedJoints(level, shifts);
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] simulated: shifting the joints of moved ships failed: {}", e.toString());
        }
    }

    /**
     * Test hook ({@code /ip_aeronautics straddle debug swivel}): assembles every unassembled swivel bearing on the
     * ships of {@code level} next tick, as an empty-handed click does.
     *
     * @return the number of bearings
     */
    public static int assembleSwivelBearings(ServerLevel level) {
        return SIMULATED_LOADED ? Impl.assembleSwivelBearings(level) : 0;
    }

    /** Touches Simulated's classes; loaded only when Simulated is present. */
    private static final class Impl {
        static void shiftLoadedJoints(ServerLevel level, Map<UUID, BlockPos> shifts) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return;
            }
            for (Map.Entry<UUID, BlockPos> moved : shifts.entrySet()) {
                if (!(container.getSubLevel(moved.getKey()) instanceof ServerSubLevel ship)) {
                    continue;
                }
                for (var holder : ship.getPlot().getLoadedChunks()) {
                    for (BlockEntity blockEntity : List.copyOf(holder.getChunk().getBlockEntities().values())) {
                        if (blockEntity instanceof SwivelBearingBlockEntity bearing) {
                            shiftBearing(bearing, moved.getValue(), shifts);
                        }
                    }
                }
            }
        }

        static int assembleSwivelBearings(ServerLevel level) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return 0;
            }
            int count = 0;
            for (ServerSubLevel ship : container.getAllSubLevels()) {
                for (var holder : ship.getPlot().getLoadedChunks()) {
                    for (BlockEntity blockEntity : holder.getChunk().getBlockEntities().values()) {
                        if (blockEntity instanceof SwivelBearingBlockEntity bearing && !bearing.isAssembled()) {
                            bearing.assembleNextTick = true;
                            count++;
                        }
                    }
                }
            }
            return count;
        }

        private static void shiftBearing(SwivelBearingBlockEntity bearing, BlockPos ownShift, Map<UUID, BlockPos> shifts) {
            BlockPos plate = bearing.getPlatePos();
            UUID rotor = bearing.getSubLevelID();
            if (plate == null || rotor == null) {
                return;
            }
            BlockPos rotorShift = shifts.getOrDefault(rotor, BlockPos.ZERO);
            if (rotorShift.equals(BlockPos.ZERO) && ownShift.equals(BlockPos.ZERO)) {
                return;
            }
            if (!rotorShift.equals(BlockPos.ZERO)) {
                bearing.setPlatePos(plate.offset(rotorShift));
            }
            // the plate keeps its bearing's plot position
            bearing.associatePlateWithParent();
            bearing.notifyUpdate();
            LOGGER.info("[IPShipPortal] swivel bearing at {}: plate {} -> {} (rotor ship {} moved by {})",
                    bearing.getBlockPos().toShortString(), plate.toShortString(), bearing.getPlatePos().toShortString(),
                    rotor, rotorShift.toShortString());
        }
    }
}
