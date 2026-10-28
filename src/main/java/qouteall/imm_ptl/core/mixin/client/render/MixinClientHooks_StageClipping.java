/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Camera;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.neoforged.neoforge.client.ClientHooks;
import net.neoforged.neoforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

/**
 * Marks NeoForge's render stage dispatch, so that shaders other mods set from a stage get IP's clip plane in portal
 * passes ({@code MixinRenderSystem_Clipping}). Every stage goes through this overload, the section-layer ones (vanilla's
 * and Sodium's {@code renderSectionLayer}) included.
 */
@Mixin(value = ClientHooks.class, remap = false)
public class MixinClientHooks_StageClipping {
    @WrapMethod(
        method = "dispatchRenderStage(Lnet/neoforged/neoforge/client/event/RenderLevelStageEvent$Stage;Lnet/minecraft/client/renderer/LevelRenderer;Lcom/mojang/blaze3d/vertex/PoseStack;Lorg/joml/Matrix4f;Lorg/joml/Matrix4f;ILnet/minecraft/client/Camera;Lnet/minecraft/client/renderer/culling/Frustum;)V"
    )
    private static void ip_markStage(
        RenderLevelStageEvent.Stage stage, LevelRenderer levelRenderer, PoseStack poseStack, Matrix4f modelView,
        Matrix4f projection, int renderTick, Camera camera, Frustum frustum, Operation<Void> original
    ) {
        RenderStates.renderStageDepth++;
        try {
            original.call(stage, levelRenderer, poseStack, modelView, projection, renderTick, camera, frustum);
        }
        finally {
            RenderStates.renderStageDepth--;
        }
    }
}
