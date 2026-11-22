/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlockEntity;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Vec3i;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Client: an elevator car through a portal and its far copy are each drawn only on their side of their plane
 * ({@link IPElevatorPortals#keep}), the way carriages crossing a portal are cut: past the plane the car is inside
 * whatever lies behind the portal in its own level, and drawn there it showed as a second car under the portal. Their
 * Flywheel parts get a plane ({@link IPFlywheelStraddleClip}); parts Create draws with its vanilla renderer (the
 * controls' display) are drawn by the copy on whose side their block's centre is; the pulley's belt ends at the plane
 * and its magnet, on top of the car, is hidden once past it, and the far side draws both from there on.
 */
public final class IPElevatorPortalsClient {
    private static final float[] REJECT = {0f, 0f, 0f, -1f};

    private IPElevatorPortalsClient() {
    }

    /** Whether a client-side far copy is in a world this client still has (not one of a world it left). */
    public static boolean isLive(final Entity copy) {
        return qouteall.imm_ptl.core.ClientWorldLoader.getIsInitialized()
            && qouteall.imm_ptl.core.ClientWorldLoader.getClientWorlds().contains(copy.level());
    }

    /** The Flywheel cut of a car's or far copy's embedding, relative to the engine's render origin, or null. */
    static float @Nullable [] flywheelPlane(@Nullable final Entity entity, final Vec3i renderOrigin) {
        final IPElevatorPortals.Keep keep = entity == null ? null : IPElevatorPortals.keep(entity);
        if (keep == null) {
            return null;
        }
        // the plane is in the entity's frame: through the ship's render pose for one on a ship
        final Vec3 n = IPElevatorFramesClient.normalToWorld(entity, new Vec3(0, keep.above() ? 1 : -1, 0));
        final Vec3 point = IPElevatorFramesClient.pointToWorld(entity, new Vec3(entity.getX(), keep.planeY(), entity.getZ()));
        if (IPCreateCarriageCrossingClient.facesPortalClipAway(point, n)) {
            return REJECT;
        }
        final Vec3 rel = point.subtract(renderOrigin.getX(), renderOrigin.getY(), renderOrigin.getZ());
        final double w = -n.dot(rel) + (PortalRendering.isRendering() ? IPCreateCarriageCrossingClient.SEAM : 0.0);
        return new float[]{(float) n.x, (float) n.y, (float) n.z, (float) w};
    }

    /**
     * A far copy in a dimension this client only sees through portals gets its Flywheel visual now. Flywheel makes the
     * visuals of new entities when it updates their dimension, and a dimension seen only through a portal is updated only
     * while the portal is drawn: when the car fills the portal's opening, its floor hides the portal until the floor is
     * past the plane, and the copy's visual was made in the frame the portal came into view, too late to be drawn in it
     * (the floor blinked out for a frame). Runs Flywheel's tick update for that dimension, switched to it, while the copy
     * has no visual.
     */
    public static void ensureFarVisual(final Entity copy) {
        final Minecraft minecraft = Minecraft.getInstance();
        if (copy.level() == minecraft.level || IPFlywheelStraddleClip.hasContraptionEmbedding(copy)
            || !(copy.level() instanceof final net.minecraft.client.multiplayer.ClientLevel level)) {
            return;
        }
        // Flywheel visualizes only Minecraft's current level: switched to the copy's, as IP does to draw it through a portal
        qouteall.imm_ptl.core.ClientWorldLoader.withSwitchedWorld(level, () -> {
            final dev.engine_room.flywheel.api.visualization.VisualizationManager manager =
                dev.engine_room.flywheel.api.visualization.VisualizationManager.get(level);
            if (manager != null) {
                manager.entities().queueAdd(copy);
                dev.engine_room.flywheel.impl.visualization.VisualizationEventHandler.onClientTick(minecraft, level);
            }
        });
    }

    /** Whether a part Create draws with its vanilla renderer is drawn by this car or far copy; true if it is neither. */
    public static boolean drawsVanillaPart(final AbstractContraptionEntity entity, final BlockPos localPos) {
        final IPElevatorPortals.Keep keep = IPElevatorPortals.keep(entity);
        if (keep == null) {
            return true;
        }
        final float partialTick = Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(true);
        return keep.keeps(entity.toGlobalVector(Vec3.atCenterOf(localPos), partialTick).y);
    }

    /**
     * Before every level render (each portal pass too): every far copy is put where its car is, mapped, with the car's
     * previous position as its own. Between its car's tick and the frames after it, a far copy's previous position was
     * found reset to its current one, so whatever read it at the start of a frame drew it at the tick's end, and later in
     * the frame at the interpolated place: it stepped ahead and back once a tick, seen through the portal.
     */
    public static void beforeLevelRender() {
        final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
            java.util.Map<BlockPos, com.simibubi.create.content.contraptions.ControlledContraptionEntity>> copies = IPElevatorPortals.clientCopies();
        if (copies.isEmpty()) {
            return;
        }
        for (final var byDim : copies.entrySet()) {
            final net.minecraft.client.multiplayer.ClientLevel homeLevel = qouteall.imm_ptl.core.ClientWorldLoader.getOptionalWorld(byDim.getKey());
            if (homeLevel == null) {
                continue;
            }
            for (final var entry : byDim.getValue().entrySet()) {
                final var copy = entry.getValue();
                final IPElevatorFarCopy.FarInfo info = IPElevatorPortals.farInfo(copy);
                if (copy.isRemoved() || !isLive(copy) || info == null || !homeLevel.isLoaded(entry.getKey())
                    || !(homeLevel.getBlockEntity(entry.getKey()) instanceof ElevatorPulleyBlockEntity pulley)
                    || pulley.movedContraption == null || ((IPElevatorPulley) pulley).ip$shaft() == null) {
                    continue;
                }
                IPElevatorPortals.placeFrom(pulley.movedContraption, copy, ((IPElevatorPulley) pulley).ip$shaft());
            }
        }
    }

    /**
     * The part of the pulley's belt past the plane, and the magnet on top of the car once it is past, drawn on the far
     * side with the far copy (the pulley's own visual draws the rest on its side; {@link #beltOffset},
     * {@link #magnetPastPlane}). Create's vanilla pulley renderer's parts, placed from the pulley's position mapped
     * through the shaft's portal.
     */
    public static void drawFarPulleyParts(final AbstractContraptionEntity copy, final double camX, final double camY, final double camZ,
                                          final float partialTick, final com.mojang.blaze3d.vertex.PoseStack poseStack,
                                          final net.minecraft.client.renderer.MultiBufferSource buffers) {
        final IPElevatorFarCopy.FarInfo info = IPElevatorPortals.farInfo(copy);
        if (info == null) {
            return;
        }
        final net.minecraft.client.multiplayer.ClientLevel homeLevel = qouteall.imm_ptl.core.ClientWorldLoader.getOptionalWorld(info.homeDim());
        if (homeLevel == null || !homeLevel.isLoaded(info.pulleyPos())
            || !(homeLevel.getBlockEntity(info.pulleyPos()) instanceof ElevatorPulleyBlockEntity pulley)
            || ((IPElevatorPulley) pulley).ip$shaft() == null) {
            return;
        }
        final IPElevatorShaft shaft = ((IPElevatorPulley) pulley).ip$shaft();
        final float offset = com.simibubi.create.content.contraptions.pulley.PulleyRenderer.getBlockEntityOffset(partialTick, pulley);
        if (offset <= 0 || !com.simibubi.create.content.contraptions.pulley.PulleyRenderer.isPulleyRunning(pulley)) {
            return;
        }
        final BlockPos farPulley = shaft.blockToFar(pulley.getBlockPos());
        final double farPlane = shaft.farPlaneY();
        final net.minecraft.world.level.block.state.BlockState state = pulley.getBlockState();
        // the pulley's facing, turned as the portal turns
        final float angle = 180.0F + net.createmod.catnip.math.AngleHelper.horizontalAngle(shaft.directionToFar(
            state.getValue(com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlock.HORIZONTAL_FACING)));
        final com.mojang.blaze3d.vertex.VertexConsumer vb = buffers.getBuffer(net.minecraft.client.renderer.RenderType.solid());
        IPElevatorFramesClient.pushAt(copy.level(), farPulley, camX, camY, camZ, poseStack);
        if (farPulley.getY() - offset < farPlane) {
            final net.createmod.catnip.render.SuperByteBuffer magnet =
                net.createmod.catnip.render.CachedBuffers.partial(com.simibubi.create.AllPartialModels.ELEVATOR_MAGNET, state);
            com.simibubi.create.content.contraptions.pulley.AbstractPulleyRenderer.renderAt(copy.level(),
                magnet.center().rotateYDegrees(angle).uncenter(), offset, farPulley, poseStack, vb);
        }
        final net.createmod.catnip.render.SpriteShiftEntry beltShift = com.simibubi.create.AllSpriteShifts.ELEVATOR_BELT;
        final float spriteSize = beltShift.getTarget().getV1() - beltShift.getTarget().getV0();
        final double beltScroll = (-(offset + 0.5) - Math.floor(-(offset + 0.5))) / 2.0;
        final net.createmod.catnip.render.SuperByteBuffer belt =
            net.createmod.catnip.render.CachedBuffers.partial(com.simibubi.create.AllPartialModels.ELEVATOR_BELT, state);
        for (int i = 0; i < offset - 0.25F; i++) {
            final float at = offset - i;
            final double bottom = farPulley.getY() - at;
            if (bottom >= farPlane) {
                break; // from here up the belt is on the pulley's side
            }
            belt.center().rotateYDegrees(angle).uncenter();
            final double height = farPlane - bottom;
            if (height < 1) {
                // the piece across the plane: only its part below the plane, squeezed to that height. Moved down
                // first, then squeezed (renderAt would move it after the squeeze, and the squeeze would shorten the move)
                final BlockPos lightPos = farPulley.below((int) at);
                belt.translate(0, -at, 0);
                belt.scale(1, (float) height, 1);
                belt.shiftUVScrolling(beltShift, (float) beltScroll * spriteSize)
                    .light(net.minecraft.client.renderer.LevelRenderer.getLightColor(copy.level(), copy.level().getBlockState(lightPos), lightPos))
                    .renderInto(poseStack, vb);
                continue;
            }
            com.simibubi.create.content.contraptions.pulley.AbstractPulleyRenderer.renderAt(copy.level(),
                belt.shiftUVScrolling(beltShift, (float) beltScroll * spriteSize), at, farPulley, poseStack, vb);
        }
        poseStack.popPose();
    }

    /** How far down from the pulley its belt is drawn: no further than the shaft's portal plane. */
    public static float beltOffset(final ElevatorPulleyBlockEntity pulley, final float offset) {
        final IPElevatorShaft shaft = ((IPElevatorPulley) pulley).ip$shaft();
        return shaft == null ? offset : Math.min(offset, (float) Math.max(0.0, pulley.getBlockPos().getY() - shaft.planeY()));
    }

    /** Whether the magnet on top of the car is past the shaft's plane (drawn on the far side, not here). */
    public static boolean magnetPastPlane(final ElevatorPulleyBlockEntity pulley, final float offset) {
        final IPElevatorShaft shaft = ((IPElevatorPulley) pulley).ip$shaft();
        return shaft != null && pulley.getBlockPos().getY() - offset < shaft.planeY();
    }

    /** Messages from the server ({@code McRemoteProcedureCall}). */
    public static final class RemoteCallables {
        private RemoteCallables() {
        }

        /** A pulley's shaft ({@code IPElevatorPortals.sendShaft}); an empty tag: none. Ignored until the pulley is loaded here. */
        public static void setShaft(final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension,
                                    final BlockPos pulley, final net.minecraft.nbt.CompoundTag tag) {
            if (!qouteall.imm_ptl.core.ClientWorldLoader.getIsInitialized()) {
                return;
            }
            // only a world this client has: a message never makes one
            final net.minecraft.client.multiplayer.ClientLevel level = qouteall.imm_ptl.core.ClientWorldLoader.getClientWorlds().stream()
                .filter(world -> world.dimension() == dimension).findFirst().orElse(null);
            if (level == null || !level.isLoaded(pulley) || !(level.getBlockEntity(pulley) instanceof ElevatorPulleyBlockEntity be)) {
                return;
            }
            final IPElevatorShaft shaft = tag.isEmpty() ? null : IPElevatorShaft.read(tag);
            final IPElevatorPulley holder = (IPElevatorPulley) be;
            if (!java.util.Objects.equals(shaft, holder.ip$shaft())) {
                holder.ip$setShaft(shaft);
            }
        }
    }
}
