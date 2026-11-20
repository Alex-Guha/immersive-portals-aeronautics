/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.iris_integration;

import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.portal.shape.RectangularPortalShape;
import qouteall.imm_ptl.core.render.FrustumCuller;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * The shadow casters of a portal view, culled to what its portal shows.
 * <p>
 * Iris culls a pack's shadow casters (terrain sections, entities, DH LODs) by the camera's view frustum swept towards
 * the light ({@code AdvancedShadowCullingFrustum}): whatever can cast a shadow onto something the camera sees. In a
 * portal view that frustum is the portal camera's whole screen, though the view shows only through its portal, so the
 * view's shadow map is drawn with every caster around the far portal. Here the frustum Iris culls by is cut to the far
 * portal's rectangle on screen: the receivers the view can show are all inside it, so every caster that can shade them
 * is kept and the pixels shown do not change. The shadow map's own projection is untouched.
 * <p>
 * Only the advanced culling takes this frustum: a pack that turns shadow culling off, or one Iris culls by distance
 * (voxelization), is left as it is.
 */
public final class IPIrisShadowCull {
    /** Debug switch {@code portal_shadow_cull}. */
    public static boolean enabled = true;

    /** Kept around the rectangle in normalized device coordinates (1% of the screen per side). */
    private static final float MARGIN = 0.02f;
    /** A corner this close to the camera plane (or behind it): the portal is close and large, no cut. */
    private static final float MIN_W = 0.05f;

    private IPIrisShadowCull() {
    }

    /**
     * {@code viewProjection} (camera-relative, from Iris's {@code gbufferProjection * gbufferModelView}) cut to the
     * portal of the view being drawn; unchanged outside portal views. May modify and return the argument.
     */
    public static Matrix4f cropToPortal(Matrix4f viewProjection) {
        if (!enabled || !PortalRendering.isRendering()) {
            return viewProjection;
        }
        Portal portal = PortalRendering.getRenderingPortal();
        if (portal.isFuseView()) {
            // drawn without its own area: the view may cover more than the portal
            return viewProjection;
        }
        Vec3 camera = CHelper.getCurrentCameraPos();
        Vector4f clip = new Vector4f();
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (Vec3 near : nearCorners(portal)) {
            Vec3 far = portal.transformPoint(near);
            clip.set((float) (far.x - camera.x), (float) (far.y - camera.y), (float) (far.z - camera.z), 1);
            viewProjection.transform(clip);
            if (clip.w < MIN_W) {
                return viewProjection;
            }
            float x = clip.x / clip.w;
            float y = clip.y / clip.w;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
        minX = Math.max(-1, minX - MARGIN);
        minY = Math.max(-1, minY - MARGIN);
        maxX = Math.min(1, maxX + MARGIN);
        maxY = Math.min(1, maxY + MARGIN);
        if (minX <= -1 && minY <= -1 && maxX >= 1 && maxY >= 1) {
            return viewProjection;
        }
        // an edge-on or off-screen portal shows (almost) nothing; keep the cut finite
        float halfX = Math.max((maxX - minX) / 2, 1e-3f);
        float halfY = Math.max((maxY - minY) / 2, 1e-3f);
        float centreX = (minX + maxX) / 2;
        float centreY = (minY + maxY) / 2;
        // in clip space: x' = (x - centreX * w) / halfX, the same for y; z and w as they were
        Matrix4f crop = new Matrix4f()
            .translation(-centreX / halfX, -centreY / halfY, 0)
            .scale(1 / halfX, 1 / halfY, 1);
        return viewProjection.mulLocal(crop);
    }

    /** The portal's outline on this side: its four corners, or the corners of its thin box for other shapes. */
    private static Vec3[] nearCorners(Portal portal) {
        if (portal.getPortalShape() instanceof RectangularPortalShape) {
            return FrustumCuller.getRectPortalFourVerticesCounterClockwise(portal.getThisSideState());
        }
        AABB box = portal.getThinBoundingBox();
        Vec3[] corners = new Vec3[8];
        for (int i = 0; i < 8; i++) {
            corners[i] = new Vec3(
                (i & 1) == 0 ? box.minX : box.maxX,
                (i & 2) == 0 ? box.minY : box.maxY,
                (i & 4) == 0 ? box.minZ : box.maxZ
            );
        }
        return corners;
    }
}
