/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL11;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.IPCGlobal;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.ducks.IEShader;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.q_misc_util.my_util.Plane;

public class FrontClipping {
    private static final Minecraft client = Minecraft.getInstance();
    private static double[] activeClipPlaneEquationBeforeModelView;
    private static double[] activeClipPlaneAfterModelView;
    // Immersive Aeronautics: the clip plane for world terrain drawn by Sodium (see setupInnerClipping)
    private static double[] activeTerrainClipPlaneAfterModelView;
    
    public static boolean isClippingEnabled = false;
    
    public static final double ADJUSTMENT = 0.01;
    
    public static void disableClipping() {
        if (IPGlobal.enableClippingMechanism) {
            if (isClippingEnabled) {
                GL11.glDisable(GL11.GL_CLIP_PLANE0);
                isClippingEnabled = false;
            }
        }
    }
    
    private static void enableClipping() {
        if (IPGlobal.enableClippingMechanism) {
            if (!isClippingEnabled) {
                GL11.glEnable(GL11.GL_CLIP_PLANE0);
                isClippingEnabled = true;
            }
        }
    }
    
    public static void updateInnerClipping(PoseStack matrixStack) {
        Matrix4f modelView = matrixStack.last().pose();
        updateInnerClipping(modelView);
    }
    
    public static void updateInnerClipping(Matrix4f modelView) {
        if (PortalRendering.isRendering()) {
            setupInnerClipping(
                PortalRendering.getActiveClippingPlane(),
                modelView, 0
            );
        }
        else {
            disableClipping();
        }
    }
    
    // NOTE the actual clipping plane is related to current model view matrix
    public static void setupInnerClipping(
        Plane clipping, Matrix4f modelView, double adjustment
    ) {
        if (!IPCGlobal.useFrontClipping) {
            return;
        }
        
        // Note: the normal of plane points to the non-clipped side
        
        if (clipping != null) {
            activeClipPlaneEquationBeforeModelView =
                getClipEquationInner(clipping.pos(), clipping.normal(), adjustment);
            activeClipPlaneAfterModelView =
                transformClipEquation(activeClipPlaneEquationBeforeModelView, modelView);
            // Immersive Aeronautics: the adjustment keeps terrain faces lying exactly in the portal plane (world
            // wrapping portals on block faces) and the far halves of straddling ships (cut 1 cm past the plane in
            // portal passes). For world terrain behind a portal whose plane lies mid-block (nether portals) no block
            // face can lie in it, while the band showed the last centimetre of anything ending exactly in the plane
            // on the camera's side (Create's portal tracks). Ships are drawn with the vanilla shaders and keep it.
            activeTerrainClipPlaneAfterModelView = adjustment != 0 && liesMidBlock(clipping)
                ? transformClipEquation(getClipEquationInner(clipping.pos(), clipping.normal(), 0), modelView)
                : activeClipPlaneAfterModelView;
            
            enableClipping();
        }
        else {
            activeClipPlaneEquationBeforeModelView = null;
            disableClipping();
        }
    }
    
    // Immersive Aeronautics: an axis-aligned plane through the middle of a block layer
    private static boolean liesMidBlock(Plane plane) {
        Vec3 normal = plane.normal();
        Vec3 pos = plane.pos();
        double coordinate;
        if (Math.abs(normal.x) > 0.999999) {
            coordinate = pos.x;
        }
        else if (Math.abs(normal.y) > 0.999999) {
            coordinate = pos.y;
        }
        else if (Math.abs(normal.z) > 0.999999) {
            coordinate = pos.z;
        }
        else {
            return false;
        }
        return Math.abs(coordinate - Math.floor(coordinate) - 0.5) < 1.0e-6;
    }
    
    private static double[] transformClipEquation(
        double[] equation, Matrix4f modelView
    ) {
        Vector4f eq =
            new Vector4f((float) equation[0], (float) equation[1], (float) equation[2], (float) equation[3]);
        Matrix4f m = new Matrix4f(modelView);
        m.invert();
        m.transpose();
        m.transform(eq);
        return new double[]{eq.x(), eq.y(), eq.z(), eq.w()};
    }
    
    private static double[] getClipEquationInner(
        Vec3 clippingPoint, Vec3 clippingDirection, double correction
    ) {
        Vec3 cameraPos = CHelper.getCurrentCameraPos();
        
        Vec3 planeNormal = clippingDirection;
        
        Vec3 portalPos = clippingPoint
            .add(planeNormal.scale(correction))
            .subtract(cameraPos);
        
        //equation: planeNormal * p + c > 0
        //-planeNormal * portalCenter = c
        double c = planeNormal.scale(-1).dot(portalPos);
        
        return new double[]{
            planeNormal.x, planeNormal.y, planeNormal.z, c
        };
    }
    
