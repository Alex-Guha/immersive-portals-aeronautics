/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import com.mojang.blaze3d.vertex.PoseStack;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import dev.simulated_team.simulated.content.blocks.lasers.AbstractLaserBlockEntity;
import dev.simulated_team.simulated.content.blocks.lasers.AbstractLaserRenderer;
import dev.simulated_team.simulated.content.blocks.lasers.LaserBehaviour;
import dev.simulated_team.simulated.content.blocks.lasers.laser_pointer.LaserPointerRenderer;
import dev.simulated_team.simulated.index.SimRenderTypes;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.blockentity.BlockEntityRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix3f;
import org.joml.Matrix4d;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaterniond;
import org.joml.Vector3d;
import org.joml.Vector3f;
import org.joml.Vector4f;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.mixin_create.IPLaserRendererInvoker;
import qouteall.imm_ptl.core.compat.sable_straddle.IPPortalIsometry;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPPlaneClippingBufferSource;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/**
 * Client side of {@link IPLaserPortals}. A beam through portals is drawn whole, as Simulated draws it (its length is the
 * path length, read off the unfolded hit), once per leg: in the beam's own world by its block entity renderer, cut at
 * the first portal ({@link #clipToFirstLeg}); in each later leg's world mapped through the portals before it and cut to
 * that leg ({@link #drawLaterLegs}, in every pass rendering that world, IP's portal views included). The cuts are on the
 * CPU ({@link IPPlaneClippingBufferSource}): Simulated's laser shader is its own.
 */
public final class IPLaserPortalsClient {
    /** Lasers whose beam went through a portal at their last cast. Client thread only. */
    private static final Set<LaserBehaviour> THROUGH = Collections.newSetFromMap(new WeakHashMap<>());
    private static boolean registered;
    /** Laser renderers drawing through a portal-pass clip (a laser pointer's {@code renderSafe} calls its super's). */
    private static int portalClipDepth;
    /** The block entity's pose at the start of the renderer's {@code renderSafe}: block local to drawing space. */
    @Nullable
    private static Matrix4f blockEntityPose;

    private IPLaserPortalsClient() {
    }

    /** @return the client world of {@code dimension} if IP has it loaded; never creates one */
    @Nullable
    static Level optionalWorld(final ResourceKey<Level> dimension) {
        return ClientWorldLoader.getOptionalWorld(dimension);
    }

    /** A laser cast on the client: draw its later legs while its beam goes through a portal. */
    public static void track(final LaserBehaviour laser, @Nullable final IPLaserPortals.Trace trace) {
        if (trace == null) {
            THROUGH.remove(laser);
            return;
        }
        if (!registered) {
            registered = true;
            NeoForge.EVENT_BUS.addListener(RenderLevelStageEvent.class, IPLaserPortalsClient::onStage);
        }
        THROUGH.add(laser);
    }

    /**
     * Draws a laser block entity (lens and beam) in a portal pass cut at IP's portal plane: they use Simulated's own
     * Veil shaders, which IP's front clipping plane does not reach, so a laser between the portal-side camera and the
     * far portal showed through the portal's surface. Outside portal passes, {@code render} gets {@code buffer}.
     */
    public static void renderClippedInPortalPass(final BlockEntity blockEntity, final PoseStack pose, final MultiBufferSource buffer,
                                                 final Consumer<MultiBufferSource> render) {
        final Plane clip = portalClipDepth == 0 && PortalRendering.isRendering() && blockEntity.getLevel() != null
                ? PortalRendering.getActiveClippingPlane() : null;
        if (clip == null) {
            render.accept(buffer);
            return;
        }
        final Vector3f[] drawing = drawingPlane(blockEntity, pose.last().pose(), clip.pos(), clip.normal());
        final IPPlaneClippingBufferSource clipped = new IPPlaneClippingBufferSource(buffer, drawing[0], drawing[1]);
        portalClipDepth++;
        try {
            render.accept(clipped);
        } finally {
            portalClipDepth--;
            clipped.flush();
        }
    }

