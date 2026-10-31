/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import com.mojang.logging.LogUtils;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3d;
import dev.ryanhcode.sable.sublevel.SubLevel;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.PortalExtension;
import qouteall.imm_ptl.core.portal.PortalManipulation;
import qouteall.imm_ptl.peripheral.wand.ProtoPortal;
import qouteall.imm_ptl.peripheral.wand.ProtoPortalSide;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * IP's portal wand on ships (docs/plans/custom-mounts.md, step 3), server side; only called with Sable present. A corner
 * the wand puts on a ship's block is in that ship's plot (Sable's raycast gives plot coordinates), and the client keeps
 * the rest of that side in the same plot ({@code IPShipPortalWandClient}). So a side drawn on a ship arrives in plot
 * coordinates: it is mapped into the world at the ship's pose now, the portals are made there as usual, and that side
 * is mounted on the ship like a custom portal mounted by command.
 */
public final class IPShipPortalWand {
    private static final Logger LOGGER = LogUtils.getLogger();

    private IPShipPortalWand() {
    }

    /** Why a wand portal cannot be made, for the player. */
    public static final class Refusal extends Exception {
        Refusal(final String message) {
            super(message);
        }
    }

    /** The side of a wand portal drawn on a ship ({@code secondSide}, or the first), mapped into the world at {@code pose}. */
    public static final class DrawnSide {
        private final boolean secondSide;
        private final UUID ship;
        private final Pose3d pose;

        private DrawnSide(final boolean secondSide, final UUID ship, final Pose3d pose) {
            this.secondSide = secondSide;
            this.ship = ship;
            this.pose = pose;
        }
    }

    /**
     * Maps each side of {@code proto} drawn on a ship into the world, in place, at its ship's current pose.
     *
     * @return those sides (none, one or both; both may be on one ship)
     * @throws Refusal when a ship is gone, or ship-mounted portals are switched off
     */
    public static List<DrawnSide> mapToWorld(final ProtoPortal proto) throws Refusal {
        final List<DrawnSide> drawn = new ArrayList<>();
        for (final DrawnSide side : new DrawnSide[]{drawnSide(proto.firstSide, false), drawnSide(proto.secondSide, true)}) {
            if (side != null) {
                drawn.add(side);
            }
        }
        if (!drawn.isEmpty() && !IPShipPortals.isEnabled()) {
            throw new Refusal("Portals on ships are switched off (the shipPortalMode option)");
        }
        for (final DrawnSide each : drawn) {
            final ProtoPortalSide side = each.secondSide ? proto.secondSide : proto.firstSide;
            side.leftBottom = each.pose.transformPosition(side.leftBottom);
            if (side.rightBottom != null) {
                side.rightBottom = each.pose.transformPosition(side.rightBottom);
            }
            if (side.leftTop != null) {
                side.leftTop = each.pose.transformPosition(side.leftTop);
            }
        }
        return drawn;
    }

    /** The ship a wand side was drawn on (its first corner in the ship's plot), or null for a side in the world. */
    @Nullable
    private static DrawnSide drawnSide(@Nullable final ProtoPortalSide side, final boolean secondSide) throws Refusal {
        if (side == null || !inPlot(side.leftBottom)) {
            return null;
        }
        final ServerLevel level = McHelper.getServerWorld(side.dimension);
        final SubLevel ship = Sable.HELPER.getContaining(level, side.leftBottom);
        if (ship == null || ship.isRemoved()) {
            throw new Refusal("The ship this portal was drawn on is gone");
        }
        return new DrawnSide(secondSide, ship.getUniqueId(), new Pose3d(ship.logicalPose()));
    }

    private static boolean inPlot(final Vec3 pos) {
        return IPSableCompatHelper.isPlotSpacePosition(pos.x, pos.z);
    }

    /**
     * Mounts the drawn sides of a new wand cluster on their ships: the first side's ship carries the portal's own end,
     * the second side's its far end. The four portals are linked first: IP links a cluster on its next tick, and the
     * ship sides have to move with their ships from this tick on.
     */
    public static void mount(final List<DrawnSide> drawn, final Portal portal, final Portal flipped, final Portal reverse,
                             final Portal parallel) {
        for (final Portal p : List.of(portal, flipped, reverse, parallel)) {
            final PortalExtension ext = PortalExtension.get(p);
            ext.flippedPortal = PortalManipulation.findFlippedPortal(p);
            ext.reversePortal = PortalManipulation.findReversePortal(p);
            ext.parallelPortal = PortalManipulation.findParallelPortal(p);
            ext.flippedPortalId = ext.flippedPortal == null ? null : ext.flippedPortal.getUUID();
            ext.reversePortalId = ext.reversePortal == null ? null : ext.reversePortal.getUUID();
            ext.parallelPortalId = ext.parallelPortal == null ? null : ext.parallelPortal.getUUID();
            if (ext.flippedPortal == null || ext.reversePortal == null || ext.parallelPortal == null) {
                LOGGER.warn("[IPShipPortal] wand portal {} is missing part of its cluster", p);
            }
        }
        for (final DrawnSide side : drawn) {
            IPShipPortalAnchors.mountEnd(side.secondSide ? reverse : portal, side.ship,
                    world -> IPShipPortalAnchors.toPlot(world, side.pose));
            LOGGER.info("[IPShipPortal] wand portal's {} side drawn on ship {}", side.secondSide ? "second" : "first", side.ship);
        }
    }
}
