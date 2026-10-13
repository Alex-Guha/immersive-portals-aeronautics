/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.logging.LogUtils;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.BlockPos.MutableBlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.slf4j.Logger;

/**
 * Finds room for a ship's hull next to a portal, so a ship never arrives (or is shoved) into terrain.
 * <p>
 * The arrival position is the portal mirror of the ship's position, pushed out of the destination portal, so
 * it is only as good as the destination portal's surroundings: a portal whose exit side runs into a wall
 * (a nether portal generated half inside netherrack), or one standing on higher ground than the source portal,
 * puts the hull inside blocks. Sable does not push a body out of terrain; it sinks until it falls below the
 * world and Sable deletes it. The hull is therefore moved, before the move, to the nearest position where it
 * overlaps no block with a collision shape, no lava, no portal and no other ship. Terrain and other ships'
 * AABBs are tested against the cells the ship's blocks cover ({@link Shape}), not its own world AABB: a ship
 * that tumbled after its last arrival has an AABB several times its real size, which fits nowhere in a nether
 * cave. Portals are tested against the AABB, the box the portal scan itself uses. The search
 * goes outwards from the portal, up, sideways and a little down (never back towards the portal), within
 * {@value #REACH_SIDEWAYS} blocks sideways, {@value #REACH_UP} up and {@value #REACH_DOWN} down, and never above
 * the dimension's logical height (a nether arrival is not lifted above the roof). Positions with ground under
 * the hull are preferred over ones it would fall from. When nothing in reach is clear the caller refuses the
 * crossing.
 */
final class IPShipArrivalClearance {
    private static final Logger LOGGER = LogUtils.getLogger();
    /** Shrink applied to the probed box so a hull resting exactly on a block top is not read as overlapping it. */
    private static final double PROBE_INSET = 0.1;
    private static final int REACH_SIDEWAYS = 16;
    private static final int REACH_UP = 24;
    private static final int REACH_DOWN = 8;
    /** Search costs per block of offset: moving out of the portal and up are preferred to sideways and down. */
    private static final double COST_OUT = 1.0;
    private static final double COST_UP = 1.0;
    private static final double COST_SIDEWAYS = 1.5;
    private static final double COST_DOWN = 2.0;
    /**
     * Cost per block a hull would fall before something under its footprint catches it: a ship dropped from
     * high up tumbles on landing, possibly back into the portal it came through.
     */
    private static final double COST_FALL = 0.5;

    private IPShipArrivalClearance() {
    }

    /**
     * The blocks of a ship, for testing the cells it really covers instead of its world AABB (a tilted ship's AABB
     * is mostly air and can be several times its real size).
     *
     * @param blockOrigins per block, the world position of its minimum plot corner relative to the ship's pose position
     * @param edgeX        the world image of the plot's unit X edge (the same for every block); likewise Y and Z
     */
    record Shape(List<Vector3d> blockOrigins, Vector3d edgeX, Vector3d edgeY, Vector3d edgeZ) {
    }

