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
import com.simibubi.create.content.contraptions.actors.seat.SeatBlock;
import com.simibubi.create.content.contraptions.bearing.ClockworkBearingBlockEntity;
import com.simibubi.create.content.contraptions.bearing.MechanicalBearingBlockEntity;
import com.simibubi.create.content.contraptions.glue.SuperGlueEntity;
import com.simibubi.create.content.contraptions.piston.LinearActuatorBlockEntity;
import com.simibubi.create.content.kinetics.base.GeneratingKineticBlockEntity;
import com.simibubi.create.content.kinetics.base.KineticBlockEntity;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;

/**
 * Create-side helpers for ship moves, gated by {@code ModList.isLoaded("create")} with the inner {@code Impl}
 * isolating Create class loading: disassembles running bearings / linear actuators (recording bearing
 * positions to reassemble), captures and re-adds super glue boxes, snapshots kinetic block entity positions
 * (generators get {@code reActivateSource} after the move) and re-seats riders on Create seats.
 */
public final class IPShipCreateCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean CREATE_LOADED = ModList.get().isLoaded("create");

    private IPShipCreateCompat() {
    }

    public static List<BlockPos> disassembleAssemblies(ServerLevel level, ServerSubLevel sub) {
        if (!CREATE_LOADED) {
            return List.of();
        } else {
            try {
                return IPShipCreateCompat.Impl.disassembleAssemblies(level, sub);
            } catch (Throwable e) {
                LOGGER.warn("[IPShipPortal] create: disassemble failed: {}", e.toString());
                return List.of();
            }
        }
    }

    public static List<AABB> captureGlue(ServerLevel level, ServerSubLevel sub) {
        if (!CREATE_LOADED) {
            return List.of();
        } else {
            try {
                return IPShipCreateCompat.Impl.captureGlue(level, sub);
            } catch (Throwable e) {
                LOGGER.warn("[IPShipPortal] create: glue capture failed: {}", e.toString());
                return List.of();
            }
        }
    }

    public static void replayGlue(ServerLevel level, List<AABB> glueBoxes, BlockPos shift) {
        if (CREATE_LOADED && !glueBoxes.isEmpty()) {
            try {
                IPShipCreateCompat.Impl.replayGlue(level, glueBoxes, shift);
            } catch (Throwable e) {
                LOGGER.warn("[IPShipPortal] create: glue replay failed: {}", e.toString());
            }
        }
    }

    public static void reassemble(ServerLevel level, List<BlockPos> bearingPositions, BlockPos shift) {
        if (CREATE_LOADED && !bearingPositions.isEmpty()) {
            try {
                IPShipCreateCompat.Impl.reassemble(level, bearingPositions, shift);
            } catch (Throwable e) {
                LOGGER.warn("[IPShipPortal] create: reassemble failed: {}", e.toString());
            }
        }
    }

    public static IPShipCreateCompat.KineticSnapshot collectKinetics(ServerLevel level, ServerSubLevel sub) {
        if (!CREATE_LOADED) {
            return IPShipCreateCompat.KineticSnapshot.EMPTY;
        } else {
            try {
                return IPShipCreateCompat.Impl.collectKinetics(level, sub);
            } catch (Throwable e) {
                LOGGER.warn("[IPShipPortal] create: kinetic collect failed: {}", e.toString());
                return IPShipCreateCompat.KineticSnapshot.EMPTY;
            }
        }
    }

    public static void reactivateGenerators(ServerLevel level, Set<Long> generatorPositions, BlockPos shift) {
        if (CREATE_LOADED && !generatorPositions.isEmpty()) {
            try {
                IPShipCreateCompat.Impl.reactivateGenerators(level, generatorPositions, shift);
            } catch (Throwable e) {
                LOGGER.warn("[IPShipPortal] create: generator reactivation failed: {}", e.toString());
            }
        }
    }

    public static boolean sitDown(ServerLevel level, BlockPos seatPos, Entity rider) {
        if (!CREATE_LOADED) {
            return false;
        } else {
            try {
                return IPShipCreateCompat.Impl.sitDown(level, seatPos, rider);
            } catch (Throwable e) {
                LOGGER.warn("[IPShipPortal] create: sitDown failed at {}: {}", seatPos, e.toString());
                return false;
            }
        }
    }

    /**
     * Whether {@code entity} is a Create train carriage or rides one. A train is never cargo of a ship resting on it:
     * Create moves carriages between dimensions itself, one copy per dimension, all sharing one UUID, and a copy
     * removed as {@code CHANGED_DIMENSION} saves as an empty tag, from which Create later deletes the whole train.
     */
    public static boolean isOnTrain(Entity entity) {
        if (!CREATE_LOADED) {
            return false;
        } else {
            try {
                return IPShipCreateCompat.Impl.isOnTrain(entity);
            } catch (Throwable e) {
                LOGGER.warn("[IPShipPortal] create: train check failed for {}: {}", entity, e.toString());
                return false;
            }
        }
    }

    private static final class Impl {
        static boolean isOnTrain(Entity entity) {
            return entity.getRootVehicle() instanceof CarriageContraptionEntity;
        }

        private static List<BlockEntity> blockEntitiesOf(ServerLevel level, ServerSubLevel sub) {
            ArrayList<BlockEntity> result = new ArrayList<>();

            for (PlotChunkHolder holder : sub.getPlot().getLoadedChunks()) {
                LevelChunk chunk = level.getChunk(holder.getPos().x, holder.getPos().z);
                result.addAll(chunk.getBlockEntities().values());
            }

            return result;
        }

        static List<BlockPos> disassembleAssemblies(ServerLevel level, ServerSubLevel sub) {
            ArrayList<BlockPos> toReassemble = new ArrayList<>();

            for (BlockEntity blockEntity : blockEntitiesOf(level, sub)) {
                try {
                    if (blockEntity instanceof MechanicalBearingBlockEntity bearing) {
                        boolean wasRunning = bearing.isRunning();
                        if (wasRunning || bearing.getMovedContraption() != null) {
                            bearing.disassemble();
                            if (wasRunning && bearing.getMovedContraption() == null) {
                                toReassemble.add(blockEntity.getBlockPos());
                            }
                        }
                    } else if (blockEntity instanceof ClockworkBearingBlockEntity clockwork) {
                        if (clockwork.isRunning()) {
                            clockwork.disassemble();
                            toReassemble.add(blockEntity.getBlockPos());
                        }
                    } else if (blockEntity instanceof LinearActuatorBlockEntity actuator && actuator.running) {
                        actuator.disassemble();
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("[IPShipPortal] create: failed to disassemble {}: {}", blockEntity.getBlockPos(), e.toString());
                }
            }

            if (!toReassemble.isEmpty()) {
                LOGGER.debug("[IPShipPortal] create: disassembled {} running contraption(s) pre-move", toReassemble.size());
            }

            return toReassemble;
        }

        static List<AABB> captureGlue(ServerLevel level, ServerSubLevel sub) {
            AABB plotBox = IPSableBridge.plotAabb(sub);
            if (plotBox == null) {
                return List.of();
            } else {
                ArrayList<AABB> glueBoxes = new ArrayList<>();

                for (SuperGlueEntity glue : level.getEntitiesOfClass(SuperGlueEntity.class, plotBox.inflate(2.0))) {
                    glueBoxes.add(glue.getBoundingBox());
                    glue.discard();
                }

                if (!glueBoxes.isEmpty()) {
                    LOGGER.debug("[IPShipPortal] create: captured {} super glue box(es)", glueBoxes.size());
                }

                return glueBoxes;
            }
        }

        static void replayGlue(ServerLevel level, List<AABB> glueBoxes, BlockPos shift) {
            for (AABB box : glueBoxes) {
                AABB shifted = box.move(shift.getX(), shift.getY(), shift.getZ());
                level.addFreshEntity(new SuperGlueEntity(level, shifted));
            }

            LOGGER.debug("[IPShipPortal] create: replayed {} super glue box(es)", glueBoxes.size());
        }

        static IPShipCreateCompat.KineticSnapshot collectKinetics(ServerLevel level, ServerSubLevel sub) {
            HashSet<Long> kineticPositions = new HashSet<>();
            HashSet<Long> generatorPositions = new HashSet<>();

            for (BlockEntity blockEntity : blockEntitiesOf(level, sub)) {
                if (blockEntity instanceof KineticBlockEntity) {
                    long packedPos = blockEntity.getBlockPos().asLong();
                    kineticPositions.add(packedPos);
                    if (blockEntity instanceof GeneratingKineticBlockEntity) {
                        generatorPositions.add(packedPos);
                    }
                }
            }

            return new IPShipCreateCompat.KineticSnapshot(kineticPositions, generatorPositions);
        }

        static void reactivateGenerators(ServerLevel level, Set<Long> generatorPositions, BlockPos shift) {
            int flagged = 0;

            for (long packedPos : generatorPositions) {
                BlockPos pos = BlockPos.of(packedPos).offset(shift);
                if (level.getBlockEntity(pos) instanceof GeneratingKineticBlockEntity generator) {
                    // Immersive Portals: Aeronautics: propagate now, not at the generator's next tick: machines that
                    // tick before it saw speed 0 for a tick (a propeller lost 15% of its spin at every crossing)
                    try {
                        generator.updateGeneratedRotation();
                    } catch (Throwable e) {
                        generator.reActivateSource = true;
                    }
                    flagged++;
                }
            }

            LOGGER.debug("[IPShipPortal] create: re-propagated rotation from {} generator(s)", flagged);
        }

        static boolean sitDown(ServerLevel level, BlockPos seatPos, Entity rider) {
            if (!(level.getBlockState(seatPos).getBlock() instanceof SeatBlock)) {
                LOGGER.debug("[IPShipPortal] no seat block at {} yet (plot may still be loading)", seatPos);
                return false;
            } else if (SeatBlock.isSeatOccupied(level, seatPos)) {
                return false;
            } else {
                SeatBlock.sitDown(level, seatPos, rider);
                return rider.isPassenger();
            }
        }

        static void reassemble(ServerLevel level, List<BlockPos> bearingPositions, BlockPos shift) {
            for (BlockPos oldPos : bearingPositions) {
                BlockPos pos = oldPos.offset(shift);
                BlockEntity blockEntity = level.getBlockEntity(pos);

                try {
                    if (blockEntity instanceof MechanicalBearingBlockEntity bearing) {
                        bearing.assemble();
                    } else if (blockEntity instanceof ClockworkBearingBlockEntity clockwork) {
                        clockwork.assemble();
                    } else {
                        LOGGER.warn(
                            "[IPShipPortal] create: expected a bearing at {} post-move but found {}; leaving disassembled",
                            pos,
                            blockEntity == null ? "no block entity" : blockEntity.getType()
                        );
                    }
                } catch (RuntimeException e) {
                    LOGGER.warn("[IPShipPortal] create: failed to reassemble bearing at {}; blocks are preserved, restart it manually: {}", pos, e.toString());
                }
            }
        }
    }

    public record KineticSnapshot(Set<Long> kineticPositions, Set<Long> generatorPositions) {
        public static final IPShipCreateCompat.KineticSnapshot EMPTY = new IPShipCreateCompat.KineticSnapshot(Set.of(), Set.of());
    }
}
