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
import dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.companion.math.BoundingBox3d;
import dev.ryanhcode.sable.companion.math.BoundingBox3dc;
import dev.ryanhcode.sable.companion.math.BoundingBox3i;
import dev.ryanhcode.sable.companion.math.BoundingBox3ic;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelData;
import dev.ryanhcode.sable.sublevel.storage.serialization.SubLevelSerializer;
import dev.ryanhcode.sable.util.SableNBTUtils;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Quaterniond;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import org.joml.Vector3dc;
import org.slf4j.Logger;

/**
 * The actual ship move, implemented as serialize-remove-reload. {@link #moveThroughPortal} disassembles Create
 * contraptions, captures glue/kinetics/riders, serializes the ship with {@code SubLevelSerializer.toData},
 * strips Create kinetic NBT, rewrites the {@code pose} compound to the portal-transformed position and
 * orientation, journals the entry, removes the ship from the source container and {@link #reloadInDestination}
 * reloads it into a free plot of the destination (offsetting block-entity/tick coordinates by the plot shift and
 * min-build-height delta), then rebuilds physics data block by block, zeroes velocity and replays glue,
 * contraptions, generators and riders. On failure the ship is restored to the source from a pre-edit NBT copy.
 */
public final class IPSableBridge {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String[] KINETIC_KEYS = new String[]{"Speed", "Source", "Network", "NeedsSpeedUpdate"};

    private IPSableBridge() {
    }

    public static AABB worldAabb(SubLevel sub) {
        BoundingBox3dc bounds = sub.boundingBox();
        return new AABB(bounds.minX(), bounds.minY(), bounds.minZ(), bounds.maxX(), bounds.maxY(), bounds.maxZ());
    }

    public static IPSableBridge.Moved moveThroughPortal(ServerSubLevel sub, ServerLevel srcLevel, ServerLevel dstLevel, Vec3 destPos, Quaterniond rotation) {
        return moveThroughPortal(sub, srcLevel, dstLevel, destPos, rotation, null);
    }

    /**
     * Moves a ship to {@code dstLevel} with its pose position at {@code destPos} and its orientation premultiplied by
     * {@code rotation} (null: unchanged). The ship arrives at rest, or with {@code carried} (a straddle hand-off: the ship
     * keeps moving as it did, mapped through the portal).
     */
    public static IPSableBridge.Moved moveThroughPortal(
        ServerSubLevel sub, ServerLevel srcLevel, ServerLevel dstLevel, Vec3 destPos, Quaterniond rotation, @Nullable Velocity carried
    ) {
        IPSableBridge.Moved moved = moveThroughPortal(sub, srcLevel, dstLevel, destPos, rotation, carried, Set.of(sub.getUniqueId()), false);
        if (moved != null) {
            IPShipJoints.shiftLoadedJoints(dstLevel, Map.of(moved.sub().getUniqueId(), moved.shift()));
        }
        return moved;
    }

