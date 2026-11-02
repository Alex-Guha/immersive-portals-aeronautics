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
import java.util.List;
import net.createmod.catnip.math.BlockFace;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

/**
 * Create {@link PortalTrackProvider} for IP portals, registered for {@link PortalPlaceholderBlock#instance}: Create
 * takes a block with a provider as a portal for its tracks. Create's own linking ({@code TrackBlock.connectToPortal})
 * places the exit track itself; at IP portals the player lays both ends and {@link IPCreatePortalTracks} links them
 * through {@link #exitSpot}: the block just past the other end of the portal, on the side the portal's transform
 * carries a train out to. Within one dimension too: the pair's edge becomes a portal edge ({@link IPCreatePortalEdges})
 * and each side of the portal a key of its own ({@link IPCreateTrainSides}).
 */
public final class IPCreatePortalTrackProvider implements PortalTrackProvider {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String TAG = "[IPCreatePortalTracks] ";
    /** How far a portal run (portal blocks one behind the other) is walked to its far side. */
    private static final int MAX_RUN = 8;

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
            ExitSpot spot = exitSpot(level, inboundTrack);
            return spot == null ? null : new Exit(spot.level(), new BlockFace(spot.pos(), spot.towardPortal()));
        } catch (Throwable t) {
            LOGGER.error(TAG + "findExit threw:", t);
            return null;
        }
    }

    /**
     * Where the exit track lies for a track entering a portal: {@code pos} in {@code level}, its face towards the portal
     * {@code towardPortal}.
     */
    public record ExitSpot(ServerLevel level, BlockPos pos, Direction towardPortal) {
    }

    /** The exit spot for the track at {@code inboundTrack} (its face towards the portal block), or null (logged). */
    public static @Nullable ExitSpot exitSpot(ServerLevel level, BlockFace inboundTrack) {
        ExitSpot throughShipPortal = shipPortalExitSpot(level, inboundTrack);
        if (throughShipPortal != null) {
            return throughShipPortal;
        }
        BlockPos portalPos = inboundTrack.getConnectedPos();
        Direction inboundFace = inboundTrack.getFace();
        Portal portal = findPortalAt(level, portalPos);
        if (portal == null) {
            logNoExit(level, portalPos, "no teleportable IP portal entity there");
            return null;
        }
        MinecraftServer server = level.getServer();
        ServerLevel destLevel = server.getLevel(portal.getDestDim());
        if (destLevel == null) {
            logNoExit(level, portalPos, "destination level " + portal.getDestDim().location() + " is not loaded");
            return null;
        }

        BlockPos destPos = BlockPos.containing(portal.transformPoint(Vec3.atCenterOf(portalPos)));
        destLevel.getChunk(destPos.getX() >> 4, destPos.getZ() >> 4);
        BlockPos destPortalBlock = findPlaceholderNear(destLevel, destPos);
        if (destPortalBlock == null) {
            logNoExit(level, portalPos, "no portal block near " + destPos + " in " + destLevel.dimension().location());
            return null;
        }

        // the direction a train leaves the other end in: the one it enters in, through the portal's rotation
        Vec3 out = portal.transformLocalVec(Vec3.atLowerCornerOf(inboundFace.getNormal()));
        Direction forward = Direction.getNearest(out.x, out.y, out.z);
        if (forward.getAxis().isVertical()) {
            logNoExit(level, portalPos, "the portal turns a track entering it upright");
            return null;
        }
        BlockPos pos = destPortalBlock;
        for (int i = 0; i < MAX_RUN; i++) {
            pos = pos.relative(forward);
            if (destLevel.getBlockState(pos).getBlock() != PortalPlaceholderBlock.instance) {
                if (destLevel == level && pos.equals(inboundTrack.getPos())) {
                    logNoExit(level, portalPos, "the portal leads back to the same track");
                    return null;
                }
                return new ExitSpot(destLevel, pos, forward.getOpposite());
            }
        }
        logNoExit(level, portalPos, "the portal at " + destPortalBlock + " is more than " + MAX_RUN + " blocks deep");
        return null;
    }

    /**
     * The exit spot through a portal with an end on a ship: either end of the track may lie in a ship's plot, the track
     * moves with its portal's end, so nothing here depends on where the ship is.
     */
    private static @Nullable ExitSpot shipPortalExitSpot(ServerLevel level, BlockFace inboundTrack) {
        if (!IPCreatePortalEnds.SABLE) {
            return null;
        }
        IPCreatePortalShafts.Frame frame = IPCreateShaftShips.frameAt(level, inboundTrack.getPos());
        IPCreatePortalEnds.Face face = IPCreatePortalEnds.face(frame, inboundTrack.getPos(), inboundTrack.getFace());
        for (Portal portal : qouteall.imm_ptl.core.compat.IPPortalLookup.touching(level, face.search(),
            p -> p.getTeleportable() && !(p instanceof qouteall.imm_ptl.core.portal.Mirror)
                && qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals.isShipMounted(p))) {
            IPCreatePortalEnds.Opening opening = IPCreatePortalEnds.opening(portal, frame, face);
            if (opening != null && opening.farLevel() instanceof ServerLevel farLevel) {
                ExitSpot spot = IPCreatePortalEnds.far(opening, (farFrame, pos, out, gap, plane) ->
                    farLevel.getBlockState(pos).getBlock() == PortalPlaceholderBlock.instance ? null : new ExitSpot(farLevel, pos, out.getOpposite()));
                if (spot != null) {
                    return spot;
                }
            }
        }
        return null;
    }

    private static void logNoExit(ServerLevel level, BlockPos portalPos, String reason) {
        LOGGER.info(TAG + "no exit for the track at the portal block {} in {}: {}", portalPos, level.dimension().location(), reason);
    }

    private static Portal findPortalAt(ServerLevel level, BlockPos pos) {
        Vec3 center = Vec3.atCenterOf(pos);
        List<Portal> candidates = qouteall.imm_ptl.core.compat.IPPortalLookup.touching(
            level, new AABB(pos).inflate(2.0),
            // Create's portal tracks cannot move: never on a ship-mounted portal
            p -> p.getTeleportable() && !(p instanceof qouteall.imm_ptl.core.portal.Mirror)
                && !qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals.isShipMounted(p)
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

}
