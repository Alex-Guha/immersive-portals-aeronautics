/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;

/**
 * Client: an elevator's frame ({@link IPElevatorFrames}) in the world as drawn this frame, with the ship's render pose.
 * Without Sable every frame is the world.
 */
public final class IPElevatorFramesClient {
    private IPElevatorFramesClient() {
    }

    /** A point in {@code entity}'s frame, in the world as drawn. */
    public static Vec3 pointToWorld(final Entity entity, final Vec3 inFrame) {
        final Pose3dc pose = renderPoseOf(entity);
        return pose == null ? inFrame : pose.transformPosition(inFrame);
    }

    /** A direction in {@code entity}'s frame, in the world as drawn. */
    public static Vec3 normalToWorld(final Entity entity, final Vec3 inFrame) {
        final Pose3dc pose = renderPoseOf(entity);
        return pose == null ? inFrame : pose.transformNormal(inFrame);
    }

    /**
     * Pushes onto {@code poseStack} the move to {@code pos} in its frame, relative to the camera: through the ship's
     * render pose for a plot position, worked out in doubles (plot coordinates lose whole blocks in a float matrix).
     */
    public static void pushAt(final Level level, final BlockPos pos, final double camX, final double camY, final double camZ,
                              final PoseStack poseStack) {
        poseStack.pushPose();
        final Pose3dc pose = IPElevatorFrames.SABLE && Sable.HELPER.getContaining(level, pos) instanceof final ClientSubLevel ship
            ? ship.renderPose() : null;
        if (pose == null) {
            poseStack.translate(pos.getX() - camX, pos.getY() - camY, pos.getZ() - camZ);
            return;
        }
        final Matrix4d matrix = new Matrix4d().translation(-camX, -camY, -camZ)
            .mul(pose.bakeIntoMatrix(new Matrix4d()))
            .translate(pos.getX(), pos.getY(), pos.getZ());
        final Matrix4f drawing = new Matrix4f(matrix);
        poseStack.last().pose().mul(drawing);
        poseStack.last().normal().mul(new Matrix3f(drawing).normal());
    }

    private static @Nullable Pose3dc renderPoseOf(final Entity entity) {
        return IPElevatorFrames.SABLE && Sable.HELPER.getContaining(entity) instanceof final ClientSubLevel ship ? ship.renderPose() : null;
    }
}