    /**
     * As {@link #moveThroughPortal(ServerSubLevel, ServerLevel, ServerLevel, Vec3, Quaterniond, Velocity)}, for a ship
     * crossing together with the ships in {@code travelling} (which includes it): ropes between them are kept and travel
     * with the ships ({@link IPShipRopeCompat}); the caller moves the others too and then fixes up their anchors.
     *
     * @param straddled the ship went through the portal straddling it (a hand-off): players standing next to it are
     *                  not carried along ({@link IPShipRiderCarry#capture})
     */
    public static IPSableBridge.Moved moveThroughPortal(
        ServerSubLevel sub, ServerLevel srcLevel, ServerLevel dstLevel, Vec3 destPos, Quaterniond rotation, @Nullable Velocity carried,
        Set<UUID> travelling, boolean straddled
    ) {
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        String refusal = whyNotMovable(sub, srcLevel, dstLevel);
        if (refusal != null) {
            LOGGER.warn("[IPShipPortal] ship {} cannot be moved to {}: {}; it stays where it is", sub.getUniqueId(), dstLevel.dimension().location(), refusal);
            return null;
        }
        if (srcContainer != null && dstContainer != null) {
            // Immersive Aeronautics: ropes tied to anything staying behind cannot follow the ship; break them before
            // the ship's data is snapshotted so the anchor blocks travel detached (a strand left behind would crash
            // the source level's physics on its next tick). Ropes among the travelling ships travel along.
            IPShipRopeCompat.breakAttachedRopes(srcLevel, sub, travelling);
            IPShipJoints.prepareSpringsForMove(srcLevel, sub, travelling);
            List<BlockPos> disassembled = IPShipCreateCompat.disassembleAssemblies(srcLevel, sub);
            IPShipCreateCompat.Glue glue = IPShipCreateCompat.captureGlue(srcLevel, sub);
            IPShipCreateCompat.KineticSnapshot kinetics = IPShipCreateCompat.collectKinetics(srcLevel, sub);
            IPShipRiderCarry.Captured riders = IPShipRiderCarry.capture(srcLevel, sub, straddled);
            IPShipBalloonCompat.Captured balloons = IPShipBalloonCompat.capture(srcLevel, sub);
            SubLevelData data = SubLevelSerializer.toData(sub, List.of());
            CompoundTag fullTag = data.fullTag();
            stripKineticState(fullTag.getCompound("plot"), kinetics.kineticPositions());
            CompoundTag preEditCopy = fullTag.copy();
            CompoundTag poseTag = fullTag.getCompound("pose");
            Pose3d pose = SableNBTUtils.readPose3d(poseTag);
            Pose3d oldPose = new Pose3d(pose);
            pose.position().set(destPos.x, destPos.y, destPos.z);
            if (rotation != null) {
                pose.orientation().premul(rotation);
            }
            IPShipRopeCompat.mapSavedStrands(fullTag.getCompound("plot"), oldPose, pose, dstLevel);

            fullTag.put("pose", SableNBTUtils.writePose3d(pose));
            IPShipTeleportJournal.write(
                srcLevel.getServer(), data.uuid(), srcLevel.dimension().location(), dstLevel.dimension().location(), srcLevel.getMinBuildHeight(), data
            );
            srcContainer.removeSubLevel(sub, SubLevelRemovalReason.REMOVED);
            // Pym would rebuild the old copy's last joints (a held handle's, aimed at this world's coordinates) on the new
            // copy when it is resized: the new copy has the same UUID (IPPymCompat.forgetConstraintsOf)
            int forgotten = IPPymCompat.forgetConstraintsOf(sub.getUniqueId());
            if (forgotten > 0) {
                LOGGER.info("[IPShipPortal] Pym: forgot {} joint record(s) of ship {} left in {}", forgotten, sub.getUniqueId(),
                    srcLevel.dimension().location());
            }

            IPSableBridge.Loaded loaded;
            try {
                // within one level, never the plot it left (see reloadInDestination)
                loaded = reloadInDestination(srcLevel.getMinBuildHeight(), dstLevel, dstContainer, data, dstLevel == srcLevel);
            } catch (RuntimeException e) {
                LOGGER.warn("[IPShipPortal] destination load threw for ship {}; restoring to source: {}", data.uuid(), e.toString());
                loaded = null;
            }

            if (loaded != null) {
                IPShipCreateCompat.replayGlue(dstLevel, glue, loaded.shift());
                IPShipCreateCompat.reassemble(dstLevel, disassembled, loaded.shift());
                IPShipCreateCompat.reactivateGenerators(dstLevel, kinetics.generatorPositions(), loaded.shift());
                IPShipBalloonCompat.restore(dstLevel, loaded.sub(), balloons, loaded.shift());
                Vec3 riderVelocity = Vec3.ZERO;
                if (carried == null) {
                    stopOnArrival(loaded.sub());
                } else {
                    setVelocity(loaded.sub(), carried);
                    // blocks per tick, as arrivalVelocity
                    riderVelocity = new Vec3(carried.linear().x(), carried.linear().y(), carried.linear().z()).scale(0.05);
                }
                IPShipRiderCarry.replay(srcLevel, dstLevel, loaded.sub(), loaded.shift(), riders, riderVelocity);
                IPShipTeleportJournal.delete(srcLevel.getServer(), data.uuid());
                return new IPSableBridge.Moved(loaded.sub(), loaded.shift());
            } else {
                if (restoreToSource(srcLevel, srcContainer, preEditCopy, data.uuid(), disassembled, glue, kinetics, riders, balloons)) {
                    IPShipTeleportJournal.delete(srcLevel.getServer(), data.uuid());
                } else {
                    LOGGER.warn(
                        "[IPShipPortal] destination load AND source restore failed for ship {}; left in journal for recovery on next start",
                        data.uuid()
                    );
                }

                return null;
            }
        } else {
            LOGGER.warn("[IPShipPortal] missing container src={} dst={}", srcContainer, dstContainer);
            return null;
        }
    }

