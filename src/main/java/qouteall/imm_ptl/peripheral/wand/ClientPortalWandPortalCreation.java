/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.peripheral.wand;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.ship_portals.IPShipPortalWandClient;
import qouteall.imm_ptl.core.mc_utils.WireRenderingHelper;
import qouteall.imm_ptl.core.platform_specific.IPConfig;
import qouteall.imm_ptl.core.portal.animation.TimingFunction;
import qouteall.imm_ptl.core.render.context_management.RenderStates;
import qouteall.q_misc_util.CustomTextOverlay;
import qouteall.q_misc_util.Helper;
import qouteall.q_misc_util.api.McRemoteProcedureCall;
import qouteall.q_misc_util.api.McRemoteProcedureCallClient;
import qouteall.q_misc_util.my_util.Circle;
import qouteall.q_misc_util.my_util.Plane;
import qouteall.q_misc_util.my_util.WithDim;
import qouteall.q_misc_util.my_util.animation.Animated;
import qouteall.q_misc_util.my_util.animation.RenderedPlane;

import java.util.function.Consumer;

/**
 * The process and relevant marking rendering is handled purely on client side.
 * When it finishes, it performs a remote procedure call to create the portal.
 */
//@Environment(EnvType.CLIENT)
public class ClientPortalWandPortalCreation {
    
    private static final Logger LOGGER = LogUtils.getLogger();
    
    // Immersive Aeronautics: corners on ships stay in the ship's plot (IPShipPortalWandClient)
    private static final boolean SABLE = ModList.get().isLoaded("sable");
    
    public static final Animated<Vec3> cursor = new Animated<>(
        Animated.VEC3_NULLABLE_TYPE_INFO,
        () -> RenderStates.renderStartNanoTime,
        TimingFunction.sine::mapProgress,
        null
    );
    
    public static final Animated<RenderedPlane> renderedPlane = new Animated<>(
        Animated.RENDERED_PLANE_TYPE_INFO,
        () -> RenderStates.renderStartNanoTime,
        TimingFunction.sine::mapProgress,
        RenderedPlane.NONE
    );
    
    // the proto-portal determined by placed anchors
    @NotNull
    public static ProtoPortal protoPortal = new ProtoPortal();
    
    public static void reset() {
        protoPortal = new ProtoPortal();
        renderedPlane.clearTarget();
    }
    
    public static void onLeftClick() {
        undo();
    }
    
    private static void undo() {
        protoPortal.undo();
    }
    
    public static void onRightClick() {
        Vec3 cursorTarget = cursor.getTarget();
        
        if (cursorTarget == null) {
            return;
        }
        
        ClientLevel world = Minecraft.getInstance().level;
        
        if (world == null) {
            return;
        }
        
        protoPortal.tryPlaceCursor(
            world.dimension(),
            cursorTarget
        );
        
        if (protoPortal.isComplete()) {
            finish();
        }
    }
    
    public static void clearCursorPointing() {
        cursor.clearTarget();
    }
    
    private static boolean isSameFrame(Vec3 a, Vec3 b) {
        return !SABLE || IPShipPortalWandClient.sameFrame(a, b);
    }
    
    /**
     * Immersive Aeronautics: runs {@code draw} with the camera position for points in {@code framePos}'s frame:
     * the world, or a ship's plot drawn through the ship's render pose. Draws nothing for a ship this client lacks.
     */
    private static void drawInFrame(PoseStack matrixStack, Vec3 cameraPos, Vec3 framePos, Consumer<Vec3> draw) {
        if (!SABLE || !IPShipPortalWandClient.inPlot(framePos)) {
            draw.accept(cameraPos);
            return;
        }
        Vec3 cameraInPlot = IPShipPortalWandClient.pushShipFrame(matrixStack, framePos, cameraPos);
        if (cameraInPlot != null) {
            draw.accept(cameraInPlot);
            matrixStack.popPose();
        }
    }
    
    public static void updateDisplay() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        
        ResourceKey<Level> cursorLimitingDim = protoPortal.getCursorConstraintDim();
        
        if (cursorLimitingDim != null && player.level().dimension() != cursorLimitingDim) {
            cursor.clearTarget();
            renderedPlane.clearTarget();
            return;
        }
        
        WithDim<Plane> limitingPlane = protoPortal.getCursorConstraintPlane();
        WithDim<Circle> limitingCircle = protoPortal.getCursorConstraintCircle();
        
        Vec3 eyePos = player.getEyePosition(RenderStates.getPartialTick());
        Vec3 viewVec = player.getViewVector(RenderStates.getPartialTick());
        
