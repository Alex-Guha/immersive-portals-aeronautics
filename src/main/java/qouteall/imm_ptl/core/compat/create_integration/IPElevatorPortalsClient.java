/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
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

    /** Pulleys on this client whose shaft runs through portals, for their belts past each plane ({@link #onRenderStage}). */
    private static final java.util.Set<ElevatorPulleyBlockEntity> CHAINED =
        java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());

    /** The segments whose belt a far copy drew in the level render under way, as (pulley, portal index). */
    private static final java.util.Set<Drawn> DRAWN = new java.util.HashSet<>();

    private record Drawn(ElevatorPulleyBlockEntity pulley, int index) {
    }

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
        DRAWN.clear(); // portal passes come after this level's entities and block entities, so none falls in between
        final java.util.Map<net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level>,
            java.util.Map<IPElevatorPortals.CopyKey, com.simibubi.create.content.contraptions.ControlledContraptionEntity>> copies =
            IPElevatorPortals.clientCopies();
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
                final BlockPos pulleyPos = entry.getKey().pulley();
                if (copy.isRemoved() || !isLive(copy) || info == null || !homeLevel.isLoaded(pulleyPos)
                    || !(homeLevel.getBlockEntity(pulleyPos) instanceof ElevatorPulleyBlockEntity pulley)
                    || pulley.movedContraption == null || ((IPElevatorPulley) pulley).ip$chain().isEmpty()) {
                    continue;
                }
                IPElevatorPortals.placeFrom(pulley.movedContraption, copy, info.shaft());
            }
        }
    }

    /**
     * The part of the pulley's belt between the far copy's planes (its portal's, and the next portal's below if any),
     * and the magnet on top of the car while it is between them, drawn with the far copy (the pulley's own visual draws
     * the part on its side; {@link #beltOffset}, {@link #magnetPastPlane}). Create's vanilla pulley renderer's parts,
     * placed from the pulley's position mapped into the copy's frame.
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
            || ((IPElevatorPulley) pulley).ip$chain().isEmpty()) {
            return;
        }
        if (!qouteall.imm_ptl.core.compat.iris_integration.IPIrisCompat.isRenderingShadowPass()) {
            DRAWN.add(new Drawn(pulley, info.index()));
        }
        drawPulleyParts(copy.level(), pulley, info.shaft(), info.hasBottom() ? info.farBottomPlaneY() : Double.NEGATIVE_INFINITY,
            camX, camY, camZ, partialTick, poseStack, buffers);
    }

    /**
     * Client: remembers a pulley whose shaft runs through portals, so that its belt is drawn in every level it passes
     * through ({@link #onRenderStage}).
     */
    public static void track(final ElevatorPulleyBlockEntity pulley) {
        if (!((IPElevatorPulley) pulley).ip$chain().isEmpty()) {
            CHAINED.add(pulley);
        }
    }

    /**
     * The belt in each level the rope passes through that no far copy drew in this level render: a copy is there only
     * while the car is near its segment, so once the car is past the next portal the belt between the two was drawn
     * nowhere; and a copy out of view is not drawn, nor its belt with it. After the block entities, in the level being
     * drawn (main and portal passes), with a shader pack as Iris's block entities; flushed here without Iris. No shadows
     * with a pack (Iris's shadow pass runs no render stage): the copies' own belts keep theirs.
     */
    public static void onRenderStage(final net.neoforged.neoforge.client.event.RenderLevelStageEvent event) {
        if (event.getStage() != net.neoforged.neoforge.client.event.RenderLevelStageEvent.Stage.AFTER_BLOCK_ENTITIES || CHAINED.isEmpty()) {
            return;
        }
        final Minecraft minecraft = Minecraft.getInstance();
        final net.minecraft.client.multiplayer.ClientLevel level = minecraft.level;
        if (level == null) {
            return;
        }
        final Vec3 camera = event.getCamera().getPosition();
        final float partialTick = event.getPartialTick().getGameTimeDeltaPartialTick(false);
        final net.minecraft.client.renderer.MultiBufferSource.BufferSource buffers = minecraft.renderBuffers().bufferSource();
        boolean drew = false;
        for (final ElevatorPulleyBlockEntity pulley : java.util.List.copyOf(CHAINED)) {
            final java.util.List<IPElevatorShaft> chain = ((IPElevatorPulley) pulley).ip$chain();
            if (pulley.isRemoved() || chain.isEmpty() || !(pulley.getLevel() instanceof final net.minecraft.client.multiplayer.ClientLevel home)
                || !qouteall.imm_ptl.core.ClientWorldLoader.getClientWorlds().contains(home)) {
                CHAINED.remove(pulley);
                continue;
            }
            for (int k = 0; k < chain.size(); k++) {
                final IPElevatorShaft shaft = chain.get(k);
                if (shaft.farDim() != level.dimension() || DRAWN.contains(new Drawn(pulley, k))) {
                    continue;
                }
                // the next portal's plane in this segment's frame, as a far copy's FarInfo holds it
                final double farBottom = k + 1 < chain.size() ? chain.get(k + 1).planeY() + shaft.shift().getY() : Double.NEGATIVE_INFINITY;
                final net.minecraft.client.renderer.MultiBufferSource source =
                    qouteall.imm_ptl.core.compat.iris_integration.IPIrisCompat.beginBlockEntity(buffers, pulley.getBlockState());
                try {
                    drew |= drawPulleyParts(level, pulley, shaft, farBottom, camera.x, camera.y, camera.z, partialTick,
                        new com.mojang.blaze3d.vertex.PoseStack(), source);
                } finally {
                    qouteall.imm_ptl.core.compat.iris_integration.IPIrisCompat.endBlockEntity();
                }
            }
        }
        if (drew && !qouteall.imm_ptl.core.compat.iris_integration.IPIrisCompat.isShaderPackInUse()) {
            buffers.endBatch(net.minecraft.client.renderer.RenderType.solid());
        }
    }

    /**
     * The pulley's belt between {@code shaft}'s far plane and {@code farBottom} (the next portal's plane in the same
     * frame, or -inf), and the magnet while the rope ends between them, in {@code farLevel}. False if nothing is there.
     */
    private static boolean drawPulleyParts(final net.minecraft.world.level.Level farLevel, final ElevatorPulleyBlockEntity pulley,
                                           final IPElevatorShaft shaft, final double farBottom,
                                           final double camX, final double camY, final double camZ,
                                           final float partialTick, final com.mojang.blaze3d.vertex.PoseStack poseStack,
                                           final net.minecraft.client.renderer.MultiBufferSource buffers) {
        final float offset = com.simibubi.create.content.contraptions.pulley.PulleyRenderer.getBlockEntityOffset(partialTick, pulley);
        if (offset <= 0 || !com.simibubi.create.content.contraptions.pulley.PulleyRenderer.isPulleyRunning(pulley)) {
            return false;
        }
        final BlockPos farPulley = shaft.blockToFar(pulley.getBlockPos());
        final double farPlane = shaft.farPlaneY();
        if (farPulley.getY() - offset >= farPlane) {
            return false; // the rope ends above this segment
        }
        final net.minecraft.world.level.block.state.BlockState state = pulley.getBlockState();
        // the pulley's facing, turned as the portal turns
        final float angle = 180.0F + net.createmod.catnip.math.AngleHelper.horizontalAngle(shaft.directionToFar(
            state.getValue(com.simibubi.create.content.contraptions.elevator.ElevatorPulleyBlock.HORIZONTAL_FACING)));
        final com.mojang.blaze3d.vertex.VertexConsumer vb = buffers.getBuffer(net.minecraft.client.renderer.RenderType.solid());
        IPElevatorFramesClient.pushAt(farLevel, farPulley, camX, camY, camZ, poseStack);
        if (farPulley.getY() - offset < farPlane && farPulley.getY() - offset >= farBottom) {
            final net.createmod.catnip.render.SuperByteBuffer magnet =
                net.createmod.catnip.render.CachedBuffers.partial(com.simibubi.create.AllPartialModels.ELEVATOR_MAGNET, state);
            com.simibubi.create.content.contraptions.pulley.AbstractPulleyRenderer.renderAt(farLevel,
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
                break; // from here up the belt is on the side above this copy's portal
            }
            // the piece's part between the planes (below the next plane, the next copy draws it)
            final double from = Math.max(bottom, farBottom);
            final double to = Math.min(bottom + 1, farPlane);
            if (to <= from) {
                continue;
            }
            belt.center().rotateYDegrees(angle).uncenter();
            if (from > bottom || to < bottom + 1) {
                // a piece across a plane: only its part between the planes, squeezed to that height. Moved down
                // first, then squeezed (renderAt would move it after the squeeze, and the squeeze would shorten the move)
                final BlockPos lightPos = farPulley.below((int) at);
                belt.translate(0, -at + (from - bottom), 0);
                belt.scale(1, (float) (to - from), 1);
                belt.shiftUVScrolling(beltShift, (float) beltScroll * spriteSize)
                    .light(net.minecraft.client.renderer.LevelRenderer.getLightColor(farLevel, farLevel.getBlockState(lightPos), lightPos))
                    .renderInto(poseStack, vb);
                continue;
            }
            com.simibubi.create.content.contraptions.pulley.AbstractPulleyRenderer.renderAt(farLevel,
                belt.shiftUVScrolling(beltShift, (float) beltScroll * spriteSize), at, farPulley, poseStack, vb);
        }
        poseStack.popPose();
        return true;
    }

    /** How far down from the pulley its belt is drawn: no further than the shaft's portal plane. */
    public static float beltOffset(final ElevatorPulleyBlockEntity pulley, final float offset) {
        final IPElevatorShaft shaft = ((IPElevatorPulley) pulley).ip$shaft();
        return shaft == null ? offset : Math.min(offset, (float) Math.max(0.0, pulley.getBlockPos().getY() - shaft.planeY()));
    }

    /**
     * One part Create's vanilla pulley renderer places with {@code AbstractPulleyRenderer.renderAt} ({@code offset} blocks
     * below the pulley): false to let Create draw it, true if done here. The magnet past the plane and belt pieces wholly
     * past it are not drawn; a belt piece across the plane is drawn squeezed to its part above it, as the Flywheel visual
     * ends the belt there. Create turns the cached buffer before the call and the draw resets it: a part not drawn is
     * reset here, or the next draw of that buffer (the next piece, or the far side's) took the turn twice.
     */
    public static boolean vanillaPartAt(final ElevatorPulleyBlockEntity pulley, final net.minecraft.world.level.LevelAccessor world,
                                        final net.createmod.catnip.render.SuperByteBuffer partial, final float offset,
                                        final BlockPos pulleyPos, final com.mojang.blaze3d.vertex.PoseStack ms,
                                        final com.mojang.blaze3d.vertex.VertexConsumer buffer) {
        final IPElevatorShaft shaft = ((IPElevatorPulley) pulley).ip$shaft();
        final double bottom = pulleyPos.getY() - offset;
        if (shaft == null || bottom >= shaft.planeY()) {
            return false;
        }
        final double plane = shaft.planeY();
        final double top = bottom + 1;
        final boolean magnet = partial == net.createmod.catnip.render.CachedBuffers.partial(
            com.simibubi.create.AllPartialModels.ELEVATOR_MAGNET, pulley.getBlockState());
        if (magnet || top <= plane) {
            partial.reset();
            return true;
        }
        // moved down first, then squeezed (as in drawPulleyParts)
        final BlockPos lightPos = pulleyPos.below((int) offset);
        partial.translate(0, plane - pulleyPos.getY(), 0);
        partial.scale(1, (float) (top - plane), 1);
        partial.light(net.minecraft.client.renderer.LevelRenderer.getLightColor(world, world.getBlockState(lightPos), lightPos))
            .renderInto(ms, buffer);
        return true;
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

        /** A pulley's shaft, its chain of portals ({@code IPElevatorPortals.sendShaft}); an empty tag: none. Ignored until the pulley is loaded here. */
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
            final java.util.List<IPElevatorShaft> chain = tag.isEmpty() ? java.util.List.of() : IPElevatorShaft.readChain(tag);
            final IPElevatorPulley holder = (IPElevatorPulley) be;
            if (!chain.equals(holder.ip$chain())) {
                holder.ip$setChain(chain);
            }
            track(be);
        }
    }
}