    /**
     * Why {@link #moveThroughPortal} would fail for this ship before touching it, or null when it can be moved: the
     * checks {@link #tryLoad} makes on the serialized ship (fits the destination's build height, a free plot there),
     * made on the live ship instead, so a refused move never removes and restores it.
     */
    public static @Nullable String whyNotMovable(ServerSubLevel sub, ServerLevel srcLevel, ServerLevel dstLevel) {
        ServerSubLevelContainer srcContainer = SubLevelContainer.getContainer(srcLevel);
        ServerSubLevelContainer dstContainer = SubLevelContainer.getContainer(dstLevel);
        if (srcContainer == null || dstContainer == null) {
            return "no ship container in " + (srcContainer == null ? srcLevel : dstLevel).dimension().location();
        }
        BoundingBox3ic bounds = sub.getPlot().getBoundingBox();
        if (bounds != BoundingBox3i.EMPTY) {
            int srcMinBuildHeight = srcLevel.getMinBuildHeight();
            int usedSections = ((bounds.maxY() - srcMinBuildHeight) >> 4) - ((bounds.minY() - srcMinBuildHeight) >> 4);
            if (usedSections >= dstLevel.getSectionsCount()) {
                return "it is too tall for " + dstLevel.dimension().location();
            }
        }
        int plotX = sub.getPlot().plotPos.x - srcContainer.getOrigin().x;
        int plotZ = sub.getPlot().plotPos.z - srcContainer.getOrigin().y;
        if (findFreePlot(dstContainer, plotX, plotZ) == null) {
            return "the ship container of " + dstLevel.dimension().location() + " has no free plot";
        }
        return null;
    }