    /** {@code renderSafe} of a laser renderer starts with the block entity's pose. */
    public static void beforeRender(final PoseStack pose) {
        blockEntityPose = new Matrix4f(pose.last().pose());
    }

    /**
     * The buffer for drawing a beam in its block entity's own pass: cut at the first portal the beam goes through, the
     * part before it kept. Without a trace (or a pose), {@code source} itself.
     */
    public static MultiBufferSource clipToFirstLeg(final AbstractLaserBlockEntity blockEntity, final LaserBehaviour laser,
                                                   final MultiBufferSource source) {
        final IPLaserPortals.Trace trace = ((IPLaserPortals.Holder) laser).ip$portalTrace();
        final Matrix4f pose = blockEntityPose;
        if (trace == null || pose == null || blockEntity.getLevel() == null) {
            return source;
        }
        final IPLaserPortals.Leg first = trace.legs().get(0);
        final Portal exit = first.exit();
        if (exit == null) {
            return source;
        }
        final Vec3 normal = keepToward(exit.getNormal(), first.from().subtract(first.to()));
        final Vector3f[] drawing = drawingPlane(blockEntity, pose, first.to(), normal);
        return new IPPlaneClippingBufferSource(source, drawing[0], drawing[1]);
    }

    /** Flushes a source from {@link #clipToFirstLeg} after drawing. */
    public static void flush(final MultiBufferSource source) {
        if (source instanceof final IPPlaneClippingBufferSource clipping) {
            clipping.flush();
        }
    }

