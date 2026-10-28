/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.kinetics.belt.BeltBlock;
import com.simibubi.create.content.kinetics.belt.BeltBlockEntity;
import com.simibubi.create.content.kinetics.belt.BeltRenderer;
import com.simibubi.create.content.kinetics.belt.BeltSlope;
import com.simibubi.create.content.kinetics.belt.transport.BeltInventory;
import com.simibubi.create.content.kinetics.belt.transport.TransportedItemStack;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Vec3i;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3d;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.compat.iris_integration.IPIrisCompat;
import qouteall.imm_ptl.core.compat.mixin_create.IPBeltRendererInvoker;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPPlaneClippingBufferSource;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Items on belts at a portal ({@link IPCreatePortalBelts}), drawn as one item passing through: Create draws a belt's
 * items in the belt's own world, whole, so an item running on to the plane stuck out of the portal's back and then
 * vanished when it was handed over. Near a portal at either end, a belt's items are cut at the plane on their own side
 * ({@link #clipNear}, around {@code BeltRenderer.renderItems}), and the part past the plane is drawn in the world
 * behind the portal, moved through it and cut the other way ({@link #onRenderStage}, Create's own item drawing). The
 * item just handed over sits on the far belt at the far plane and is cut the same way, so the two halves meet at the
 * plane. Belts on ships at their own mounted portal too: the far side is drawn through the ship's pose as drawn. Both
 * cuts are made on the CPU ({@link IPPlaneClippingBufferSource}): IP's clip plane for entities crossing
 * portals is off with Iris installed.
 */
public final class IPCreatePortalBeltsClient {
    /** True while an item is drawn through a portal (Create's frustum test would use its own world's position). */
    private static boolean projecting;
    private static @Nullable IPPlaneClippingBufferSource clipping;

    private IPCreatePortalBeltsClient() {
    }

    public static boolean isProjecting() {
        return projecting;
    }

    /**
     * {@code BeltRenderer.renderItems}' buffer for a belt with a portal at an end: cut at each plane, the belt's side
     * kept. {@code pose} is the block entity's.
     */
    public static MultiBufferSource clipNear(final BeltBlockEntity be, final PoseStack pose, final MultiBufferSource buffer) {
        clipping = null;
        if (projecting || !be.isController()) {
            return buffer;
        }
        final IPCreatePortalBelts.Ends ends = IPCreatePortalBelts.clientEnds(be);
        if (ends == null) {
            return buffer;
        }
        MultiBufferSource result = buffer;
        for (final IPCreatePortalBelts.Exit exit : new IPCreatePortalBelts.Exit[]{ends.start(), ends.end()}) {
            if (exit != null) {
                final Vector3f[] drawing = drawingPlane(be.getBlockPos(), pose.last().pose(), exit.nearOnPlane(), exit.into().scale(-1));
                result = new IPPlaneClippingBufferSource(result, drawing[0], drawing[1]);
            }
        }
        clipping = result instanceof IPPlaneClippingBufferSource source ? source : null;
        return result;
    }

    /** After {@code renderItems}: emits what the cut buffers still hold. */
    public static void afterRenderItems() {
        if (clipping != null) {
            clipping.flush();
            clipping = null;
        }
    }

    /**
     * Draws, in the world being drawn, the parts of other worlds' belt items that lie past a portal leading here: each
     * item within reach of the plane, placed through the portal and cut to this side of the far plane. Also in portal
     * passes, where the item is seen inside the portal's view.
     */
    public static void onRenderStage(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        final Map<BeltBlockEntity, IPCreatePortalBelts.Ends> belts = IPCreatePortalBelts.clientBelts();
        if (level == null || belts.isEmpty()) {
            return;
        }
        final List<Runnable> draws = new ArrayList<>();
        final Vec3 camera = event.getCamera().getPosition();
        final float partialTicks = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        final MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        for (final Map.Entry<BeltBlockEntity, IPCreatePortalBelts.Ends> entry : belts.entrySet()) {
            final BeltBlockEntity be = entry.getKey();
            if (be.isRemoved() || be.getLevel() == null
                || !(minecraft.getBlockEntityRenderDispatcher().getRenderer(be) instanceof BeltRenderer renderer)) {
                continue;
            }
            final IPCreatePortalBelts.Ends ends = entry.getValue();
            for (final boolean atEnd : new boolean[]{false, true}) {
                final IPCreatePortalBelts.Exit exit = atEnd ? ends.end() : ends.start();
                if (exit != null && exit.farLevel() == level) {
                    draws.add(() -> project(be, (BeltRenderer) renderer, exit, atEnd, camera, partialTicks, buffers));
                }
            }
        }
        if (draws.isEmpty()) {
            return;
        }
        projecting = true;
        try {
            draws.forEach(Runnable::run);
        } finally {
            projecting = false;
        }
        if (!IPIrisCompat.isShaderPackInUse()) {
            buffers.endBatch();
        }
    }

    private static void project(
        final BeltBlockEntity be, final BeltRenderer renderer, final IPCreatePortalBelts.Exit exit, final boolean atEnd,
        final Vec3 camera, final float partialTicks, final MultiBufferSource.BufferSource buffers
    ) {
        final BeltInventory inventory = be.getInventory();
        if (inventory == null) {
            return;
        }
        final float plane = IPCreatePortalBelts.planePosition(be, exit, atEnd);
        final List<TransportedItemStack> past = new ArrayList<>();
        for (final TransportedItemStack stack : inventory.getTransportedItems()) {
            if (atEnd ? stack.beltPosition > plane - IPCreatePortalBelts.ITEM_REACH : stack.beltPosition < plane + IPCreatePortalBelts.ITEM_REACH) {
                past.add(stack);
            }
        }
        final TransportedItemStack lazy = inventory.getLazyClientItem();
        if (lazy != null && (atEnd ? lazy.beltPosition > plane - IPCreatePortalBelts.ITEM_REACH : lazy.beltPosition < plane + IPCreatePortalBelts.ITEM_REACH)) {
            past.add(lazy);
        }
        if (past.isEmpty()) {
            return;
        }
        // drawing space is the drawn world's minus the camera; the belt's block is placed through the portal, into the
        // far side's coordinates, and from there (a ship) by the ship's pose as it is drawn
        final Matrix4d farPose = farPose(exit);
        if (farPose == null) {
            return;
        }
        final BlockPos pos = be.getBlockPos();
        final Matrix4d place = new Matrix4d().translation(-camera.x, -camera.y, -camera.z).mul(farPose).mul(exit.nearToFar())
            .translate(pos.getX(), pos.getY(), pos.getZ());
        final PoseStack pose = new PoseStack();
        final Matrix4f drawing = new Matrix4f(place);
        pose.last().pose().set(drawing);
        pose.last().normal().set(new Matrix3f(drawing).normal());

        final Vector3d farPlane = farPose.transformPosition(new Vector3d(exit.farOnPlane().x, exit.farOnPlane().y, exit.farOnPlane().z));
        final Vector3d farNormal = farPose.transformDirection(new Vector3d(
            exit.farFacing().getStepX(), exit.farFacing().getStepY(), exit.farFacing().getStepZ())).normalize();
        final Vector3f anchor = new Vector3f((float) (farPlane.x - camera.x), (float) (farPlane.y - camera.y), (float) (farPlane.z - camera.z));
        final IPPlaneClippingBufferSource cut = new IPPlaneClippingBufferSource(
            IPIrisCompat.beginBlockEntity(buffers, be.getBlockState()), new Vector3f((float) farNormal.x, (float) farNormal.y, (float) farNormal.z), anchor);
        try {
            // as BeltRenderer.renderItems sets up its items
            final Direction facing = be.getBlockState().getValue(BlockStateProperties.HORIZONTAL_FACING);
            final Vec3i direction = facing.getNormal();
            final Vec3 startOffset = Vec3.atLowerCornerOf(direction).scale(-0.5).add(0.5, 0.9375, 0.5);
            pose.translate(startOffset.x, startOffset.y, startOffset.z);
            final BeltSlope slope = be.getBlockState().getValue(BeltBlock.SLOPE);
            final int verticality = slope == BeltSlope.DOWNWARD ? -1 : slope == BeltSlope.UPWARD ? 1 : 0;
            for (final TransportedItemStack stack : past) {
                ((IPBeltRendererInvoker) renderer).ip$renderItem(be, partialTicks, pose, cut, 0, OverlayTexture.NO_OVERLAY, facing,
                    direction, slope, verticality, facing.getAxis() == Direction.Axis.X, false, stack, startOffset);
            }
        } finally {
            cut.flush();
            IPIrisCompat.endBlockEntity();
        }
    }

    /** The far side's coordinates to the world's as drawn this frame: a ship's render pose, else the identity. */
    private static @Nullable Matrix4d farPose(final IPCreatePortalBelts.Exit exit) {
        if (exit.farFrame() == null) {
            return new Matrix4d();
        }
        return IPCreateShaftShipsClient.renderPose(exit.farFrame());
    }

    /** A world plane in a block entity's drawing space, through its pose. @return {normal, anchor} */
    private static Vector3f[] drawingPlane(final BlockPos pos, final Matrix4fc pose, final Vec3 point, final Vec3 normal) {
        final Vector3f anchor = pose.transformPosition(new Vector3f(
            (float) (point.x - pos.getX()), (float) (point.y - pos.getY()), (float) (point.z - pos.getZ())));
        final Vector3f drawingNormal = pose.transformDirection(normal.toVector3f()).normalize();
        return new Vector3f[]{drawingNormal, anchor};
    }
}