        // update cursor
        Vec3 cursorPointing = null;
        int alignment = IPConfig.getConfig().portalWandCursorAlignment;
        if (limitingPlane != null) {
            if (SABLE) {
                // traced in the plane's frame: the world, or the plot of the ship this side is on
                Vec3[] ray = IPShipPortalWandClient.viewRay(player, limitingPlane.value().pos());
                cursorPointing = ray == null ? null : limitingPlane.value().rayTrace(ray[0], ray[1]);
                if (ray == null) {
                    CustomTextOverlay.putText(Component.literal(
                        "The ship this side is on is not loaded here; come closer, or left-click to undo"
                    ));
                }
            }
            else {
                cursorPointing = limitingPlane.value().rayTrace(eyePos, viewVec);
            }
            
            if (cursorPointing != null) {
                // align it and then project onto plane
                // aligning may cause it to be out of the plane
                cursorPointing = WandUtil.alignOnBlocks(player.level(), cursorPointing, alignment);
                cursorPointing = limitingPlane.value().getProjection(cursorPointing);
                
                if (limitingCircle != null) {
                    // align it into the circle
                    cursorPointing = limitingCircle.value().projectToCircle(cursorPointing);
                }
            }
        }
        else {
            // with Sable a hit on a ship's block is in the ship's plot
            HitResult hitResult = SABLE ? IPShipPortalWandClient.pick(player, 64) :
                player.pick(64, RenderStates.getPartialTick(), false);
            
            if (hitResult.getType() == HitResult.Type.BLOCK && (hitResult instanceof BlockHitResult blockHitResult)) {
                // if pointing at a block, use the aligned position on block
                cursorPointing = WandUtil.alignOnBlocks(player.level(), blockHitResult.getLocation(), alignment);
            }
        }
        
        if (limitingPlane != null && limitingCircle == null) {
            RenderedPlane shownPlane = renderedPlane.getTarget();
            if (shownPlane != null && shownPlane.plane() != null
                && !isSameFrame(shownPlane.plane().value().pos(), limitingPlane.value().pos())) {
                // no sweep between a ship's plot and the world
                renderedPlane.clearTarget();
            }
            renderedPlane.setTarget(
                new RenderedPlane(limitingPlane, 1.0),
                Helper.secondToNano(3.0)
            );
        }
        else {
            renderedPlane.setTarget(
                RenderedPlane.NONE, Helper.secondToNano(0.5)
            );
        }
        
        // remove cursor if the placement is invalid
        
        if (cursorPointing != null) {
            ProtoPortal pendingState = protoPortal.copy();
            boolean canPlace = pendingState.tryPlaceCursor(player.level().dimension(), cursorPointing);
            String shipProblem = canPlace && SABLE ? IPShipPortalWandClient.whyNot(pendingState) : null;
            if (!canPlace || !pendingState.isValidPlacement() || shipProblem != null) {
                cursorPointing = null;
                pendingState = null;
            }
            
            MutableComponent promptMessage = shipProblem != null ? Component.literal(shipProblem) :
                protoPortal.getPromptMessage(pendingState);
            if (promptMessage != null) {
                CustomTextOverlay.putText(
                    promptMessage
                );
            }
        }
        