    private static void onStage(final RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || THROUGH.isEmpty()) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final Level rendered = minecraft.level;
        if (rendered == null) {
            return;
        }
        final Vec3 camera = event.getCamera().getPosition();
        final float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        final MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        boolean drew = false;
        for (final LaserBehaviour laser : List.copyOf(THROUGH)) {
            final BlockEntity blockEntity = laser.blockEntity;
            if (blockEntity.isRemoved() || blockEntity.getLevel() == null) {
                THROUGH.remove(laser);
                continue;
            }
            final IPLaserPortals.Trace trace = ((IPLaserPortals.Holder) laser).ip$portalTrace();
            if (trace == null || !laser.shouldCast()) {
                continue;
            }
            for (int i = 1; i < trace.legs().size(); i++) {
                final IPLaserPortals.Leg leg = trace.legs().get(i);
                if (leg.level() == rendered) {
                    drew |= drawLeg((AbstractLaserBlockEntity) blockEntity, laser, trace, i, camera, partialTick, buffers);
                }
            }
        }
        if (drew) {
            buffers.endBatch(SimRenderTypes.laser());
        }
    }

    /** Draws leg {@code index} of a beam: the whole beam mapped into that leg's world, cut to the leg. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static boolean drawLeg(final AbstractLaserBlockEntity blockEntity, final LaserBehaviour laser, final IPLaserPortals.Trace trace,
                                   final int index, final Vec3 camera, final float partialTick, final MultiBufferSource buffers) {
        final BlockEntityRenderer<BlockEntity> renderer = Minecraft.getInstance().getBlockEntityRenderDispatcher().getRenderer(blockEntity);
        if (!(renderer instanceof final AbstractLaserRenderer laserRenderer)) {
            return false;
        }
        final Vector4f colors = laserRenderer.getColors(blockEntity, partialTick);
        if (colors.w <= 0.0f || (laserRenderer instanceof LaserPointerRenderer && colors.x == 0.0f && colors.y == 0.0f && colors.z == 0.0f)) {
            return false;
        }

        final IPLaserPortals.Leg leg = trace.legs().get(index);
        final IPLaserPortals.Leg previous = trace.legs().get(index - 1);
        final Matrix4d matrix = new Matrix4d().translation(-camera.x, -camera.y, -camera.z).mul(matrixOf(leg.fromSource()));
        final BlockPos pos = blockEntity.getBlockPos();
        if (Sable.HELPER.getContaining(blockEntity) instanceof final ClientSubLevel ship) {
            matrix.mul(ship.renderPose().bakeIntoMatrix(new Matrix4d()));
        }
        matrix.translate(pos.getX(), pos.getY(), pos.getZ());
        final PoseStack pose = new PoseStack();
        final Matrix4f drawing = new Matrix4f(matrix);
        pose.last().pose().set(drawing);
        pose.last().normal().set(new Matrix3f(drawing).normal());

        // drawing space is this world's minus the camera
        MultiBufferSource source = buffers;
        final List<IPPlaneClippingBufferSource> clips = new ArrayList<>();
        final Portal entry = previous.exit();
        if (entry != null) {
            final Vec3 entryPoint = entry.transformPoint(previous.to());
            final Vec3 entryNormal = keepToward(entry.transformLocalVecNonScale(entry.getNormal()), leg.to().subtract(entryPoint));
            source = clip(source, clips, entryPoint.subtract(camera), entryNormal);
        }
        if (leg.exit() != null) {
            source = clip(source, clips, leg.to().subtract(camera), keepToward(leg.exit().getNormal(), leg.from().subtract(leg.to())));
        }
        if (PortalRendering.isRendering()) {
            final Plane portalClip = PortalRendering.getActiveClippingPlane();
            if (portalClip != null) {
                source = clip(source, clips, portalClip.pos().subtract(camera), portalClip.normal());
            }
        }

        final IPLaserRendererInvoker invoker = (IPLaserRendererInvoker) laserRenderer;
        pose.pushPose();
        invoker.ip$transformPose(blockEntity, laser, pose);
        invoker.ip$createLaser(colors, pose, source, laser.getRange(), laserRenderer.getLaserLength(laser));
        pose.popPose();
        // the outermost clip feeds the next one in
        for (int i = clips.size() - 1; i >= 0; i--) {
            clips.get(i).flush();
        }
        return true;
    }

    private static MultiBufferSource clip(final MultiBufferSource source, final List<IPPlaneClippingBufferSource> clips,
                                          final Vec3 anchor, final Vec3 normal) {
        final IPPlaneClippingBufferSource clipping = new IPPlaneClippingBufferSource(source,
                new Vector3f((float) normal.x, (float) normal.y, (float) normal.z),
                new Vector3f((float) anchor.x, (float) anchor.y, (float) anchor.z));
        clips.add(clipping);
        return clipping;
    }

    /** @return {@code normal} or its opposite, whichever points to the side {@code toward} goes to */
    private static Vec3 keepToward(final Vec3 normal, final Vec3 toward) {
        return normal.dot(toward) >= 0.0 ? normal : normal.scale(-1.0);
    }

    private static Matrix4d matrixOf(final IPPortalIsometry isometry) {
        final Vec3 translation = isometry.translation();
        return new Matrix4d().translation(translation.x, translation.y, translation.z).rotate(new Quaterniond(isometry.rotation()));
    }

    /**
     * A world plane in a block entity's drawing space: the point and normal taken to block local coordinates (through
     * the ship's render pose when it is on one), then through the block entity's pose (as IPStraddleRender places its
     * cuts, so no camera position is needed, also in a straddle projection).
     *
     * @return {normal, anchor}
     */
    private static Vector3f[] drawingPlane(final BlockEntity blockEntity, final Matrix4fc pose, final Vec3 point, final Vec3 normal) {
        final BlockPos pos = blockEntity.getBlockPos();
        final Vector3d local = new Vector3d(point.x, point.y, point.z);
        final Vector3d localNormal = new Vector3d(normal.x, normal.y, normal.z);
        if (Sable.HELPER.getContaining(blockEntity) instanceof final ClientSubLevel ship) {
            ship.renderPose().transformPositionInverse(local);
            new Quaterniond(ship.renderPose().orientation()).conjugate().transform(localNormal);
        }
        local.sub(pos.getX(), pos.getY(), pos.getZ());
        final Vector3f anchor = pose.transformPosition(new Vector3f((float) local.x, (float) local.y, (float) local.z));
        final Vector3f drawingNormal = pose.transformDirection(new Vector3f((float) localNormal.x, (float) localNormal.y, (float) localNormal.z)).normalize();
        return new Vector3f[]{drawingNormal, anchor};
    }
}
