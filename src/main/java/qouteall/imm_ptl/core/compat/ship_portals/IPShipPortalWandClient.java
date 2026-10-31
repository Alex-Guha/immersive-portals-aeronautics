/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.ship_portals;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Quaternionf;
import org.joml.Vector3dc;
import qouteall.imm_ptl.core.compat.sable_integration.IPSableCompatHelper;
import qouteall.imm_ptl.peripheral.wand.ProtoPortal;
import qouteall.imm_ptl.peripheral.wand.ProtoPortalSide;

/**
 * IP's portal wand on ships (docs/plans/custom-mounts.md, step 3), client side; only called with Sable present.
 * <p>
 * Every point the wand keeps (corners, cursor, the plane and circle that constrain the next corner) is in a frame: the
 * world, or the plot of the ship it was put on, which its coordinates tell. Sable's raycast already gives plot
 * coordinates for a ship's block, so a corner clicked on a ship lands in its plot; the next corners of that side are
 * traced against the side's plane in the same plot, so they move with the ship however it flies. Points in a plot are
 * drawn through the ship's render pose. The server maps each side drawn on a ship into the world and mounts it
 * ({@link IPShipPortalWand}); both sides may be on ships.
 * <p>
 * The cursor is traced once a tick ({@code ClientPortalWandPortalCreation.updateDisplay}), with the eye and the ship
 * both at the current tick so that a moving ship does not shift it.
 */
public final class IPShipPortalWandClient {
    private IPShipPortalWandClient() {
    }

    /** Whether a wand point is in a ship's plot (else in the world). */
    public static boolean inPlot(final Vec3 pos) {
        return IPSableCompatHelper.isPlotSpacePosition(pos.x, pos.z);
    }

    @Nullable
    private static ClientSubLevel shipAt(final Vec3 pos) {
        final ClientLevel level = Minecraft.getInstance().level;
        return level != null && Sable.HELPER.getContaining(level, pos) instanceof final ClientSubLevel ship
                && !ship.isRemoved() ? ship : null;
    }

    /** Whether two wand points are in one frame: both in the world, or both on the same ship. */
    public static boolean sameFrame(final Vec3 a, final Vec3 b) {
        if (!inPlot(a) || !inPlot(b)) {
            return inPlot(a) == inPlot(b);
        }
        final ClientSubLevel ship = shipAt(a);
        return ship != null && ship == shipAt(b);
    }

    /** A block pick at the current tick; on a ship its location is in the ship's plot. */
    public static HitResult pick(final Player player, final double range) {
        return player.pick(range, 1.0f, false);
    }

    /**
     * The player's view ray at the current tick (eye, direction) in the frame of {@code framePos}, or null when that is
     * a ship this client does not have.
     */
    public static Vec3 @Nullable [] viewRay(final Player player, final Vec3 framePos) {
        final Vec3 eye = Sable.HELPER.getEyePositionInterpolated(player, 1.0f);
        final Vec3 direction = player.getViewVector(1.0f);
        if (!inPlot(framePos)) {
            return new Vec3[]{eye, direction};
        }
        final ClientSubLevel ship = shipAt(framePos);
        if (ship == null) {
            return null;
        }
        final Pose3dc pose = ship.logicalPose();
        return new Vec3[]{pose.transformPositionInverse(eye), pose.transformNormalInverse(direction)};
    }

    /**
     * Prepares {@code stack} for drawing points of {@code framePos}'s ship frame, which renderers take relative to the
     * returned camera: it pushes the ship's render rotation and scale (relative positions in the plot rotate into the
     * world's; the plot's large coordinates never reach a float). Pops nothing; the caller pops when this returns non-null.
     *
     * @return the camera {@code camera} in the ship's plot, or null (nothing pushed) when this client does not have the ship
     */
    @Nullable
    public static Vec3 pushShipFrame(final PoseStack stack, final Vec3 framePos, final Vec3 camera) {
        final ClientSubLevel ship = shipAt(framePos);
        if (ship == null) {
            return null;
        }
        final Pose3dc pose = ship.renderPose();
        final Vector3dc scale = pose.scale();
        stack.pushPose();
        stack.mulPose(new Quaternionf(pose.orientation()));
        stack.scale((float) scale.x(), (float) scale.y(), (float) scale.z());
        return pose.transformPositionInverse(camera);
    }

    /** Why the wand's corners in {@code pending} cannot be where they are on ships, for the player, or null if they can. */
    @Nullable
    public static String whyNot(final ProtoPortal pending) {
        if (!sideInOneFrame(pending.firstSide) || !sideInOneFrame(pending.secondSide)) {
            return "The corners of one side must all be on the same ship, or all off ships";
        }
        return null;
    }

    private static boolean sideInOneFrame(@Nullable final ProtoPortalSide side) {
        return side == null
                || (side.rightBottom == null || sameFrame(side.leftBottom, side.rightBottom))
                && (side.leftTop == null || sameFrame(side.leftBottom, side.leftTop));
    }
}
