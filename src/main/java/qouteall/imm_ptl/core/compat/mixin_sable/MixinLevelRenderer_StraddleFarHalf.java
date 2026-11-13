/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.SheetedDecalTextureGenerator;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.client.resources.model.ModelBakery;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.BlockDestructionProgress;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;

import java.util.SortedSet;

/**
 * More of a straddling ship's far half, drawn by the destination's level renderer (docs/lessons/straddle-rendering.md
 * leftovers), each inside {@link IPStraddleRender#forEachProjection} so Sable poses the ship (and what is on it) where
 * the far half is:
 * <ul>
 *     <li>entities placed on the ship (Contraption Diagrams, item frames) past the portal plane, through this renderer's
 *     {@code renderEntity} (Sable places plot entities by the ship's render pose; {@code MixinLevelRenderer_StraddleEntityCut}
 *     keeps those past the plane);</li>
 *     <li>block-breaking cracks on far-half blocks, which the ship's own dimension's renderer records;</li>
 * </ul>
 * and in the ship's own dimension no crack is drawn on a block past the plane (it is not drawn there).
 */
@Mixin(LevelRenderer.class)
public abstract class MixinLevelRenderer_StraddleFarHalf {
    @Shadow
    @Nullable
    private ClientLevel level;

    @Shadow
    @Final
    private RenderBuffers renderBuffers;

    @Shadow
    protected abstract void renderEntity(Entity entity, double camX, double camY, double camZ, float partialTick,
                                         PoseStack poseStack, MultiBufferSource bufferSource);

    @Unique
    private final Quaternionf ip_orientation = new Quaternionf();

    @Inject(
        method = "renderLevel",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/renderer/LevelRenderer;globalBlockEntities:Ljava/util/Set;", shift = At.Shift.BEFORE, ordinal = 0),
        require = 0
    )
    private void ip_farHalfEntities(final DeltaTracker deltaTracker, final boolean renderBlockOutline, final Camera camera,
                                    final GameRenderer gameRenderer, final LightTexture lightTexture, final Matrix4f frustumMatrix,
                                    final Matrix4f projectionMatrix, final CallbackInfo ci) {
        if (this.level == null || this.level != Minecraft.getInstance().level) {
            return;
        }
        final Vec3 cameraPos = camera.getPosition();
        final MultiBufferSource.BufferSource buffers = this.renderBuffers.bufferSource();
        IPStraddleRender.forEachProjection(ship -> {
            final var box = ship.getPlot().getBoundingBox();
            final AABB plot = new AABB(box.minX(), box.minY(), box.minZ(), box.maxX() + 1, box.maxY() + 1, box.maxZ() + 1).inflate(1.0);
            // Sable's entity lookup also returns world entities overlapping the ship (a player standing on it): only
            // entities in the plot itself are on the ship
            for (final Entity entity : ship.getLevel().getEntities((Entity) null, plot,
                e -> !e.isRemoved() && e != camera.getEntity() && dev.ryanhcode.sable.Sable.HELPER.getContaining(e) == ship)) {
                final float partialTick = deltaTracker.getGameTimeDeltaPartialTick(true);
                this.renderEntity(entity, cameraPos.x, cameraPos.y, cameraPos.z, partialTick, new PoseStack(), buffers);
            }
        });
    }

    @Inject(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/debug/DebugRenderer;render(Lcom/mojang/blaze3d/vertex/PoseStack;Lnet/minecraft/client/renderer/MultiBufferSource$BufferSource;DDD)V"),
        require = 0
    )
    private void ip_farHalfCracks(final DeltaTracker deltaTracker, final boolean renderBlockOutline, final Camera camera,
                                  final GameRenderer gameRenderer, final LightTexture lightTexture, final Matrix4f frustumMatrix,
                                  final Matrix4f projectionMatrix, final CallbackInfo ci) {
        if (this.level == null || this.level != Minecraft.getInstance().level) {
            return;
        }
        final Vec3 cameraPos = camera.getPosition();
        final BlockRenderDispatcher blockRenderer = Minecraft.getInstance().getBlockRenderer();
        IPStraddleRender.forEachProjection(ship -> {
            final ClientLevel shipLevel = (ClientLevel) ship.getLevel();
            final LevelRenderer shipRenderer = ClientWorldLoader.getWorldRenderer(shipLevel.dimension());
            if (shipRenderer == null) {
                return;
            }
            final Long2ObjectMap<SortedSet<BlockDestructionProgress>> progress =
                ((IPLevelRendererDestructionAccessor) shipRenderer).ip_getDestructionProgress();
            if (progress.isEmpty()) {
                return;
            }
            final Pose3dc pose = ship.renderPose();
            for (final Long2ObjectMap.Entry<SortedSet<BlockDestructionProgress>> entry : progress.long2ObjectEntrySet()) {
                final SortedSet<BlockDestructionProgress> stages = entry.getValue();
                if (stages == null || stages.isEmpty()) {
                    continue;
                }
                final BlockPos pos = BlockPos.of(entry.getLongKey());
                if (!(qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleCut.shipAt(shipLevel, pos) instanceof final ClientSubLevel owner)
                    || owner != ship || !IPStraddleRender.keepsBlockEntity(ship, pos)) {
                    continue;
                }
                // as vanilla's crack loop, posed like Sable's ship cracks (corner by the pose, then its orientation)
                final Vec3 corner = pose.transformPosition(new Vec3(pos.getX(), pos.getY(), pos.getZ()));
                final PoseStack poseStack = new PoseStack();
                poseStack.translate(corner.x - cameraPos.x, corner.y - cameraPos.y, corner.z - cameraPos.z);
                poseStack.mulPose(this.ip_orientation.set(pose.orientation()));
                final float scale = (float) qouteall.imm_ptl.core.compat.sable_straddle.IPShipScale.of(pose);
                poseStack.scale(scale, scale, scale);
                final VertexConsumer consumer = new SheetedDecalTextureGenerator(
                    this.renderBuffers.crumblingBufferSource().getBuffer(ModelBakery.DESTROY_TYPES.get(stages.last().getProgress())),
                    poseStack.last(), 1.0F);
                blockRenderer.renderBreakingTexture(shipLevel.getBlockState(pos), pos, shipLevel, poseStack, consumer,
                    shipLevel.getModelData(pos));
            }
        });
    }

    /** No crack on a block of a straddling ship that is past its portal plane (not drawn in this pass). */
    @WrapOperation(
        method = "renderLevel",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/client/renderer/block/BlockRenderDispatcher;renderBreakingTexture(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/BlockAndTintGetter;Lcom/mojang/blaze3d/vertex/PoseStack;Lcom/mojang/blaze3d/vertex/VertexConsumer;Lnet/neoforged/neoforge/client/model/data/ModelData;)V"),
        require = 0
    )
    private void ip_noCrackOnCutPart(final BlockRenderDispatcher dispatcher, final BlockState state, final BlockPos pos,
                                     final BlockAndTintGetter getter, final PoseStack poseStack, final VertexConsumer consumer,
                                     final ModelData modelData, final Operation<Void> original) {
        if (this.level != null) {
            final ClientSubLevel ship = IPStraddleRender.cutShipAt(this.level, pos);
            if (ship != null && !IPStraddleRender.keepsBlockEntity(ship, pos)) {
                return;
            }
        }
        original.call(dispatcher, state, pos, getter, poseStack, consumer, modelData);
    }
}
