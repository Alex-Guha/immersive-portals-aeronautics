/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalSectionPieces;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalPieceKind;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalPieceWalk;
import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalPieceTargets;
import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.AllShapes;
import com.simibubi.create.content.trains.track.TrackBlock;
import com.simibubi.create.content.trains.track.TrackShape;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceKey;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.client.event.AddSectionGeometryEvent;
import net.neoforged.neoforge.client.model.data.ModelData;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortals;


/**
 * The far half of a track's bridge into a portal when the other end has no track ({@link IPCreatePortalTracks}): a
 * track at a portal is a portal-shaped track whose rails run half a block into the portal block, up to the plane
 * ({@link IPCreatePortalTrackModel}); past the plane the portal shows the far world, where a track at the exit spot
 * would draw the other half. Without one the bridge looked cut off at the plane. So the far portal block gets that half:
 * the part of Create's portal-track model inside the portal block, as a portal track at the exit spot would draw it,
 * clipped at the portal block's face (Create's rails run as single quads from the track block to the plane).
 * <p>
 * As for pipes ({@link IPCreatePortalPipeStubs}): the portal blocks are never meshed, so the piece is added to the far
 * world's chunk mesh through NeoForge's {@link AddSectionGeometryEvent}; which far blocks get one is found on the client
 * thread before the frame after a block entity or portal block changes (a portal track has a block entity), and every
 * {@code IPPortalPieceWalk.SCAN_TICKS} ticks for the rest. Not for ship-mounted portals: they predate track links
 * through mounted portals (2.8.3) and were not extended to them (docs/completed/ship-trains.md).
 */
public final class IPCreatePortalTrackStubs {
    private static final RandomSource RANDOM = RandomSource.create();

    /**
     * A far portal block {@code cell} that gets the half of a bridge a portal track {@code track} at {@code spot} (the
     * exit spot, empty) would draw into it.
     */
    private record Stub(BlockPos cell, BlockPos spot, BlockState track) {
    }

    /** A stub's shape, by the way it runs out of the plane: a straight track's ({@link IPPortalPieceTargets}). */
    private static final VoxelShape[] PIECES = IPPortalPieceTargets.halves(
        d -> d.getAxis().isVertical() ? net.minecraft.world.phys.shapes.Shapes.empty() : AllShapes.TRACK_ORTHO.get(d));

    /** The stubs in the far world's chunk mesh, by the section of the far portal block. */
    private static final IPPortalSectionPieces<Stub> STUBS = IPPortalSectionPieces.create(IPCreatePortalTrackStubs::render);

    private IPCreatePortalTrackStubs() {
    }

    /** The walk's track kind ({@link IPCreatePortalPieces}): a track change calls for a walk. */
    static final IPPortalPieceWalk.Scanner SCANNER = new IPPortalPieceWalk.Scanner() {
        @Override
        public IPPortalPieceWalk.Scan newScan() {
            return new TrackScan();
        }

        @Override
        public boolean matters(final BlockState state) {
            return state.getBlock() instanceof TrackBlock;
        }
    };

    /** The stubs found in a walk: portals in the world, between world tracks. */
    static final class TrackScan implements IPPortalPieceWalk.Scan {
        private final Map<ResourceKey<Level>, Map<Long, List<Stub>>> found = new HashMap<>();
        /** The same, for the crosshair. */
        private final Map<ResourceKey<Level>, Map<BlockPos, IPPortalPieceTargets.Piece>> targets = new HashMap<>();

        @Override
        public boolean wants(final IPPortalPieceWalk.Side side) {
            return side.frame() == null && side.farWorld() != null && IPShipPortals.clusterMountOf(side.portal()) == null
                && !side.front().getAxis().isVertical() && !out(side).getAxis().isVertical();
        }

        /** A train going into the portal from the front side moves this way, and comes out of the far end that way. */
        private static Direction out(final IPPortalPieceWalk.Side side) {
            final Vec3 outVector = side.portal().transformLocalVec(Vec3.atLowerCornerOf(side.front().getOpposite().getNormal()));
            return Direction.getNearest(outVector.x, outVector.y, outVector.z);
        }

        @Override
        public void cell(final IPPortalPieceWalk.Side side, final BlockPos cell, final Vec3 worldCentre) {
            final ClientLevel world = side.world();
            final Direction front = side.front();
            final Direction in = front.getOpposite();
            final BlockState track = world.getBlockState(cell.relative(front));
            if (!(track.getBlock() instanceof TrackBlock) || track.getValue(TrackBlock.SHAPE) != TrackShape.asPortal(in)) {
                return;
            }
            final IPPortalPieceWalk.FarCell far = side.far(worldCentre);
            if (far == null) {
                return;
            }
            final ClientLevel farWorld = far.world();
            final BlockPos farCell = far.cell();
            final Direction out = far.out();
            final BlockPos spot = far.end();
            final BlockState there = farWorld.getBlockState(spot);
            final TrackShape farShape = TrackShape.asPortal(out.getOpposite());
            if (there.getBlock() instanceof TrackBlock && there.getValue(TrackBlock.SHAPE) == farShape) {
                return; // the far end's own track draws its half
            }
            if (IPPortalPieceKind.outranked(farWorld, spot, out.getOpposite(), IPCreatePortalEnds.TRACK)) {
                return; // a belt, shaft or pipe there owns the space between ({@link IPCreatePortalEnds})
            }
            IPPortalSectionPieces.add(found, farWorld.dimension(), farCell,
                new Stub(farCell.immutable(), spot.immutable(), track.setValue(TrackBlock.SHAPE, farShape)));
            targets.computeIfAbsent(farWorld.dimension(), d -> new HashMap<>())
                .put(farCell.immutable(), new IPPortalPieceTargets.Piece(PIECES[out.ordinal()], track.getBlock().asItem()));
        }

        @Override
        public void finish(final Collection<ClientLevel> worlds, final boolean important) {
            STUBS.publish(found, important);
            IPPortalPieceTargets.set(IPCreatePortalEnds.TRACK, targets);
        }
    }

    private static void render(final AddSectionGeometryEvent.SectionRenderingContext context, final BlockPos origin, final Stub stub) {
        final BlockAndTintGetter region = context.getRegion();
        final BakedModel model = Minecraft.getInstance().getBlockRenderer().getBlockModel(stub.track());
        final ModelData data = model.getModelData(region, stub.spot(), stub.track(), ModelData.EMPTY);
        final Direction toPortal = Direction.getNearest(
            stub.cell().getX() - stub.spot().getX(), stub.cell().getY() - stub.spot().getY(), stub.cell().getZ() - stub.spot().getZ());
        final int light = LevelRenderer.getLightColor(region, stub.cell());
        final PoseStack poses = context.getPoseStack();
        poses.pushPose();
        poses.translate(stub.spot().getX() - origin.getX(), stub.spot().getY() - origin.getY(), stub.spot().getZ() - origin.getZ());
        IPPortalSectionPieces.putModel(context, poses, model, stub.track(), data, RANDOM, light, quad -> IPPortalSectionPieces.pastFace(quad, toPortal));
        poses.popPose();
    }
}
