/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;
import qouteall.imm_ptl.core.portal.PortalState;
import qouteall.imm_ptl.core.portal.animation.DeltaUnilateralPortalState;
import qouteall.imm_ptl.core.portal.animation.UnilateralPortalState;
import qouteall.imm_ptl.core.portal.nether_portal.BlockPortalShape;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;

import java.util.UUID;

/**
 * Lighting a nether portal on a ship (docs/lessons/ship-mounted-portals.md). IP's own generation runs on the
 * frame in the ship's plot; the other side is searched for or placed from the ship's world position, and the new
 * ship-side portal is put at the ship's world pose before IP derives the other three portals from it, then mounted.
 * Only called through {@link IPShipPortals} with Sable present.
 */
final class IPShipPortalGeneration {
    private IPShipPortalGeneration() {
    }

    private record Token(UUID carrier, UnilateralPortalState plotState, DeltaUnilateralPortalState delta) {
    }

    @Nullable
    static String whyNotOnShip(final ServerLevel level, final BlockPos plotPos) {
        final SubLevel ship = Sable.HELPER.getContaining(level, plotPos);
        if (ship == null || ship.isRemoved()) {
            return "no ship owns that plot position";
        }
        return null;
    }

    static BlockPos worldPositionOf(final ServerLevel level, final BlockPos plotPos) {
        final SubLevel ship = Sable.HELPER.getContaining(level, plotPos);
        if (ship == null) {
            return plotPos;
        }
        final Vector3d world = ship.logicalPose().transformPosition(
                new Vector3d(plotPos.getX() + 0.5, plotPos.getY() + 0.5, plotPos.getZ() + 0.5));
        return BlockPos.containing(world.x, world.y, world.z);
    }

    @Nullable
    static Object placeOnShip(final BreakablePortalEntity f1, final BlockPortalShape fromShape) {
        final SubLevel ship = Sable.HELPER.getContaining(f1.level(), fromShape.anchor);
        if (ship == null || ship.isRemoved()) {
            return null;
        }
        final PortalState state = f1.getPortalState();
        final UnilateralPortalState plotState = UnilateralPortalState.extractThisSide(state);
        final UnilateralPortalState otherSide = UnilateralPortalState.extractOtherSide(state);
        final DeltaUnilateralPortalState delta = IPShipPortalDriver.deltaFor(ship.logicalPose(), plotState.position());
        f1.setPortalState(UnilateralPortalState.combine(plotState.apply(delta), otherSide));
        return new Token(ship.getUniqueId(), plotState, delta);
    }

    static void mount(final BreakablePortalEntity f1, final Object token) {
        final Token t = (Token) token;
        IPShipPortalAnchors.attach(f1, t.carrier(), t.plotState(), t.delta());
    }
}
