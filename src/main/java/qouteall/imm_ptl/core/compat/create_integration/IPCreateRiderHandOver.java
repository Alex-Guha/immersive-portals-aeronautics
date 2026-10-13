/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.content.trains.entity.Carriage;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.compat.mixin_create.IPCarriageAccessor;

/**
 * Server: which copy of a carriage crossing an IP portal carries the riders of a seat (docs/lessons/create.md, 1.24.12).
 * <p>
 * Create hands riders over between copies by its whole-block cut ({@code DimensionalCarriageEntity
 * .updatePassengerLoadout}: a seat outside {@code min/maxAllowedLocalCoord} goes to the other copy), measured from the
 * portal-track node and only when the cut moves. That cut lags IP's plane by up to a block, more since the copies are
 * moved towards the portal ({@link IPCreateCarriageCrossing#shift}): a seated player's head went past the portal in
 * the dimension it was in, so the player saw that world behind the portal and stuck out of it for the others. While a
 * carriage crosses an IP portal, a seat now belongs to the copy on whose side of the plane its rider's head is, with a
 * small band against handing a rider to and fro, and the hand-over is checked every tick.
 */
public final class IPCreateRiderHandOver {
    /** A seat stays with a copy until its head is this far past the plane (the band is twice this). */
    private static final double MARGIN = 0.05;
    /** Height of a seated player's head above the seat block's bottom, in the carriage's frame. */
    private static final double HEAD = 1.2;

    private IPCreateRiderHandOver() {
    }

    /** @return whether the riders of the seat at {@code seat} (contraption-local) belong to this copy */
    public static boolean isOnOwnSide(final CarriageContraptionEntity entity, final IPCreateCarriageCrossing.Crossing crossing,
                                      final BlockPos seat) {
        final Vec3 head = entity.toGlobalVector(new Vec3(seat.getX() + 0.5, seat.getY() + HEAD, seat.getZ() + 0.5), 1.0f);
        return head.subtract(crossing.keepPoint()).dot(crossing.keepNormal()) > -MARGIN;
    }

    /** Server, after Create placed the carriage's copies for this tick: hands riders over at the plane. */
    public static void tick(final Carriage carriage, final Level level) {
        if (IPCreateCarriageCrossing.STOCK || level.isClientSide()) {
            return;
        }
        final List<Carriage.DimensionalCarriageEntity> copies =
            new ArrayList<>(((IPCarriageAccessor) carriage).ip_getEntities().values());
        if (copies.size() < 2) {
            return;
        }
        for (final Carriage.DimensionalCarriageEntity dce : copies) {
            final CarriageContraptionEntity entity = dce == null ? null : dce.entity.get();
            if (entity == null || entity.isRemoved() || IPCreateCarriageCrossing.of(entity, dce) == null) {
                continue;
            }
            dce.updatePassengerLoadout();
        }
    }
}