    /**
     * @param srcHull  the ship's world AABB at the source
     * @param srcPos   the ship's pose position at the source
     * @param shape    the ship's blocks (relative to {@code srcPos}), or null to test the whole hull box
     * @param destPos  the arrival position computed so far
     * @param rotation the rotation the portal applies to the ship, or null when none
     * @param outward  unit direction pointing away from the destination portal; the search never moves against it
     * @param keepOut  boxes the hull's AABB must not intersect (portals)
     * @param solids   boxes treated like terrain, tested against the cells the ship covers (other ships)
     * @return {@code destPos} moved by the cheapest offset that frees the hull, or null when nothing in reach is clear
     */
    static @Nullable Vec3 findClearPosition(
        ServerLevel level, UUID shipId, AABB srcHull, Vec3 srcPos, @Nullable Shape shape, Vec3 destPos,
        @Nullable Quaterniond rotation, Vec3 outward, List<AABB> keepOut, List<AABB> solids
    ) {
        AABB hull = destinationHull(srcHull, srcPos, destPos, rotation);
        AABB probe = hull.deflate(PROBE_INSET);
        long[] shapeCells = shape == null ? null : shapeCells(shape, destPos, rotation);

        int hullMinX;
        int hullMinY;
        int hullMinZ;
        int hullSizeX;
        int hullSizeY;
        int hullSizeZ;
        if (shapeCells != null && shapeCells.length > 0) {
            int minX = Integer.MAX_VALUE;
            int minY = Integer.MAX_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int maxY = Integer.MIN_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (long cell : shapeCells) {
                minX = Math.min(minX, BlockPos.getX(cell));
                minY = Math.min(minY, BlockPos.getY(cell));
                minZ = Math.min(minZ, BlockPos.getZ(cell));
                maxX = Math.max(maxX, BlockPos.getX(cell));
                maxY = Math.max(maxY, BlockPos.getY(cell));
                maxZ = Math.max(maxZ, BlockPos.getZ(cell));
            }
            hullMinX = minX;
            hullMinY = minY;
            hullMinZ = minZ;
            hullSizeX = maxX - minX + 1;
            hullSizeY = maxY - minY + 1;
            hullSizeZ = maxZ - minZ + 1;
        } else {
            shapeCells = null;
            hullMinX = Mth.floor(probe.minX);
            hullMinY = Mth.floor(probe.minY);
            hullMinZ = Mth.floor(probe.minZ);
            hullSizeX = Math.max(1, Mth.ceil(probe.maxX) - hullMinX);
            hullSizeY = Math.max(1, Mth.ceil(probe.maxY) - hullMinY);
            hullSizeZ = Math.max(1, Mth.ceil(probe.maxZ) - hullMinZ);
        }

        BlockedGrid grid = new BlockedGrid(
            level,
            hullMinX - REACH_SIDEWAYS, hullMinY - REACH_DOWN, hullMinZ - REACH_SIDEWAYS,
            hullSizeX + 2 * REACH_SIDEWAYS, hullSizeY + REACH_DOWN + REACH_UP, hullSizeZ + 2 * REACH_SIDEWAYS,
            solids
        );

        // the shape's cells relative to the corner of its cell box, packed as x, y, z triples
        int[] relativeCells = null;
        if (shapeCells != null) {
            relativeCells = new int[shapeCells.length * 3];
            for (int i = 0; i < shapeCells.length; i++) {
                relativeCells[3 * i] = BlockPos.getX(shapeCells[i]) - hullMinX;
                relativeCells[3 * i + 1] = BlockPos.getY(shapeCells[i]) - hullMinY;
                relativeCells[3 * i + 2] = BlockPos.getZ(shapeCells[i]) - hullMinZ;
            }
        }

        int bestX = 0;
        int bestY = 0;
        int bestZ = 0;
        double bestCost = Double.POSITIVE_INFINITY;
        boolean found = false;
        for (int dx = -REACH_SIDEWAYS; dx <= REACH_SIDEWAYS; dx++) {
            for (int dy = -REACH_DOWN; dy <= REACH_UP; dy++) {
                for (int dz = -REACH_SIDEWAYS; dz <= REACH_SIDEWAYS; dz++) {
                    double cost = offsetCost(dx, dy, dz, outward);
                    if (cost >= bestCost) {
                        continue;
                    }
                    int gridX = REACH_SIDEWAYS + dx;
                    int gridY = REACH_DOWN + dy;
                    int gridZ = REACH_SIDEWAYS + dz;
                    if (grid.countBlocked(gridX, gridY, gridZ, hullSizeX, hullSizeY, hullSizeZ) != 0
                        && (relativeCells == null || grid.anyBlocked(gridX, gridY, gridZ, relativeCells))) {
                        continue;
                    }
                    if (intersectsAny(hull.move(dx, dy, dz), keepOut)) {
                        continue;
                    }
                    cost += COST_FALL * grid.fallDepth(gridX, gridY, gridZ, hullSizeX, hullSizeZ);
                    if (cost >= bestCost) {
                        continue;
                    }
                    bestX = dx;
                    bestY = dy;
                    bestZ = dz;
                    bestCost = cost;
                    found = true;
                }
            }
        }

        if (!found) {
            LOGGER.info(
                "[IPShipPortal] no clear spot for ship {} near {} in {} (hull {} x {} x {} blocks, {} cells tested{})",
                shipId, destPos, level.dimension().location(), hullSizeX, hullSizeY, hullSizeZ,
                relativeCells == null ? hullSizeX * hullSizeY * hullSizeZ : relativeCells.length / 3,
                relativeCells == null ? ", whole box" : ", block shape"
            );
            return null;
        }

        Vec3 result = destPos.add(bestX, bestY, bestZ);
        if (bestX != 0 || bestY != 0 || bestZ != 0) {
            LOGGER.info(
                "[IPShipPortal] ship {} would overlap terrain or a portal in {}; moved by ({}, {}, {}) to {}",
                shipId, level.dimension().location(), bestX, bestY, bestZ, result
            );
        }
        return result;
    }

