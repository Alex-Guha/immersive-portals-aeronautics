/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.foundation.blockEntity.SmartBlockEntity;
import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.simulated_team.simulated.content.blocks.rope.RopeStrandHolderBehavior;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.RopeAttachment;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.RopeAttachmentPoint;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerLevelRopeManager;
import dev.simulated_team.simulated.content.blocks.rope.strand.server.ServerRopeStrand;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.fml.ModList;
import org.slf4j.Logger;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Create Aeronautics ropes and ship crossings.
 * <p>
 * A rope strand is a physics object of the level it was created in; its two anchors name a block and,
 * when that block is on a ship, the ship's sub-level UUID. When IP moves a ship to another dimension the
 * strand stays behind with an anchor pointing at a sub-level that no longer exists there. Aeronautics'
 * {@code areAttachmentsLoaded} only checks the first sub-level anchor, so such a strand keeps ticking and
 * {@code reattachConstraints} hands Sable a constraint whose second body resolved to the world while its
 * anchor is still in plot space: "Constraint validation failed", server crash. A rope tied to anything that stays
 * behind (the world, a ship not crossing) is therefore broken first, through Aeronautics' own routine where possible
 * (coupling item drops, break sound and particles).
 * <p>
 * Ropes whose anchors are all on ships crossing together (both ends on one ship, or a balloon roped to its hull handed
 * off as a group) travel instead. The strand is saved by its owning holder's block entity ({@code Strand}: points in
 * world space, anchors as sub-level UUID + plot block position) and the other holder keeps the rope's UUID, so the
 * ship's own serialization carries the rope; Aeronautics rebuilds it when the holder loads. The crossing only maps the
 * saved points like the ship ({@link #mapSavedStrands}) and shifts anchors whose ship moved plot
 * ({@link #shiftSavedAnchors}, {@link #shiftLoadedAnchors}).
 */
public final class IPShipRopeCompat {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean SIMULATED_LOADED = ModList.get().isLoaded("simulated");

    private IPShipRopeCompat() {
    }

    /**
     * Breaks every rope with an anchor on {@code sub} unless all its anchors are on ships in {@code travelling} (which
     * includes {@code sub}); call before the ship's data is snapshotted. Returns the count broken.
     */
    public static int breakAttachedRopes(ServerLevel level, ServerSubLevel sub, Set<UUID> travelling) {
        if (!SIMULATED_LOADED) {
            return 0;
        }
        try {
            return Impl.breakAttachedRopes(level, sub, travelling);
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] aeronautics: breaking ropes attached to ship {} failed: {}", sub.getUniqueId(), e.toString());
            return 0;
        }
    }

    /**
     * @return the ships of {@code level} tied to {@code ship} by ropes, directly or through other ships, including
     * {@code ship}; ropes with an anchor in the world do not join ships
     */
    public static Set<UUID> ropeGroup(ServerLevel level, ServerSubLevel ship) {
        if (!SIMULATED_LOADED) {
            return Set.of(ship.getUniqueId());
        }
        try {
            return Impl.ropeGroup(level, ship.getUniqueId());
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] aeronautics: finding the ropes of ship {} failed: {}", ship.getUniqueId(), e.toString());
            return Set.of(ship.getUniqueId());
        }
    }

    /**
     * In a ship's serialized plot: maps the points of every rope strand saved there from the ship's old pose to its new
     * one ({@code p -> newPosition + newOrientation * oldOrientation^-1 * (p - oldPosition)}).
     *
     * @return the number of strands mapped
     */
    public static int mapSavedStrands(CompoundTag plotTag, Pose3dc oldPose, Pose3dc newPose, ServerLevel level) {
        if (!SIMULATED_LOADED) {
            return 0;
        }
        try {
            Vector3d oldPosition = new Vector3d(oldPose.position());
            Vector3d newPosition = new Vector3d(newPose.position());
            Quaterniond turn = new Quaterniond(newPose.orientation()).mul(new Quaterniond(oldPose.orientation()).conjugate());
            return Impl.editSavedStrands(plotTag, strand -> {
                for (Vector3d point : strand.getPoints()) {
                    point.sub(oldPosition);
                    turn.transform(point);
                    point.add(newPosition);
                }
            });
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] aeronautics: mapping the saved ropes failed: {}", e.toString());
            return 0;
        }
    }

    /** In a ship's serialized plot: moves the anchors on ship {@code shipId} of every saved strand by {@code shift}. */
    public static void shiftSavedAnchors(CompoundTag plotTag, UUID shipId, BlockPos shift, ServerLevel level) {
        if (!SIMULATED_LOADED || shift.equals(BlockPos.ZERO)) {
            return;
        }
        try {
            Impl.editSavedStrands(plotTag, strand -> Impl.shiftAnchors(level, strand, Map.of(shipId, shift)));
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] aeronautics: shifting the saved ropes of ship {} failed: {}", shipId, e.toString());
        }
    }

    /**
     * After ships tied together were moved one by one into {@code level}: moves each strand's anchors on the other moved
     * ships by their plot shifts (a strand's saved data only had its owner's own shift applied).
     */
    public static void shiftLoadedAnchors(ServerLevel level, Map<UUID, BlockPos> shifts) {
        if (!SIMULATED_LOADED) {
            return;
        }
        try {
            Impl.shiftLoadedAnchors(level, shifts);
        } catch (Throwable e) {
            LOGGER.warn("[IPShipPortal] aeronautics: shifting the ropes of moved ships failed: {}", e.toString());
        }
    }

    /**
     * Test hook ({@code /ip_aeronautics straddle debug rope}): ties the rope holders at the two world positions (each
     * resolved to a ship's plot when a ship covers it) with Aeronautics' own rope creation.
     *
     * @return null on success, else why not
     */
    @Nullable
    public static String tie(ServerLevel level, BlockPos from, BlockPos to) {
        if (!SIMULATED_LOADED) {
            return "Create Simulated is not installed";
        }
        return Impl.tie(level, from, to);
    }

    /**
     * Diagnostics: the ropes of {@code level} with an anchor on ship {@code shipId}, as "count (active n)".
     */
    public static String describeRopes(ServerLevel level, UUID shipId) {
        if (!SIMULATED_LOADED) {
            return "";
        }
        ServerLevelRopeManager manager = ServerLevelRopeManager.getOrCreate(level);
        if (manager == null) {
            return "";
        }
        int count = 0;
        int active = 0;
        double farthest = 0.0;
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        Vector3d shipPosition = container != null && container.getSubLevel(shipId) instanceof ServerSubLevel ship
            ? new Vector3d(ship.logicalPose().position()) : null;
        for (ServerRopeStrand strand : manager.getAllStrands()) {
            if (Impl.isAttachedTo(strand, shipId)) {
                count++;
                if (strand.isActive()) {
                    active++;
                }
                if (shipPosition != null) {
                    for (Vector3d point : strand.getPoints()) {
                        farthest = Math.max(farthest, point.distance(shipPosition));
                    }
                }
            }
        }
        return count == 0 ? "" : String.format(", ropes %d (%d in physics, farthest point %.1f from the ship)", count, active, farthest);
    }

    /**
     * After a full sync of {@code sub} was sent to {@code player}: forgets the player in the tracking sets of the
     * ropes attached to the ship, so the rope holders send their strands again. The full sync re-creates the
     * ship's block entities on the client, and a rope's client strand lives on its holder's block entity; a
     * player still counted as tracking (e.g. a portal viewer who walked through) would otherwise never get the
     * strand back. The re-send follows the sync on the same connection, so it lands after the chunk data.
     */
    public static void resendRopesAfterFullSync(ServerLevel level, ServerSubLevel sub, ServerPlayer player) {
        if (!SIMULATED_LOADED) {
            return;
        }
        try {
            Impl.forgetViewer(level, sub.getUniqueId(), player.getUUID());
        } catch (Throwable e) {
            LOGGER.debug("[IPShipPortal] aeronautics: rope re-send after full sync failed: {}", e.toString());
        }
    }

    private static final class Impl {
        static void forgetViewer(ServerLevel level, UUID shipId, UUID playerId) {
            ServerLevelRopeManager manager = ServerLevelRopeManager.getOrCreate(level);
            if (manager == null) {
                return;
            }
            for (ServerRopeStrand strand : manager.getAllStrands()) {
                for (RopeAttachment attachment : strand.getAttachments()) {
                    if (shipId.equals(attachment.subLevelID())) {
                        strand.getTrackingPlayers().remove(playerId);
                        break;
                    }
                }
            }
        }

        static int breakAttachedRopes(ServerLevel level, ServerSubLevel sub, Set<UUID> travelling) {
            UUID shipId = sub.getUniqueId();
            ServerLevelRopeManager manager = ServerLevelRopeManager.getOrCreate(level);
            if (manager == null) {
                return 0;
            }
            List<ServerRopeStrand> attached = new ArrayList<>();
            int travellingWith = 0;
            for (ServerRopeStrand strand : manager.getAllStrands()) {
                if (!isAttachedTo(strand, shipId)) {
                    continue;
                }
                if (allAnchorsOn(strand, travelling)) {
                    travellingWith++;
                } else {
                    attached.add(strand);
                }
            }
            if (travellingWith > 0) {
                LOGGER.info("[IPShipPortal] aeronautics: {} rope(s) of ship {} cross with it", travellingWith, shipId);
            }
            int broken = 0;
            for (ServerRopeStrand strand : attached) {
                if (destroyThroughOwner(level, strand) || removeDirectly(level, manager, strand)) {
                    broken++;
                }
            }
            if (broken > 0) {
                LOGGER.info(
                    "[IPShipPortal] aeronautics: broke {} rope(s) attached to ship {} before it crossed (ropes cannot follow a ship through a portal)",
                    broken, shipId
                );
            }
            return broken;
        }

        static boolean isAttachedTo(ServerRopeStrand strand, UUID shipId) {
            for (RopeAttachment attachment : strand.getAttachments()) {
                if (shipId.equals(attachment.subLevelID())) {
                    return true;
                }
            }
            return false;
        }

        /** Whether both of the strand's anchors are on ships in {@code ships}. */
        static boolean allAnchorsOn(ServerRopeStrand strand, Set<UUID> ships) {
            int anchors = 0;
            for (RopeAttachment attachment : strand.getAttachments()) {
                if (attachment.subLevelID() == null || !ships.contains(attachment.subLevelID())) {
                    return false;
                }
                anchors++;
            }
            return anchors == 2;
        }

        static Set<UUID> ropeGroup(ServerLevel level, UUID start) {
            Set<UUID> group = new HashSet<>();
            group.add(start);
            ServerLevelRopeManager manager = ServerLevelRopeManager.getOrCreate(level);
            if (manager == null || manager.getAllStrands().isEmpty()) {
                return group;
            }
            Deque<UUID> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                UUID ship = queue.poll();
                for (ServerRopeStrand strand : manager.getAllStrands()) {
                    if (!isAttachedTo(strand, ship)) {
                        continue;
                    }
                    for (RopeAttachment attachment : strand.getAttachments()) {
                        if (attachment.subLevelID() != null && group.add(attachment.subLevelID())) {
                            queue.add(attachment.subLevelID());
                        }
                    }
                }
            }
            return group;
        }

        /** Decodes every strand saved in the plot's block entities, lets {@code edit} change it and saves it back. */
        static int editSavedStrands(CompoundTag plotTag, java.util.function.Consumer<ServerRopeStrand> edit) {
            int edited = 0;
            CompoundTag chunks = plotTag.getCompound("chunks");
            for (String chunkKey : chunks.getAllKeys()) {
                ListTag blockEntities = chunks.getCompound(chunkKey).getList("block_entities", Tag.TAG_COMPOUND);
                for (int i = 0; i < blockEntities.size(); i++) {
                    CompoundTag blockEntity = blockEntities.getCompound(i);
                    if (!blockEntity.contains("Strand", Tag.TAG_COMPOUND)) {
                        continue;
                    }
                    ServerRopeStrand strand = ServerRopeStrand.CODEC.parse(NbtOps.INSTANCE, blockEntity.getCompound("Strand")).getOrThrow();
                    edit.accept(strand);
                    blockEntity.put("Strand", ServerRopeStrand.CODEC.encodeStart(NbtOps.INSTANCE, strand).getOrThrow());
                    edited++;
                }
            }
            return edited;
        }

        /** Moves the strand's anchors on the given ships by their shifts (the strand must not be in physics yet). */
        static void shiftAnchors(ServerLevel level, ServerRopeStrand strand, Map<UUID, BlockPos> shifts) {
            List<RopeAttachment> attachments = new ArrayList<>();
            strand.getAttachments().forEach(attachments::add);
            for (RopeAttachment attachment : attachments) {
                BlockPos shift = attachment.subLevelID() == null ? null : shifts.get(attachment.subLevelID());
                if (shift != null && !shift.equals(BlockPos.ZERO)) {
                    strand.addAttachment(level, attachment.point(),
                        new RopeAttachment(attachment.point(), attachment.subLevelID(), attachment.blockAttachment().offset(shift)));
                }
            }
        }

        /**
         * The strands owned by holders on the moved ships, still waiting for their holder's first tick (Aeronautics adds
         * a loaded strand to the level then): their anchors on the other moved ships get those ships' shifts.
         */
        static void shiftLoadedAnchors(ServerLevel level, Map<UUID, BlockPos> shifts) {
            if (shifts.values().stream().allMatch(BlockPos.ZERO::equals)) {
                return;
            }
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return;
            }
            for (UUID owner : shifts.keySet()) {
                if (!(container.getSubLevel(owner) instanceof ServerSubLevel ship)) {
                    continue;
                }
                Map<UUID, BlockPos> others = new java.util.HashMap<>(shifts);
                // the owner's own anchors were shifted in its saved data
                others.remove(owner);
                for (var holder : ship.getPlot().getLoadedChunks()) {
                    for (var blockEntity : holder.getChunk().getBlockEntities().values()) {
                        RopeStrandHolderBehavior behaviour = blockEntity instanceof SmartBlockEntity smart
                            ? smart.getBehaviour(RopeStrandHolderBehavior.TYPE) : null;
                        ServerRopeStrand strand = behaviour == null ? null : behaviour.getOwnedStrand();
                        if (strand != null && !strand.isActive()) {
                            shiftAnchors(level, strand, others);
                        }
                    }
                }
            }
        }

        static String tie(ServerLevel level, BlockPos from, BlockPos to) {
            RopeStrandHolderBehavior a = holderAt(level, from);
            RopeStrandHolderBehavior b = holderAt(level, to);
            if (a == null || b == null) {
                BlockPos missing = a == null ? from : to;
                StringBuilder tried = new StringBuilder();
                ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
                if (container != null) {
                    for (ServerSubLevel ship : container.getAllSubLevels()) {
                        BlockPos local = toPlot(ship, missing);
                        tried.append("; ship ").append(ship.getUniqueId()).append(" plot ").append(local.toShortString())
                            .append(": ").append(level.getBlockState(local));
                    }
                }
                return "no rope holder at " + missing.toShortString() + tried;
            }
            return a.createRope(b, false) ? null : "Aeronautics refused the rope (already tied, or too long)";
        }

        /** The rope holder at a world position, or at the plot position of a ship covering it. */
        @Nullable
        private static RopeStrandHolderBehavior holderAt(ServerLevel level, BlockPos worldPos) {
            RopeStrandHolderBehavior direct = behaviourAt(level, worldPos);
            if (direct != null) {
                return direct;
            }
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container == null) {
                return null;
            }
            for (ServerSubLevel ship : container.getAllSubLevels()) {
                RopeStrandHolderBehavior onShip = behaviourAt(level, toPlot(ship, worldPos));
                if (onShip != null) {
                    return onShip;
                }
            }
            return null;
        }

        /** The plot block of {@code ship} at the world position {@code worldPos} (its block centre). */
        private static BlockPos toPlot(ServerSubLevel ship, BlockPos worldPos) {
            // through the pose, which applies a ship's scale (Create: Pocket Sized)
            Vec3 local = ship.logicalPose().transformPositionInverse(Vec3.atCenterOf(worldPos));
            return BlockPos.containing(local.x, local.y, local.z);
        }

        @Nullable
        private static RopeStrandHolderBehavior behaviourAt(ServerLevel level, BlockPos pos) {
            return level.getBlockEntity(pos) instanceof SmartBlockEntity smart ? smart.getBehaviour(RopeStrandHolderBehavior.TYPE) : null;
        }

        /** The owning anchor block's behaviour knows how to destroy a rope properly (far end detached, item, effects). */
        private static boolean destroyThroughOwner(ServerLevel level, ServerRopeStrand strand) {
            RopeAttachment start = strand.getAttachment(RopeAttachmentPoint.START);
            if (start == null || !(level.getBlockEntity(start.blockAttachment()) instanceof SmartBlockEntity blockEntity)) {
                return false;
            }
            RopeStrandHolderBehavior holder = blockEntity.getBehaviour(RopeStrandHolderBehavior.TYPE);
            if (holder == null || holder.getOwnedStrand() != strand) {
                return false;
            }
            holder.destroyRope(null, null, true);
            return true;
        }

        /** Owner block unreachable: detach the far holder and drop the strand from physics and the manager. */
        private static boolean removeDirectly(ServerLevel level, ServerLevelRopeManager manager, ServerRopeStrand strand) {
            RopeAttachment end = strand.getAttachment(RopeAttachmentPoint.END);
            if (end != null && level.getBlockEntity(end.blockAttachment()) instanceof SmartBlockEntity blockEntity) {
                RopeStrandHolderBehavior holder = blockEntity.getBehaviour(RopeStrandHolderBehavior.TYPE);
                if (holder != null) {
                    holder.detachRope();
                    blockEntity.notifyUpdate();
                }
            }
            strand.removeConstraints();
            ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
            if (container != null && strand.isActive()) {
                container.physicsSystem().removeObject(strand);
            }
            manager.removeStrand(strand.getUUID());
            LOGGER.warn("[IPShipPortal] aeronautics: rope {} had no reachable owner block; removed it directly", strand.getUUID());
            return true;
        }
    }
}