        if (cursorPointing != null) {
            Vec3 shownCursor = cursor.getTarget();
            if (shownCursor != null && !isSameFrame(shownCursor, cursorPointing)) {
                // no sweep between a ship's plot and the world
                cursor.clearTarget();
            }
            cursor.setTarget(cursorPointing, Helper.secondToNano(0.1));
        }
        else {
            cursor.clearTarget();
        }
    }
    
    /**
     * {@link PortalWandInteraction.RemoteCallables#finishPortalCreation(ServerPlayer, ProtoPortal)}
     */
    public static void finish() {
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) {
            return;
        }
        
        McRemoteProcedureCallClient.tellServerToInvoke(
            "qouteall.imm_ptl.peripheral.wand.PortalWandInteraction.RemoteCallables.finishPortalCreation",
            protoPortal.copy()
        );
        
        reset();
    }
    
    // ARGB
    private static final int colorOfFirstSideLeftBottom = 0xfffb00ff;
    private static final int colorOfFirstSideRightBottom = 0xffe63262;
    private static final int colorOfFirstSideLeftUp = 0xfffcef60;
    
    private static final int colorOfSecondSideLeftBottom = 0xffaeff57;
    private static final int colorOfSecondSideRightBottom = 0xff57ffd2;
    private static final int colorOfSecondSideLeftUp = 0xffbdb3ff;
    
    private static final int colorOfPlane = 0xffafd3fa;
    private static final int colorOfCircle = 0xff03fce3;
    private static final int colorOfFirstPortalArea = 0xfffc9003;
    private static final int colorOfFirstPortalArea2 = 0xffFF7D98;
    private static final int colorOfSecondPortalArea = 0xff60f2fc;
    private static final int colorOfSecondPortalArea2 = 0xff60f2fc;
    
    public static void render(
        PoseStack matrixStack,
        MultiBufferSource.BufferSource bufferSource,
        double camX, double camY, double camZ
    ) {
        LocalPlayer player = Minecraft.getInstance().player;
        
        if (player == null) {
            return;
        }
        
        ResourceKey<Level> currDim = player.level().dimension();
        
        VertexConsumer vertexConsumer = bufferSource.getBuffer(RenderType.lines());
        Vec3 cameraPos = new Vec3(camX, camY, camZ);
        
        WithDim<Circle> circle = protoPortal.getCursorConstraintCircle();
        
        Vec3 renderedCursor = ClientPortalWandPortalCreation.cursor.getCurrent();
        
        if (circle != null && renderedCursor != null) {
            // the cursor interpolates along line
            // it may not always be on the circle
            renderedCursor = circle.value().projectToCircle(renderedCursor);
        }
        
        ProtoPortal renderedProtoPortal = protoPortal;
        
        if (renderedCursor != null) {
            ProtoPortal pending = protoPortal.copy();
            boolean canPlace = pending.tryPlaceCursor(currDim, renderedCursor);
            
            if (canPlace) {
                renderedProtoPortal = pending;
            }
        }
        
        if (circle == null) {
            circle = protoPortal.getCursorConstraintCircle();
        }
        
        // render the proto-portal
        if (renderedProtoPortal.firstSide != null && currDim == renderedProtoPortal.firstSide.dimension) {
            ProtoPortalSide side = renderedProtoPortal.firstSide;
            drawInFrame(matrixStack, cameraPos, side.leftBottom, camera -> renderSide(
                vertexConsumer, camera, side, matrixStack,
                colorOfFirstSideLeftBottom, colorOfFirstSideRightBottom, colorOfFirstSideLeftUp, colorOfFirstPortalArea
            ));
        }
        
        if (renderedProtoPortal.secondSide != null && currDim == renderedProtoPortal.secondSide.dimension) {
            ProtoPortalSide side = renderedProtoPortal.secondSide;
            drawInFrame(matrixStack, cameraPos, side.leftBottom, camera -> renderSide(
                vertexConsumer, camera, side, matrixStack,
                colorOfSecondSideLeftBottom, colorOfSecondSideRightBottom, colorOfSecondSideLeftUp, colorOfSecondPortalArea
            ));
        }
        
        VertexConsumer debugLineStripConsumer = bufferSource.getBuffer(RenderType.debugLineStrip(1));
        
        // render the circle
        WithDim<Circle> renderedCircle = circle != null ?
            circle : renderedProtoPortal.getCursorConstraintCircle();
        if (renderedCircle != null && renderedCircle.dimension() == currDim) {
            Circle drawnCircle = renderedCircle.value();
            drawInFrame(matrixStack, cameraPos, drawnCircle.plane().pos(), camera -> WireRenderingHelper.renderCircle(
                debugLineStripConsumer, camera,
                drawnCircle,
                colorOfCircle,
                matrixStack
            ));
        }
        
        // render the plane (don't render the plane if renders circle)
        RenderedPlane currRenderedPlane = renderedPlane.getCurrent();
        if (currRenderedPlane != null &&
            currRenderedPlane.plane() != null &&
            currRenderedPlane.plane().dimension() == currDim
        ) {
            double scale = currRenderedPlane.scale();
            if (scale > 0.01) {
                Plane drawnPlane = currRenderedPlane.plane().value();
                drawInFrame(matrixStack, cameraPos, drawnPlane.pos(), camera -> WireRenderingHelper.renderPlane(
                    debugLineStripConsumer,
                    camera, drawnPlane,
                    scale,
                    colorOfPlane,
                    matrixStack,
                    true
                ));
            }
        }
    }
    
    private static void renderSide(
        VertexConsumer vertexConsumer, Vec3 cameraPos, ProtoPortalSide side, PoseStack matrixStack,
        int colorOfLeftBottom, int colorOfRightBottom, int colorOfLeftUp, int colorOfArea
    ) {
        WireRenderingHelper.renderSmallCubeFrame(
            vertexConsumer, cameraPos, side.leftBottom,
            colorOfLeftBottom, 1.0, matrixStack
        );
        
        if (side.rightBottom != null) {
            WireRenderingHelper.renderSmallCubeFrame(
                vertexConsumer, cameraPos, side.rightBottom,
                colorOfRightBottom, 1.0, matrixStack
            );
        }
        if (side.leftTop != null) {
            WireRenderingHelper.renderSmallCubeFrame(
                vertexConsumer, cameraPos, side.leftTop,
                colorOfLeftUp, 1.0, matrixStack
            );
            
            WandUtil.renderPortalAreaGrid(
                vertexConsumer,
                cameraPos,
                side,
                colorOfArea,
                matrixStack
            );
        }
    }
    
}