    public static void setupOuterClipping(PoseStack matrixStack, Portal portal) {
        if (!IPCGlobal.useFrontClipping) {
            return;
        }
        
        double[] clipEquationOuter = getClipEquationOuter(portal);
        
        if (clipEquationOuter != null) {
            activeClipPlaneEquationBeforeModelView = clipEquationOuter;
            activeClipPlaneAfterModelView = transformClipEquation(
                activeClipPlaneEquationBeforeModelView, matrixStack.last().pose()
            );
            activeTerrainClipPlaneAfterModelView = activeClipPlaneAfterModelView; // Immersive Aeronautics
            enableClipping();
        }
        else {
            activeClipPlaneEquationBeforeModelView = null;
            disableClipping();
        }
    }
    
    // "double @Nullable []" is weird...
    private static double @Nullable [] getClipEquationOuter(Portal portal) {
        @Nullable Plane outerClipping = portal.getPortalShape()
            .getOuterClipping(portal.getThisSideState());
        
        if (outerClipping == null) {
            return null;
        }
        
        Vec3 planeNormal = outerClipping.normal();
        
        Vec3 cameraPos = client.gameRenderer.getMainCamera().getPosition();
        
        Vec3 portalPos = outerClipping.pos()
            .subtract(cameraPos);
        
        //equation: planeNormal * p + c > 0
        //-planeNormal * portalCenter = c
        double c = planeNormal.scale(-1).dot(portalPos);
        
        return new double[]{
            planeNormal.x, planeNormal.y, planeNormal.z, c
        };
    }
    
    public static double[] getActiveClipPlaneEquationBeforeModelView() {
        return activeClipPlaneEquationBeforeModelView;
    }
    
    public static double[] getActiveClipPlaneEquationAfterModelView() {
        return activeClipPlaneAfterModelView;
    }
    
    // Immersive Aeronautics: what Sodium's terrain shader clips against (without the adjustment at mid-block planes)
    public static double[] getActiveTerrainClipPlaneEquationAfterModelView() {
        return activeTerrainClipPlaneAfterModelView;
    }
    
    public static void updateClippingEquationUniformForCurrentShader(
        boolean isRenderingEntities
    ) {
        if (!IPGlobal.enableClippingMechanism) {
            return;
        }
        
        ShaderInstance shader = RenderSystem.getShader();
        
        if (shader == null) {
            return;
        }
        
        Uniform clippingEquationUniform = ((IEShader) shader).ip_getClippingEquationUniform();
        if (clippingEquationUniform != null) {
            if (isClippingEnabled) {
                double[] equation = activeClipPlaneEquationBeforeModelView;
//                double[] equation = isRenderingEntities ? activeClipPlaneAfterModelView : activeClipPlaneEquationBeforeModelView;
                clippingEquationUniform.set(
                    (float) equation[0], (float) equation[1],
                    (float) equation[2], (float) equation[3]
                );
            }
            else {
                clippingEquationUniform.set(0f, 0f, 0f, 1f);
            }
        }
    }
    
    // Immersive Aeronautics: for a shader set while a NeoForge render stage is dispatched in a portal pass (other
    // mods' own draws: Pipes 'n Physics' pipe fluid). Such a draw sends world-minus-camera positions through
    // RenderSystem's model view, which the stages inside the section layers have rotated and the earlier ones have
    // not, so the eye-space plane is carried back through that matrix. Clipped only where IP's clip is already on
    // (section layers, entities and block entities): elsewhere GL's clip distance stays off, and turning it on
    // would cut the draws of shaders without the plane at random.
    public static void updateClippingEquationUniformForStageDraw() {
        if (!IPGlobal.enableClippingMechanism) {
            return;
        }
        
        ShaderInstance shader = RenderSystem.getShader();
        
        if (shader == null) {
            return;
        }
        
        Uniform clippingEquationUniform = ((IEShader) shader).ip_getClippingEquationUniform();
        if (clippingEquationUniform == null) {
            return;
        }
        if (!isClippingEnabled || activeTerrainClipPlaneAfterModelView == null) {
            clippingEquationUniform.set(0f, 0f, 0f, 1f);
            return;
        }
        double[] eye = activeTerrainClipPlaneAfterModelView;
        Vector4f eq = new Vector4f((float) eye[0], (float) eye[1], (float) eye[2], (float) eye[3]);
        new Matrix4f(RenderSystem.getModelViewMatrix()).transpose().transform(eq);
        clippingEquationUniform.set(eq.x(), eq.y(), eq.z(), eq.w());
    }
    
    public static void unsetClippingUniform() {
        if (!IPGlobal.enableClippingMechanism) {
            return;
        }
        
        ShaderInstance shader = RenderSystem.getShader();
        
        if (shader == null) {
            return;
        }
        
        Uniform clippingEquationUniform = ((IEShader) shader).ip_getClippingEquationUniform();
        if (clippingEquationUniform != null) {
            clippingEquationUniform.set(0f, 0f, 0f, 1f);
        }
    }
}
