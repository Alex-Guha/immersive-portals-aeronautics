/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.Contraption;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Create contraptions on a Sable ship (bearings, Offroad's borehead, pistons) as part of the ship for crossing detection
 * ({@code IPShipCrossingDetector}). Their blocks are not in the ship's plot but in the contraption entity, which Sable
 * keeps in the plot, so a contraption hanging past the hull (a drill head below its bearing) reached a portal before
 * the ship did: no straddle session yet, so no far half and no cut until the hull got there. Only called with Create
 * loaded.
 */
public final class IPCreateShipContraptions {
    /** Contraption entities found per ship, for the game tick they were looked up in. */
    private static final Map<UUID, Found> FOUND = new HashMap<>();

    private record Found(ServerSubLevel ship, long tick, List<AbstractContraptionEntity> entities) {
    }

    private IPCreateShipContraptions() {
    }

    private static List<AbstractContraptionEntity> on(final ServerSubLevel ship) {
        final long tick = ship.getLevel().getGameTime();
        final Found found = FOUND.get(ship.getUniqueId());
        if (found != null && found.ship() == ship && found.tick() == tick) {
            return found.entities();
        }
        final var box = ship.getPlot().getBoundingBox();
        final AABB plot = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1).inflate(1.0);
        final List<AbstractContraptionEntity> entities = ship.getLevel().getEntitiesOfClass(AbstractContraptionEntity.class, plot,
            e -> !e.isRemoved() && e.getContraption() != null && Sable.HELPER.getContaining(e) == ship);
        if (FOUND.size() > 256) {
            FOUND.clear();
        }
        FOUND.put(ship.getUniqueId(), new Found(ship, tick, entities));
        return entities;
    }

    /**
     * Adds the blocks (plot coordinates) of the ship's contraptions where they are now, each by the cell of its centre.
     *
     * @return whether any were added
     */
    public static boolean addBlocks(final ServerSubLevel ship, final List<BlockPos> out) {
        boolean added = false;
        for (final AbstractContraptionEntity entity : on(ship)) {
            final Contraption contraption = entity.getContraption();
            for (final BlockPos local : contraption.getBlocks().keySet()) {
                out.add(BlockPos.containing(entity.toGlobalVector(Vec3.atCenterOf(local), 1.0f)));
                added = true;
            }
        }
        return added;
    }

    /**
     * @return the union of the ship's contraptions' bounding boxes (plot coordinates; a bearing contraption's covers
     * every angle it turns to), or null when it has none
     */
    @Nullable
    public static AABB extent(final ServerSubLevel ship) {
        AABB union = null;
        for (final AbstractContraptionEntity entity : on(ship)) {
            final AABB box = entity.getBoundingBox();
            union = union == null ? box : union.minmax(box);
        }
        return union;
    }
}