    /**
     * The world cells covered by the ship's blocks at {@code destPos}, turned by {@code rotation}: for each block,
     * the cells its rotated cube's AABB overlaps (shrunk by {@link #PROBE_INSET}), deduplicated.
     */
    private static long[] shapeCells(Shape shape, Vec3 destPos, @Nullable Quaterniond rotation) {
        Vector3d edgeX = new Vector3d(shape.edgeX());
        Vector3d edgeY = new Vector3d(shape.edgeY());
        Vector3d edgeZ = new Vector3d(shape.edgeZ());
        if (rotation != null) {
            rotation.transform(edgeX);
            rotation.transform(edgeY);
            rotation.transform(edgeZ);
        }
        double lowX = Math.min(0.0, edgeX.x) + Math.min(0.0, edgeY.x) + Math.min(0.0, edgeZ.x) + PROBE_INSET;
        double lowY = Math.min(0.0, edgeX.y) + Math.min(0.0, edgeY.y) + Math.min(0.0, edgeZ.y) + PROBE_INSET;
        double lowZ = Math.min(0.0, edgeX.z) + Math.min(0.0, edgeY.z) + Math.min(0.0, edgeZ.z) + PROBE_INSET;
        double highX = Math.max(0.0, edgeX.x) + Math.max(0.0, edgeY.x) + Math.max(0.0, edgeZ.x) - PROBE_INSET;
        double highY = Math.max(0.0, edgeX.y) + Math.max(0.0, edgeY.y) + Math.max(0.0, edgeZ.y) - PROBE_INSET;
        double highZ = Math.max(0.0, edgeX.z) + Math.max(0.0, edgeY.z) + Math.max(0.0, edgeZ.z) - PROBE_INSET;

        LongOpenHashSet cells = new LongOpenHashSet();
        Vector3d origin = new Vector3d();
        for (Vector3d blockOrigin : shape.blockOrigins()) {
            origin.set(blockOrigin);
            if (rotation != null) {
                rotation.transform(origin);
            }
            origin.add(destPos.x, destPos.y, destPos.z);
            int minX = Mth.floor(origin.x + lowX);
            int minY = Mth.floor(origin.y + lowY);
            int minZ = Mth.floor(origin.z + lowZ);
            int maxX = Mth.floor(origin.x + highX);
            int maxY = Mth.floor(origin.y + highY);
            int maxZ = Mth.floor(origin.z + highZ);
            for (int x = minX; x <= maxX; x++) {
                for (int y = minY; y <= maxY; y++) {
                    for (int z = minZ; z <= maxZ; z++) {
                        cells.add(BlockPos.asLong(x, y, z));
                    }
                }
            }
        }
        return cells.toLongArray();
    }

