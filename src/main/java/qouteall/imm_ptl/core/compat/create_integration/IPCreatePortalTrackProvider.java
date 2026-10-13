/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import com.simibubi.create.api.contraption.train.PortalTrackProvider;
import com.simibubi.create.api.contraption.train.PortalTrackProvider.Exit;
import java.util.List;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

/**
 * Create {@link PortalTrackProvider} for IP portals, registered for
 * {@link PortalPlaceholderBlock#instance} so Create trains can path through them.
 * Finds the teleportable cross-dimension {@link Portal} near the track end, transforms the
 * point to the destination, loads that chunk, searches for the destination placeholder block,
 * walks out of the portal run both ways choosing a replaceable landing block, and may clear
 * a solid non-obsidian block with {@code setBlock(AIR)} to make room for the track.
 */
public final class IPCreatePortalTrackProvider implements PortalTrackProvider {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TAG = "[IPCreatePortalTracks] ";

    private IPCreatePortalTrackProvider() {
    }

    public static void register() {
        try {
            PortalTrackProvider.REGISTRY.register(PortalPlaceholderBlock.instance, new IPCreatePortalTrackProvider());
            LOGGER.debug(TAG + "registered IP portal as Create portal-track");
        } catch (Throwable t) {
            LOGGER.error(TAG + "registration failed:", t);
        }
    }

    @Override
    public Exit findExit(ServerLevel level, BlockFace inboundTrack) {
        try {
            return resolve(level, inboundTrack);
        } catch (Throwable t) {
            LOGGER.error(TAG + "findExit threw:", t);
            return null;
        }
    }

    private static Exit resolve(ServerLevel level, BlockFace inboundTrack) {
        BlockPos portalPos = inboundTrack.getConnectedPos();
        Direction inboundFace = inboundTrack.getFace();
        Vec3 portalCenter = Vec3.atCenterOf(portalPos);
        Portal portal = findPortalAt(level, portalPos);
        if (portal == null) {
            logNoExit(level, portalPos, "no teleportable cross-dimension IP portal entity there");
            return null;
        }
        if (!portal.getTeleportable()) {
            logNoExit(level, portalPos, "portal is not teleportable");
            return null;
        }
        MinecraftServer server = level.getServer();
        ServerLevel destLevel = server.getLevel(portal.getDestDim());
        if (destLevel == null) {
            logNoExit(level, portalPos, "destination level " + portal.getDestDim().location() + " is not loaded");
            return null;
        }

        Vec3 destCenter = portal.transformPoint(portalCenter);
        BlockPos destPos = BlockPos.containing(destCenter);
        destLevel.getChunk(destPos.getX() >> 4, destPos.getZ() >> 4);
        BlockPos destPortalBlock = findPlaceholderNear(destLevel, destPos);
        if (destPortalBlock == null) {
            logNoExit(
                level, portalPos,
                "no PortalPlaceholderBlock near " + destPos + " in " + destLevel.dimension().location()
                    + " (destination portal not generated/lit yet)"
            );
            return null;
        }

        BlockState destPortalState = destLevel.getBlockState(destPortalBlock);
        Direction.Axis destAxis = destPortalState.getValue(PortalPlaceholderBlock.AXIS);
        Direction forward = inboundFace;
        if (inboundFace.getAxis() != destAxis) {
            forward = inboundFace.getClockWise();
        }

        Landing landing = chooseLanding(destLevel, destPortalBlock, forward);
        if (landing == null) {
            logNoExit(
                level, portalPos,
                "both exit sides at " + destPortalBlock + " are obsidian/portal frame; refusing to clear them"
            );
            return null;
        }

        Direction exitFace = landing.dir.getOpposite();
        LOGGER.debug(
            TAG + "exit for track at {} in {}: {} in {} face={} (destPortalBlock={}, destAxis={}, clearedLandingBlock={})",
            portalPos, level.dimension().location(), landing.pos, destLevel.dimension().location(), exitFace,
            destPortalBlock, destAxis, landing.clearedBlock
        );
        return new Exit(destLevel, new BlockFace(landing.pos, exitFace));
    }

    private static void logNoExit(ServerLevel level, BlockPos portalPos, String reason) {
        LOGGER.debug(TAG + "no exit for track at {} in {}: {}", portalPos, level.dimension().location(), reason);
    }

