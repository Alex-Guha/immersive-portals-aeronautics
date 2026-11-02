/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.blaze3d.vertex.PoseStack;
import com.simibubi.create.content.trains.entity.CarriageContraptionEntity;
import dev.engine_room.flywheel.api.backend.BackendManager;
import java.util.ArrayList;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.compat.iris_integration.IPIrisCompat;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPPlaneClippingBufferSource;
import qouteall.imm_ptl.core.ducks.IEWorldRenderer;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * A crossing carriage when Flywheel's backend is off, always under an Iris shader pack: Create then draws the copy as a
 * plain entity, so {@link IPCreateCarriageCrossingClient}'s Flywheel plane and far-half draw do nothing, and each copy
 * stood whole in its own world, the part past the portal over no track. The same two things are done here for the
 * vanilla draw. The cut is made on the CPU ({@link IPPlaneClippingBufferSource}): IP's clip plane for entities crossing
 * a portal is off with Iris. The far half is the copy drawn a second time in the world being drawn, with the camera
 * mapped back through the portal and the rotation on the pose, the way {@link IPFlywheelFarHalf} draws its engine.
 */
public final class IPCreateCarriageVanillaDraw {
    /** The cut of the copy being drawn. Render thread. */
    private static @Nullable IPPlaneClippingBufferSource cut;
    private static boolean drawingFarHalf;

    private IPCreateCarriageVanillaDraw() {
    }

    private static boolean applies() {
        return !IPCreateCarriageCrossing.STOCK && !BackendManager.isBackendOn();
    }

    /** Whether a far half is being drawn: the copy's parts past the plane are what is wanted. */
    public static boolean isDrawingFarHalf() {
        return drawingFarHalf;
    }

    /** {@code CarriageContraptionEntityRenderer.render}, head: whether this copy draws nothing in this pass. */
    public static boolean isSkipped(final CarriageContraptionEntity entity) {
        if (!applies() || drawingFarHalf) {
            return false;
        }
        if (IPCreateCarriageCrossingClient.isHidden(entity)) {
            return true;
        }
        final IPCreateCarriageCrossing.Crossing crossing = IPCreateCarriageCrossingClient.crossingOf(entity);
        return crossing != null && IPCreateCarriageCrossingClient.facesPortalClipAway(
            IPCreateTrainShipsClient.renderPoint(entity, crossing.keepPoint()),
            IPCreateTrainShipsClient.renderDirection(entity, crossing.keepNormal()));
    }

    /** {@code CarriageContraptionEntityRenderer.render}, head: its buffers, cut at the plane of the copy's crossing. */
    public static MultiBufferSource cutNear(final CarriageContraptionEntity entity, final MultiBufferSource buffers) {
        cut = null;
        final IPCreateCarriageCrossing.Crossing crossing = IPCreateCarriageCrossingClient.crossingOf(entity);
        if (!applies() || drawingFarHalf || crossing == null) {
            return buffers;
        }
        cut = planeCut(buffers, IPCreateTrainShipsClient.renderPoint(entity, crossing.keepPoint()),
            IPCreateTrainShipsClient.renderDirection(entity, crossing.keepNormal()), CHelper.getCurrentCameraPos());
        return cut;
    }

    /** {@code CarriageContraptionEntityRenderer.render}, return: emits what the cut still holds. */
    public static void afterRender() {
        if (cut != null) {
            cut.flush();
            cut = null;
        }
    }

    /** Drawing space is the world's minus the camera; in a portal pass the cut stands just past IP's own clip. */
    private static IPPlaneClippingBufferSource planeCut(final MultiBufferSource buffers, final Vec3 point, final Vec3 keepNormal, final Vec3 camera) {
        final double seam = PortalRendering.isRendering() ? IPCreateCarriageCrossingClient.SEAM : 0.0;
        final Vec3 anchor = point.subtract(camera).subtract(keepNormal.scale(seam));
        return new IPPlaneClippingBufferSource(buffers, new Vector3f((float) keepNormal.x, (float) keepNormal.y, (float) keepNormal.z),
            new Vector3f((float) anchor.x, (float) anchor.y, (float) anchor.z));
    }

    /**
     * Draws, in the world being drawn, the parts of carriages that reach into it through a portal with no placed copy
     * of their own there. After the entities, so the pack draws them as entities and IP's clip covers them in portal views.
     */
    public static void onRenderStage(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_ENTITIES || !applies()) {
            return;
        }
        final Map<CarriageContraptionEntity, IPCreateCarriageCrossing.Crossing> farHalves = IPCreateCarriageCrossingClient.farHalves();
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel level = minecraft.level;
        if (farHalves.isEmpty() || level == null) {
            return;
        }
        final Vec3 camera = event.getCamera().getPosition();
        final float partialTicks = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        final MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        boolean drew = false;
        for (final Map.Entry<CarriageContraptionEntity, IPCreateCarriageCrossing.Crossing> entry : new ArrayList<>(farHalves.entrySet())) {
            final CarriageContraptionEntity entity = entry.getKey();
            final Portal portal = entry.getValue().portal();
            if (entity != null && !entity.isRemoved() && !portal.isRemoved() && portal.getDestDim().equals(level.dimension())) {
                drew |= drawFarHalf(minecraft, entity, entry.getValue(), portal, camera, partialTicks, buffers);
            }
        }
        if (drew && !IPIrisCompat.isShaderPackInUse()) {
            buffers.endBatch();
        }
    }

    private static boolean drawFarHalf(final Minecraft minecraft, final CarriageContraptionEntity entity,
                                       final IPCreateCarriageCrossing.Crossing crossing, final Portal portal, final Vec3 camera,
                                       final float partialTicks, final MultiBufferSource buffers) {
        final Vec3 point = portal.transformPoint(IPCreateTrainShipsClient.renderPoint(entity, crossing.keepPoint()));
        final Vec3 keepNormal = portal.transformLocalVecNonScale(
            IPCreateTrainShipsClient.renderDirection(entity, crossing.keepNormal())).scale(-1.0);
        if (IPCreateCarriageCrossingClient.facesPortalClipAway(point, keepNormal)) {
            return false;
        }
        final IPPlaneClippingBufferSource farCut = planeCut(buffers, point, keepNormal, camera);
        final Vec3 mappedCamera = portal.inverseTransformPoint(camera);
        final PoseStack pose = new PoseStack();
        pose.mulPose(portal.getRotationD().toMcQuaternion());
        drawingFarHalf = true;
        try {
            ((IEWorldRenderer) minecraft.levelRenderer).ip_myRenderEntity(
                entity, mappedCamera.x, mappedCamera.y, mappedCamera.z, partialTicks, pose, farCut);
        } finally {
            drawingFarHalf = false;
            farCut.flush();
        }
        return true;
    }
}