    /** Infinite for offsets that move back towards the portal, otherwise the weighted length of the offset. */
    private static double offsetCost(int dx, int dy, int dz, Vec3 outward) {
        double along = dx * outward.x + dy * outward.y + dz * outward.z;
        if (along < -1.0E-6) {
            return Double.POSITIVE_INFINITY;
        }
        double restX = dx - along * outward.x;
        double restY = dy - along * outward.y;
        double restZ = dz - along * outward.z;
        double sideways = Math.abs(restX) + Math.abs(restZ);
        double vertical = restY > 0.0 ? restY * COST_UP : -restY * COST_DOWN;
        return along * COST_OUT + vertical + sideways * COST_SIDEWAYS;
    }

    private static boolean intersectsAny(AABB box, List<AABB> boxes) {
        for (AABB other : boxes) {
            if (box.intersects(other)) {
                return true;
            }
        }
        return false;
    }

    /** The source hull, expressed as offsets from the ship position, rotated by the portal and moved to the arrival point. */
    private static AABB destinationHull(AABB srcHull, Vec3 srcPos, Vec3 destPos, @Nullable Quaterniond rotation) {
        if (rotation == null) {
            return srcHull.move(destPos.subtract(srcPos));
        }

        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        Vector3d corner = new Vector3d();
        for (int i = 0; i < 8; i++) {
            corner.set(
                ((i & 1) == 0 ? srcHull.minX : srcHull.maxX) - srcPos.x,
                ((i & 2) == 0 ? srcHull.minY : srcHull.maxY) - srcPos.y,
                ((i & 4) == 0 ? srcHull.minZ : srcHull.maxZ) - srcPos.z
            );
            rotation.transform(corner);
            minX = Math.min(minX, corner.x);
            minY = Math.min(minY, corner.y);
            minZ = Math.min(minZ, corner.z);
            maxX = Math.max(maxX, corner.x);
            maxY = Math.max(maxY, corner.y);
            maxZ = Math.max(maxZ, corner.z);
        }

        return new AABB(minX, minY, minZ, maxX, maxY, maxZ).move(destPos);
    }

    /**
     * Blocked cells of a box of the world, with a 3D prefix sum so the number of blocked cells in any sub-box is
     * read in constant time. A cell is blocked when it holds a block with a collision shape or lava, overlaps one
     * of the solid boxes, or lies outside the build height or above the logical height.
     */
    private static final class BlockedGrid {
        private final int sizeY;
        private final int sizeZ;
        /** Prefix sums, (sizeX + 1) * (sizeY + 1) * (sizeZ + 1), index 0 on every axis is the empty prefix. */
        private final int[] sums;
        /** The blocked cells themselves, sizeX * sizeY * sizeZ. */
        private final boolean[] blocked;