    /**
     * Refused crossing: removes the ship's velocity into the portal (along {@code outward}, the portal's normal
     * pointing back to the side the ship came from) and its spin, and gives it {@code speed} [m/s] back out.
     */
    public static void pushBackOutOfPortal(ServerSubLevel sub, Vec3 outward, double speed) {
        try {
            RigidBodyHandle body = RigidBodyHandle.of(sub);
            if (body == null) {
                return;
            }

            Vec3 normal = outward.normalize();
            Vector3d linear = body.getLinearVelocity(new Vector3d());
            double into = linear.x * normal.x + linear.y * normal.y + linear.z * normal.z;
            double change = Math.max(0.0, -into) + speed;
            Vector3d angular = body.getAngularVelocity(new Vector3d());
            body.addLinearAndAngularVelocity(
                new Vector3d(normal.x * change, normal.y * change, normal.z * change), angular.negate(new Vector3d())
            );
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] could not push ship {} back out of the portal: {}", sub.getUniqueId(), e.toString());
        }
    }

    private static void stopOnArrival(ServerSubLevel sub) {
        try {
            RigidBodyHandle body = RigidBodyHandle.of(sub);
            if (body == null) {
                return;
            }

            Vector3d linear = body.getLinearVelocity(new Vector3d());
            Vector3d angular = body.getAngularVelocity(new Vector3d());
            body.addLinearAndAngularVelocity(linear.negate(new Vector3d()), angular.negate(new Vector3d()));
            LOGGER.debug("[IPShipPortal] ship brought to rest on arrival");
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] could not damp arrival velocity: {}", e.toString());
        }
    }

    /** A rigid body's velocity: linear [m/s] and angular [rad/s], both in world space. */
    public record Velocity(Vector3dc linear, Vector3dc angular) {
    }

    private static void setVelocity(ServerSubLevel sub, Velocity velocity) {
        try {
            RigidBodyHandle body = RigidBodyHandle.of(sub);
            if (body == null) {
                return;
            }

            Vector3d linear = body.getLinearVelocity(new Vector3d());
            Vector3d angular = body.getAngularVelocity(new Vector3d());
            body.addLinearAndAngularVelocity(
                new Vector3d(velocity.linear()).sub(linear), new Vector3d(velocity.angular()).sub(angular)
            );
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] could not carry the ship's velocity through the portal: {}", e.toString());
        }
    }

    private static Vec3 arrivalVelocity(ServerSubLevel sub) {
        try {
            if (sub.isRemoved()) {
                return Vec3.ZERO;
            } else {
                RigidBodyHandle body = RigidBodyHandle.of(sub);
                if (body == null) {
                    return Vec3.ZERO;
                } else {
                    Vector3d linear = body.getLinearVelocity(new Vector3d());
                    return new Vec3(linear.x, linear.y, linear.z).scale(0.05);
                }
            }
        } catch (Throwable e) {
            return Vec3.ZERO;
        }
    }

    private static boolean restoreToSource(
        ServerLevel srcLevel,
        ServerSubLevelContainer srcContainer,
        CompoundTag preEditCopy,
        UUID shipId,
        List<BlockPos> disassembled,
        IPShipCreateCompat.Glue glue,
        IPShipCreateCompat.KineticSnapshot kinetics,
        IPShipRiderCarry.Captured riders,
        IPShipBalloonCompat.Captured balloons
    ) {
        SubLevelData data = SubLevelSerializer.fromData(preEditCopy);
        if (data == null) {
            return false;
        } else {
            IPSableBridge.Loaded loaded;
            try {
                loaded = reloadInDestination(srcLevel.getMinBuildHeight(), srcLevel, srcContainer, data);
            } catch (RuntimeException e) {
                LOGGER.warn("[IPShipPortal] source restore threw for ship {}: {}", shipId, e.toString());
                return false;
            }

            if (loaded == null) {
                return false;
            } else {
                IPShipCreateCompat.replayGlue(srcLevel, glue, loaded.shift());
                IPShipCreateCompat.reassemble(srcLevel, disassembled, loaded.shift());
                IPShipCreateCompat.reactivateGenerators(srcLevel, kinetics.generatorPositions(), loaded.shift());
                IPShipBalloonCompat.restore(srcLevel, loaded.sub(), balloons, loaded.shift());
                IPShipRiderCarry.replay(srcLevel, srcLevel, loaded.sub(), loaded.shift(), riders, arrivalVelocity(loaded.sub()));
                LOGGER.info("[IPShipPortal] destination load failed; ship {} restored to source (move cancelled)", shipId);
                return true;
            }
        }
    }

    public static IPSableBridge.Loaded reloadInDestination(int srcMinBuildHeight, ServerLevel dstLevel, ServerSubLevelContainer dstContainer, SubLevelData data) {
        return reloadInDestination(srcMinBuildHeight, dstLevel, dstContainer, data, false);
    }

    /**
     * @param otherPlot load the ship into a plot other than the one it was saved from: for a move within one level.
     *                  Sable names a ship by its plot in what clients send and receive (a player's position while
     *                  standing on it, pose snapshots), so in the old plot the arrived copy took over what was meant for
     *                  the copy that left: a move packet the rider's client sent from the old copy put the player a
     *                  portal's distance on, their own crossing was refused as too far, and a drag or held handle was
     *                  left a portal off and pulled the ship there
     */
    public static IPSableBridge.Loaded reloadInDestination(int srcMinBuildHeight, ServerLevel dstLevel, ServerSubLevelContainer dstContainer, SubLevelData data,
                                                           boolean otherPlot) {
        IPSableBridge.Loaded loaded = tryLoad(srcMinBuildHeight, dstLevel, dstContainer, data, otherPlot);
        if (loaded != null) {
            logLoadedPhysics(dstLevel, loaded.sub());
        }

        return loaded;
    }

    /**
     * Logs the loaded ship's block count and mass. Sable's {@code ServerLevelPlot.load} already feeds every loaded
     * block to the mass tracker and the physics pipeline; until 1.4.23 every block was replayed here once more
     * ({@code handleBlockChange(air -> state)}), which doubled the ship's mass and inertia on every crossing (a hot-air
     * balloon could no longer lift it).
     */
    private static void logLoadedPhysics(ServerLevel level, ServerSubLevel sub) {
        BoundingBox3ic bounds = sub.getPlot().getBoundingBox();
        if (bounds == BoundingBox3i.EMPTY) {
            LOGGER.warn("[IPShipPortal] plot bounds empty after load for ship {}", sub.getUniqueId());
            return;
        }

        MutableBlockPos pos = new MutableBlockPos();
        int blocks = 0;
        for (int x = bounds.minX(); x <= bounds.maxX(); x++) {
            for (int y = bounds.minY(); y <= bounds.maxY(); y++) {
                for (int z = bounds.minZ(); z <= bounds.maxZ(); z++) {
                    if (!level.getBlockState(pos.set(x, y, z)).isAir()) {
                        blocks++;
                    }
                }
            }
        }

        LOGGER.info(
            "[IPShipPortal] loaded ship {} ({} blocks, mass {}, massInvalid={})",
            sub.getUniqueId(),
            blocks,
            String.format("%.1f", sub.getMassTracker().getMass()),
            sub.getMassTracker().isInvalid()
        );
    }

    /**
     * Rewrites the serialized plot so that it loads correctly in {@code dstLevel}, then loads it.
     * <p>
     * Sable stores chunk sections keyed by <em>array index</em> (0 = the level's lowest section),
     * so a ship serialized in a dimension with another minimum build height would come back
     * shifted vertically (nether -> overworld: 64 blocks down). The sections are therefore re-keyed
     * so the blocks keep their absolute Y where possible. Likewise, if the ship's preferred plot is
     * taken in the destination it is moved to a free plot, which shifts every block in plot space.
     * <p>
     * Sable maps plot space to the world as {@code position + rotation * (p - rotationPoint)}, so
     * every shift applied to the blocks (block entities, scheduled ticks, Create controllers) is also
     * applied to the pose's rotation point; otherwise the ship appears displaced by exactly that
     * shift (a whole plot sideways, or 64 blocks underground).
     */
    private static IPSableBridge.Loaded tryLoad(int srcMinBuildHeight, ServerLevel dstLevel, ServerSubLevelContainer dstContainer, SubLevelData data,
                                                boolean otherPlot) {
        CompoundTag fullTag = data.fullTag();
        CompoundTag plotTag = fullTag.getCompound("plot");
        int oldPlotX = plotTag.getInt("plot_x");
        int oldPlotZ = plotTag.getInt("plot_z");

        int[] usedSections = usedSectionIndexRange(plotTag);
        int dstSectionCount = dstLevel.getSectionsCount();
        if (usedSections != null && usedSections[1] - usedSections[0] >= dstSectionCount) {
            LOGGER.warn("[IPShipPortal] ship {} is too tall for {}; aborting move so it stays where it is", data.uuid(), dstLevel.dimension().location());
            return null;
        }

        int[] freePlot = otherPlot ? findFreePlotOtherThan(dstContainer, oldPlotX, oldPlotZ) : findFreePlot(dstContainer, oldPlotX, oldPlotZ);
        if (freePlot == null) {
            LOGGER.warn("[IPShipPortal] destination container has no free plot; aborting move for {}", data.uuid());
            return null;
        }

        int logRegionSize = dstContainer.getLogPlotSize() + 4;
        int shiftX = freePlot[0] - oldPlotX << logRegionSize;
        int shiftZ = freePlot[1] - oldPlotZ << logRegionSize;

        // section re-keying: preferred offset keeps absolute Y, clamped so every section fits
        int dstMinBuildHeight = dstLevel.getMinBuildHeight();
        int sectionShift = (srcMinBuildHeight - dstMinBuildHeight) >> 4;
        if (usedSections != null) {
            if (usedSections[0] + sectionShift < 0) {
                sectionShift = -usedSections[0];
            }
            if (usedSections[1] + sectionShift >= dstSectionCount) {
                sectionShift = dstSectionCount - 1 - usedSections[1];
            }
        }
        int shiftY = (dstMinBuildHeight - srcMinBuildHeight) + (sectionShift << 4);

        if (shiftX != 0 || shiftZ != 0) {
            plotTag.putInt("plot_x", freePlot[0]);
            plotTag.putInt("plot_z", freePlot[1]);
        }
        if (sectionShift != 0) {
            rekeySections(plotTag, sectionShift);
        }
        keySectionsForDestination(plotTag, dstLevel);
        if (shiftX != 0 || shiftY != 0 || shiftZ != 0) {
            offsetPlotCoordinates(plotTag, shiftX, shiftY, shiftZ);
            IPShipRopeCompat.shiftSavedAnchors(plotTag, data.uuid(), new BlockPos(shiftX, shiftY, shiftZ), dstLevel);

            Pose3d pose = SableNBTUtils.readPose3d(fullTag.getCompound("pose"));
            pose.rotationPoint().add(shiftX, shiftY, shiftZ);
            fullTag.put("pose", SableNBTUtils.writePose3d(pose));
        }
        refreshWorldBounds(fullTag);

        // SubLevelData caches pose and bounds parsed at creation; rebuild it from the edited tag
        SubLevelData edited = SubLevelSerializer.fromData(fullTag);
        if (edited == null) {
            LOGGER.warn("[IPShipPortal] edited ship data for {} failed to parse; aborting move", data.uuid());
            return null;
        }

        LOGGER.info(
            "[IPShipPortal] loading ship {} into {}: plot ({},{}) -> ({},{}), section shift {}, block shift ({}, {}, {})",
            data.uuid(), dstLevel.dimension().location(), oldPlotX, oldPlotZ, freePlot[0], freePlot[1], sectionShift, shiftX, shiftY, shiftZ
        );
        if (READ_PLOT_BEFORE_LOAD) {
            readPlotChunks(dstLevel, dstContainer, plotTag, freePlot);
        }
        ServerSubLevel loaded = SubLevelSerializer.fullyLoad(dstLevel, edited);
        if (loaded == null) {
            return null;
        }
        if (!IPShipLoadGuard.landedWhole(loaded, plotTag)) {
            // the callers restore the ship from its unedited copy, or keep it in the teleport journal
            dstContainer.removeSubLevel(loaded, SubLevelRemovalReason.REMOVED);
            return null;
        }
        return new IPSableBridge.Loaded(loaded, new BlockPos(shiftX, shiftY, shiftZ));
    }

    /** Lowest and highest section array index used by any chunk of the plot, or null if the plot is empty. */
    private static int @Nullable [] usedSectionIndexRange(CompoundTag plotTag) {
        CompoundTag chunks = plotTag.getCompound("chunks");
        int min = Integer.MAX_VALUE;
        int max = Integer.MIN_VALUE;
        for (String chunkKey : chunks.getAllKeys()) {
            CompoundTag sections = chunks.getCompound(chunkKey).getCompound("sections");
            for (String sectionKey : sections.getAllKeys()) {
                int index;
                try {
                    index = Integer.parseInt(sectionKey);
                } catch (NumberFormatException e) {
                    continue;
                }
                min = Math.min(min, index);
                max = Math.max(max, index);
            }
        }
        return min == Integer.MAX_VALUE ? null : new int[]{min, max};
    }

    /** Moves every section of every chunk from array index {@code i} to {@code i + sectionShift}. */
    private static void rekeySections(CompoundTag plotTag, int sectionShift) {
        CompoundTag chunks = plotTag.getCompound("chunks");
        for (String chunkKey : chunks.getAllKeys()) {
            CompoundTag chunk = chunks.getCompound(chunkKey);
            CompoundTag sections = chunk.getCompound("sections");
            CompoundTag rekeyed = new CompoundTag();
            for (String sectionKey : sections.getAllKeys()) {
                String newKey;
                try {
                    newKey = String.valueOf(Integer.parseInt(sectionKey) + sectionShift);
                } catch (NumberFormatException e) {
                    newKey = sectionKey;
                }
                rekeyed.put(newKey, sections.get(sectionKey));
            }
            chunk.put("sections", rekeyed);
        }
    }

    /**
     * Test switch: {@code -Dip_aeronautics.debug.keepMinSectionY=true} leaves Dimensional Sable's {@code min_section_y}
     * as saved, so a move between levels of different heights loses its blocks and {@link IPShipLoadGuard} must undo it.
     */
    private static final boolean DEBUG_KEEP_MIN_SECTION = Boolean.getBoolean("ip_aeronautics.debug.keepMinSectionY");

    /**
     * Dimensional Sable writes the saving level's lowest section into every plot it saves ({@code min_section_y}), and
     * on load adds {@code min_section_y - level.getMinSection()} to every section key ({@code ServerLevelPlotMixin},
     * 1.1.0): the re-keying {@link #rekeySections} has already done. Applied twice, an overworld ship loaded in the
     * nether or the End came back 64 blocks below its pose: its block entities found air and were dropped, and the
     * ship sat in the ground under the portal. The tag now says its sections
     * are keyed for the destination, so that mod adds nothing; Sable ignores the key.
     */
    private static void keySectionsForDestination(CompoundTag plotTag, ServerLevel dstLevel) {
        if (!plotTag.contains("min_section_y", Tag.TAG_INT)) {
            return;
        }
        if (DEBUG_KEEP_MIN_SECTION) {
            LOGGER.info("[IPShipPortal] test switch: min_section_y left as saved ({})", plotTag.getInt("min_section_y"));
            return;
        }
        plotTag.putInt("min_section_y", dstLevel.getMinSection());
    }

    /**
     * The stored world bounds are only consulted while a ship is held (chunks unloaded); Sable
     * rewrites them on every save. Until then, use a cube around the new position that is large
     * enough to contain the ship in any orientation.
     */
    private static void refreshWorldBounds(CompoundTag fullTag) {
        if (!fullTag.contains("world_bounds", Tag.TAG_COMPOUND)) {
            return;
        }
        BoundingBox3d old = SableNBTUtils.readBoundingBox(fullTag.getCompound("world_bounds"));
        Pose3d pose = SableNBTUtils.readPose3d(fullTag.getCompound("pose"));
        double halfDiagonal = 0.5 * Math.sqrt(
            (old.maxX - old.minX) * (old.maxX - old.minX)
                + (old.maxY - old.minY) * (old.maxY - old.minY)
                + (old.maxZ - old.minZ) * (old.maxZ - old.minZ)
        ) + 1.0;
        Vector3d c = pose.position();
        fullTag.put("world_bounds", SableNBTUtils.writeBoundingBox(new BoundingBox3d(
            c.x - halfDiagonal, c.y - halfDiagonal, c.z - halfDiagonal,
            c.x + halfDiagonal, c.y + halfDiagonal, c.z + halfDiagonal
        )));
    }

    private static void stripKineticState(CompoundTag plotTag, Set<Long> kineticPositions) {
        if (!kineticPositions.isEmpty()) {
            int stripped = 0;
            CompoundTag chunks = plotTag.getCompound("chunks");

            for (String chunkKey : chunks.getAllKeys()) {
                ListTag blockEntities = chunks.getCompound(chunkKey).getList("block_entities", Tag.TAG_COMPOUND);

                for (int i = 0; i < blockEntities.size(); i++) {
                    CompoundTag blockEntity = blockEntities.getCompound(i);
                    long packedPos = BlockPos.asLong(blockEntity.getInt("x"), blockEntity.getInt("y"), blockEntity.getInt("z"));
                    if (kineticPositions.contains(packedPos)) {
                        for (String key : KINETIC_KEYS) {
                            blockEntity.remove(key);
                        }

                        for (String childKey : blockEntity.getAllKeys()) {
                            if (blockEntity.get(childKey) instanceof CompoundTag child
                                && child.contains("Speed")
                                && (child.contains("Source") || child.contains("Network"))) {
                                for (String key : KINETIC_KEYS) {
                                    child.remove(key);
                                }
                            }
                        }

                        stripped++;
                    }
                }
            }

            LOGGER.debug("[IPShipPortal] stripped kinetic state from {} block entit(ies) so networks rebuild cold", stripped);
        }
    }

    private static void offsetPlotCoordinates(CompoundTag plotTag, int dx, int dy, int dz) {
        CompoundTag chunks = plotTag.getCompound("chunks");

        for (String chunkKey : chunks.getAllKeys()) {
            CompoundTag chunk = chunks.getCompound(chunkKey);
            offsetCoordinateList(chunk.getList("block_entities", Tag.TAG_COMPOUND), dx, dy, dz);
            offsetCoordinateList(chunk.getList("block_ticks", Tag.TAG_COMPOUND), dx, dy, dz);
            offsetCoordinateList(chunk.getList("fluid_ticks", Tag.TAG_COMPOUND), dx, dy, dz);
        }
    }

    private static void offsetCoordinateList(ListTag list, int dx, int dy, int dz) {
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            if (entry.contains("x", Tag.TAG_INT)) {
                entry.putInt("x", entry.getInt("x") + dx);
            }

            if (entry.contains("y", Tag.TAG_INT)) {
                entry.putInt("y", entry.getInt("y") + dy);
            }

            if (entry.contains("z", Tag.TAG_INT)) {
                entry.putInt("z", entry.getInt("z") + dz);
            }

            // Create's KineticBlockEntity "Controller" int[3] block position
            if (entry.contains("Controller", Tag.TAG_INT_ARRAY)) {
                int[] controller = entry.getIntArray("Controller");
                if (controller.length == 3) {
                    entry.putIntArray("Controller", new int[]{controller[0] + dx, controller[1] + dy, controller[2] + dz});
                }
            }
        }
    }

    /**
     * Test switch {@code -Dip_aeronautics.debug.readPlotBeforeLoad=true}: looks up every chunk of the destination plot
     * before the ship is loaded there, as code in game may (it leaves vanilla's last-chunk cache answering for them;
     * {@code MixinSableLevelPlot_ChunkCache}).
     */
    private static final boolean READ_PLOT_BEFORE_LOAD = Boolean.getBoolean("ip_aeronautics.debug.readPlotBeforeLoad");

    private static void readPlotChunks(ServerLevel level, ServerSubLevelContainer container, CompoundTag plotTag, int[] plot) {
        int logPlotSize = container.getLogPlotSize();
        int originX = container.getOrigin().x();
        int originZ = container.getOrigin().y();
        for (String key : plotTag.getCompound("chunks").getAllKeys()) {
            long local = Long.parseLong(key);
            int chunkX = ((plot[0] + originX) << logPlotSize) + ChunkPos.getX(local);
            int chunkZ = ((plot[1] + originZ) << logPlotSize) + ChunkPos.getZ(local);
            BlockPos pos = new BlockPos(chunkX << 4, level.getMinBuildHeight(), chunkZ << 4);
            LOGGER.info("[IPShipPortal] test: looked up {} before loading: {}", pos.toShortString(), level.getBlockState(pos));
        }
    }

    private static int[] findFreePlot(ServerSubLevelContainer container, int preferredX, int preferredZ) {
        BitSet occupancy = container.getOccupancy();
        int sideLength = 1 << container.getLogSideLength();
        if (isPlotFree(container, occupancy, sideLength, preferredX, preferredZ)) {
            return new int[]{preferredX, preferredZ};
        } else {
            for (int z = 0; z < sideLength; z++) {
                for (int x = 0; x < sideLength; x++) {
                    if (isPlotFree(container, occupancy, sideLength, x, z)) {
                        return new int[]{x, z};
                    }
                }
            }

            return null;
        }
    }

    /** A free plot other than ({@code avoidX}, {@code avoidZ}), or null. */
    private static int @Nullable [] findFreePlotOtherThan(ServerSubLevelContainer container, int avoidX, int avoidZ) {
        BitSet occupancy = container.getOccupancy();
        int sideLength = 1 << container.getLogSideLength();
        for (int z = 0; z < sideLength; z++) {
            for (int x = 0; x < sideLength; x++) {
                if ((x != avoidX || z != avoidZ) && isPlotFree(container, occupancy, sideLength, x, z)) {
                    return new int[]{x, z};
                }
            }
        }
        return null;
    }

    private static boolean isPlotFree(ServerSubLevelContainer container, BitSet occupancy, int sideLength, int x, int z) {
        return x >= 0 && x < sideLength && z >= 0 && z < sideLength ? !occupancy.get(container.getIndex(x, z)) : false;
    }

    public record Loaded(ServerSubLevel sub, BlockPos shift) {
    }

    /** Result of a successful move: the reloaded ship and the plot shift applied to its block coordinates. */
    public record Moved(ServerSubLevel sub, BlockPos shift) {
    }
}
