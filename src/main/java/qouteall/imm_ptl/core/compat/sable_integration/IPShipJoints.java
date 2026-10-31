/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.api.block.BlockEntitySubLevelActor;
import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.PhysicsConstraintHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.simulated_team.simulated.content.blocks.docking_connector.DockingConnectorBlockEntity;
import dev.simulated_team.simulated.content.blocks.spring.SpringBlock;
import dev.simulated_team.simulated.content.blocks.spring.SpringBlockEntity;
import dev.simulated_team.simulated.content.blocks.swivel_bearing.SwivelBearingBlockEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
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
    private static boolean connectedFailureLogged;

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
     * @return the ships directly connected to {@code ship} as Sable sees them ({@code SubLevelHelper.getConnectedChain},
     * one step of it), without {@code ship}: whatever names another ship from a block entity. That covers ropes and most
     * joints, and springs, which are forces and never reach {@link #track}.
     */
    public static Set<UUID> connected(ServerSubLevel ship) {
        Set<UUID> connected = new HashSet<>();
        try {
            for (BlockEntitySubLevelActor actor : ship.getPlot().getBlockEntityActors()) {
                Iterable<SubLevel> dependencies = actor.sable$getConnectionDependencies();
                if (dependencies != null) {
                    for (SubLevel other : dependencies) {
                        if (other != ship) {
                            connected.add(other.getUniqueId());
                        }
                    }
                }
            }
        } catch (Throwable e) {
            if (!connectedFailureLogged) {
                connectedFailureLogged = true;
                LOGGER.warn("[IPShipPortal] finding what is connected to ship {} failed: {}", ship.getUniqueId(), e.toString());
            }
        }
        return connected;
    }

    /**
     * Before a ship's data is snapshotted to leave its level. A spring saves its partner's position alone, so a spring
     * whose other end stays behind would look in the destination, find some other block there, and break that block when
     * it gives up. It snaps here instead, in its own level. Springs among the {@code travelling} ships go along, marked as
     * moving: a spring removed any other way takes its partner with it.
     */
    public static void prepareSpringsForMove(ServerLevel level, ServerSubLevel ship, Set<UUID> travelling) {
        if (!SIMULATED_LOADED) {
            return;
        }
        try {
            Impl.prepareSpringsForMove(level, ship, travelling);
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] simulated: preparing the springs of ship {} failed: {}", ship.getUniqueId(), e.toString());
        }
    }

    /** Test hook: the springs on the ships of {@code level}, one line each. */
    /** @return how far [blocks] the springs on {@code ship} hold its partners before snapping, 0 without springs */
    public static double springReach(ServerSubLevel ship) {
        return SIMULATED_LOADED ? Impl.springReach(ship) : 0.0;
    }

    public static List<String> describeSprings(ServerLevel level) {
        return SIMULATED_LOADED ? Impl.describeSprings(level) : List.of();
    }

    /**
     * Test hook ({@code /ip_aeronautics straddle debug dock}): docks the first two docking connectors found on the ships
     * of {@code level}, as their magnets do.
     *
     * @return whether two were found
     */
    public static boolean dockConnectors(ServerLevel level) {
        return SIMULATED_LOADED && Impl.dockConnectors(level);
    }

    /**
     * After ships moved, some into other plots ({@code shifts}: ship to plot shift): points the saved plot positions of
     * other ships at those ships' new plots. Each ship's own blocks were shifted in its saved data. Swivel bearings: the
     * plate's position on the rotor ship, and the plate's link back to its bearing. Springs and docking connectors: their
     * partner's position.
     */
    public static void shiftLoadedJoints(ServerLevel level, Map<UUID, BlockPos> shifts) {
        if (!SIMULATED_LOADED || shifts.values().stream().allMatch(BlockPos.ZERO::equals)) {
            return;
        }
        try {
            Impl.shiftLoadedJoints(level, shifts);
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] simulated: pointing ships' joints at moved plots failed: {}", e.toString());
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
                for (BlockEntity blockEntity : blockEntities(ship)) {
                    if (blockEntity instanceof SwivelBearingBlockEntity bearing) {
                        shiftBearing(bearing, moved.getValue(), shifts);
                    } else if (blockEntity instanceof SpringBlockEntity spring) {
                        shiftSpring(level, spring, shifts);
                    } else if (blockEntity instanceof DockingConnectorBlockEntity connector) {
                        shiftConnector(connector, shifts);
                    }
                }
            }
        }

        private static List<BlockEntity> blockEntities(ServerSubLevel ship) {
            List<BlockEntity> blockEntities = new ArrayList<>();
            for (var holder : ship.getPlot().getLoadedChunks()) {
                blockEntities.addAll(holder.getChunk().getBlockEntities().values());
            }
            return blockEntities;
        }

        static void prepareSpringsForMove(ServerLevel level, ServerSubLevel ship, Set<UUID> travelling) {
            for (BlockEntity blockEntity : blockEntities(ship)) {
                if (!(blockEntity instanceof SpringBlockEntity spring)) {
                    continue;
                }
                BlockPos pos = spring.getBlockPos();
                BlockState state = spring.getBlockState();
                UUID partner = spring.getPartnerSubLevelID();
                if (partner != null && travelling.contains(partner)) {
                    if (state.getBlock() instanceof SpringBlock block) {
                        block.beforeMove(level, level, state, pos, pos);
                    }
                } else {
                    LOGGER.info("[IPShipPortal] simulated: spring at {} of ship {} snaps, its other end stays behind",
                        pos.toShortString(), ship.getUniqueId());
                    level.destroyBlock(pos, true);
                }
            }
        }

        private static void shiftSpring(ServerLevel level, SpringBlockEntity spring, Map<UUID, BlockPos> shifts) {
            UUID partner = spring.getPartnerSubLevelID();
            BlockPos shift = partner == null ? null : shifts.get(partner);
            if (shift == null || shift.equals(BlockPos.ZERO)) {
                return;
            }
            // the block entity has no getter for it
            CompoundTag saved = spring.saveWithoutMetadata(level.registryAccess());
            if (saved.contains("Goal")) {
                spring.setPartnerPos(BlockPos.of(saved.getLong("Goal")).offset(shift), partner);
                spring.setChanged();
            }
        }

        private static void shiftConnector(DockingConnectorBlockEntity connector, Map<UUID, BlockPos> shifts) {
            BlockPos shift = connector.otherConnectorSubLevelId == null ? null : shifts.get(connector.otherConnectorSubLevelId);
            if (connector.otherConnectorPosition != null && shift != null && !shift.equals(BlockPos.ZERO)) {
                connector.otherConnectorPosition = connector.otherConnectorPosition.offset(shift);
                connector.setChanged();
            }
        }

        static double springReach(ServerSubLevel ship) {
            double reach = 0.0;
            for (BlockEntity blockEntity : blockEntities(ship)) {
                if (blockEntity instanceof SpringBlockEntity spring) {
                    reach = Math.max(reach, spring.getSnappingDistance());
                }
            }
            return reach;
        }

        static List<String> describeSprings(ServerLevel level) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            List<String> lines = new ArrayList<>();
            if (container != null) {
                for (ServerSubLevel ship : container.getAllSubLevels()) {
                    for (BlockEntity blockEntity : blockEntities(ship)) {
                        if (blockEntity instanceof SpringBlockEntity spring) {
                            lines.add("Spring on ship " + ship.getUniqueId() + " tied to " + spring.getPartnerSubLevelID()
                                + (spring.getPairedSpring() == null ? ": no partner" : ": paired"));
                        } else if (blockEntity instanceof DockingConnectorBlockEntity connector) {
                            DockingConnectorBlockEntity other = connector.getOtherConnector();
                            lines.add("Docking connector on ship " + ship.getUniqueId() + " tied to " + connector.otherConnectorSubLevelId
                                + (other != null && connector.getBlockPos().equals(other.otherConnectorPosition) ? ": docked" : ": not docked"));
                        }
                    }
                }
            }
            return lines;
        }

        static boolean dockConnectors(ServerLevel level) {
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            List<DockingConnectorBlockEntity> connectors = new ArrayList<>();
            if (container != null) {
                for (ServerSubLevel ship : container.getAllSubLevels()) {
                    for (BlockEntity blockEntity : blockEntities(ship)) {
                        if (blockEntity instanceof DockingConnectorBlockEntity connector) {
                            connectors.add(connector);
                        }
                    }
                }
            }
            if (connectors.size() < 2) {
                return false;
            }
            connectors.get(0).pairTo(connectors.get(1));
            return true;
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
            if (plate == null || rotor == null || !shifts.containsKey(rotor)) {
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