        BlockedGrid(ServerLevel level, int originX, int originY, int originZ, int sizeX, int sizeY, int sizeZ, List<AABB> solids) {
            this.sizeY = sizeY;
            this.sizeZ = sizeZ;
            this.sums = new int[(sizeX + 1) * (sizeY + 1) * (sizeZ + 1)];
            this.blocked = new boolean[sizeX * sizeY * sizeZ];

            int floor = level.getMinBuildHeight();
            int ceiling = Math.min(level.getMaxBuildHeight(), level.getMinBuildHeight() + level.dimensionType().logicalHeight());
            MutableBlockPos pos = new MutableBlockPos();
            for (int x = 0; x < sizeX; x++) {
                for (int z = 0; z < sizeZ; z++) {
                    int worldX = originX + x;
                    int worldZ = originZ + z;
                    // loads (or generates) the chunk like Level.getBlockState would: the ship is about to be placed here
                    LevelChunk chunk = level.getChunk(SectionPos.blockToSectionCoord(worldX), SectionPos.blockToSectionCoord(worldZ));
                    for (int y = 0; y < sizeY; y++) {
                        int worldY = originY + y;
                        boolean blocked;
                        if (worldY < floor || worldY >= ceiling) {
                            blocked = true;
                        } else {
                            pos.set(worldX, worldY, worldZ);
                            BlockState state = chunk.getBlockState(pos);
                            blocked = !state.isAir()
                                && (!state.getCollisionShape(level, pos).isEmpty() || state.getFluidState().is(FluidTags.LAVA));
                        }
                        this.sums[index(x + 1, y + 1, z + 1)] = blocked ? 1 : 0;
                        this.blocked[(x * sizeY + y) * sizeZ + z] = blocked;
                    }
                }
            }

            for (AABB solid : solids) {
                AABB box = solid.deflate(PROBE_INSET);
                int minX = Math.max(0, Mth.floor(box.minX) - originX);
                int minY = Math.max(0, Mth.floor(box.minY) - originY);
                int minZ = Math.max(0, Mth.floor(box.minZ) - originZ);
                int maxX = Math.min(sizeX - 1, Mth.floor(box.maxX) - originX);
                int maxY = Math.min(sizeY - 1, Mth.floor(box.maxY) - originY);
                int maxZ = Math.min(sizeZ - 1, Mth.floor(box.maxZ) - originZ);
                for (int x = minX; x <= maxX; x++) {
                    for (int y = minY; y <= maxY; y++) {
                        for (int z = minZ; z <= maxZ; z++) {
                            this.sums[index(x + 1, y + 1, z + 1)] = 1;
                            this.blocked[(x * sizeY + y) * sizeZ + z] = true;
                        }
                    }
                }
            }

            for (int x = 1; x <= sizeX; x++) {
                for (int y = 1; y <= sizeY; y++) {
                    for (int z = 1; z <= sizeZ; z++) {
                        this.sums[index(x, y, z)] += this.sums[index(x - 1, y, z)] + this.sums[index(x, y - 1, z)] + this.sums[index(x, y, z - 1)]
                            - this.sums[index(x - 1, y - 1, z)] - this.sums[index(x - 1, y, z - 1)] - this.sums[index(x, y - 1, z - 1)]
                            + this.sums[index(x - 1, y - 1, z - 1)];
                    }
                }
            }
        }

        private int index(int x, int y, int z) {
            return (x * (this.sizeY + 1) + y) * (this.sizeZ + 1) + z;
        }

        /**
         * How many free layers lie under the footprint starting at grid cell (x, y, z) before the first layer with
         * a blocked cell in it; the whole depth to the bottom of the grid when there is none.
         */
        int fallDepth(int x, int y, int z, int sizeX, int sizeZ) {
            if (y == 0 || countBlocked(x, 0, z, sizeX, y, sizeZ) == 0) {
                return y;
            }
            // smallest depth d such that the d layers under the footprint contain a blocked cell
            int low = 1;
            int high = y;
            while (low < high) {
                int mid = (low + high) >>> 1;
                if (countBlocked(x, y - mid, z, sizeX, mid, sizeZ) > 0) {
                    high = mid;
                } else {
                    low = mid + 1;
                }
            }
            return low - 1;
        }

        /** Whether any of the cells (x, y, z triples relative to grid cell (x, y, z)) is blocked. */
        boolean anyBlocked(int x, int y, int z, int[] cells) {
            for (int i = 0; i < cells.length; i += 3) {
                int cellX = x + cells[i];
                int cellY = y + cells[i + 1];
                int cellZ = z + cells[i + 2];
                if (this.blocked[(cellX * this.sizeY + cellY) * this.sizeZ + cellZ]) {
                    return true;
                }
            }
            return false;
        }

        /** Blocked cells in the box starting at grid cell (x, y, z) with the given size. */
        int countBlocked(int x, int y, int z, int sizeX, int sizeY, int sizeZ) {
            int x1 = x + sizeX;
            int y1 = y + sizeY;
            int z1 = z + sizeZ;
            return this.sums[index(x1, y1, z1)]
                - this.sums[index(x, y1, z1)] - this.sums[index(x1, y, z1)] - this.sums[index(x1, y1, z)]
                + this.sums[index(x, y, z1)] + this.sums[index(x, y1, z)] + this.sums[index(x1, y, z)]
                - this.sums[index(x, y, z)];
        }
    }
}