    /**
     * Picks where the track lands on the destination side: walks out of the portal run forward and
     * backward, prefers a replaceable block (the nearer one when both are), and otherwise clears a
     * non-protected solid block to make room. Returns null when both sides are protected.
     */
    private static Landing chooseLanding(ServerLevel destLevel, BlockPos destPortalBlock, Direction forward) {
        Direction backward = forward.getOpposite();
        LandingSpot forwardSpot = walkOutOfPortalRun(destLevel, destPortalBlock, forward);
        LandingSpot backwardSpot = walkOutOfPortalRun(destLevel, destPortalBlock, backward);

        boolean preferBackward = !forwardSpot.replaceable
            || (backwardSpot.replaceable && forwardSpot.steps > backwardSpot.steps);
        if (!preferBackward) {
            return new Landing(forwardSpot.pos, forward, false);
        }
        if (backwardSpot.replaceable) {
            return new Landing(backwardSpot.pos, backward, false);
        }

        // neither side is replaceable: clear a non-protected one
        boolean forwardClearable = !forwardSpot.protectedBlock;
        boolean backwardClearable = !backwardSpot.protectedBlock;
        if (!forwardClearable && !backwardClearable) {
            return null;
        }

        BlockPos landingPos;
        Direction landingDir;
        if (forwardClearable && backwardClearable) {
            int forwardOpenness = openness(destLevel, forwardSpot.pos, forward);
            int backwardOpenness = openness(destLevel, backwardSpot.pos, backward);
            if (backwardOpenness > forwardOpenness) {
                landingPos = backwardSpot.pos;
                landingDir = backward;
            } else {
                landingPos = forwardSpot.pos;
                landingDir = forward;
            }
        } else if (forwardClearable) {
            landingPos = forwardSpot.pos;
            landingDir = forward;
        } else {
            landingPos = backwardSpot.pos;
            landingDir = backward;
        }

        // TODO: destructive - this silently replaces a solid block in the destination world with air
        //  (any block except obsidian/portal placeholder, including player-built ones). On the bug list.
        destLevel.setBlock(landingPos, Blocks.AIR.defaultBlockState(), 3);
        return new Landing(landingPos, landingDir, true);
    }

    private static boolean isProtected(BlockState state) {
        return state.getBlock() == Blocks.OBSIDIAN || state.getBlock() == PortalPlaceholderBlock.instance;
    }

    /**
     * Steps from {@code start} in {@code direction} (up to 8 blocks) until the first block that is
     * not a portal placeholder and describes it; if the run is longer than that, describes the last
     * block visited.
     */
    private static LandingSpot walkOutOfPortalRun(ServerLevel level, BlockPos start, Direction direction) {
        BlockPos pos = start;
        int steps = 0;

        for (int i = 0; i < 8; i++) {
            pos = pos.relative(direction);
            steps++;
            BlockState state = level.getBlockState(pos);
            if (state.getBlock() != PortalPlaceholderBlock.instance) {
                return new LandingSpot(pos, state.canBeReplaced(), isProtected(state), steps);
            }
        }

        BlockState state = level.getBlockState(pos);
        return new LandingSpot(pos, state.canBeReplaced(), isProtected(state), steps);
    }

    private static int openness(ServerLevel level, BlockPos start, Direction direction) {
        int open = 0;
        BlockPos pos = start;

        for (int i = 0; i < 3; i++) {
            if (level.getBlockState(pos).canBeReplaced()) {
                open++;
            }

            pos = pos.relative(direction);
        }

        return open;
    }

    private static Portal findPortalAt(ServerLevel level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        List<Portal> candidates = qouteall.imm_ptl.core.compat.IPPortalLookup.touching(
            level, new AABB(pos).inflate(2.0),
            p -> p.getTeleportable() && !p.getDestDim().equals(level.dimension())
        );
        Portal best = null;
        double bestDistSq = Double.MAX_VALUE;

        for (Portal candidate : candidates) {
            boolean contains = candidate.getBoundingBox().inflate(1.0).contains(center);
            double distSq = candidate.getOriginPos().distanceToSqr(center);
            if (contains && distSq < bestDistSq) {
                best = candidate;
                bestDistSq = distSq;
            }
        }

        if (best != null) {
            return best;
        }
        return candidates.isEmpty() ? null : candidates.get(0);
    }

    private static BlockPos findPlaceholderNear(ServerLevel level, BlockPos center) {
        if (level.getBlockState(center).getBlock() == PortalPlaceholderBlock.instance) {
            return center;
        }
        for (int radius = 1; radius <= 5; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -radius; dy <= radius; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos pos = center.offset(dx, dy, dz);
                        if (level.getBlockState(pos).getBlock() == PortalPlaceholderBlock.instance) {
                            return pos;
                        }
                    }
                }
            }
        }

        return null;
    }

    /** First non-placeholder block found walking out of the portal run in one direction. */
    private record LandingSpot(BlockPos pos, boolean replaceable, boolean protectedBlock, int steps) {
    }

    /** Chosen landing block, the direction walked to reach it, and whether a block was cleared there. */
    private record Landing(BlockPos pos, Direction dir, boolean clearedBlock) {
    }
}
