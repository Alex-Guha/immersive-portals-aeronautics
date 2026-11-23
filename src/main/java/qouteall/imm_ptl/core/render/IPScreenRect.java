/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.render;

import com.mojang.blaze3d.pipeline.RenderTarget;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector4f;
import qouteall.imm_ptl.core.portal.Portal;

import java.util.function.UnaryOperator;

/** Screen rectangles of boxes, for scissoring draws that only matter over a portal's view. */
public final class IPScreenRect {
    private IPScreenRect() {
    }

    /**
     * @param map         takes a corner of {@code box} into the world being drawn
     * @param camera      the camera of the pass, in the world being drawn
     * @return the rectangle on the main framebuffer in pixels (x, y, width, height; empty when off screen), or null when
     * a corner is behind the camera (then the whole screen)
     */
    public static int @Nullable [] of(AABB box, UnaryOperator<Vec3> map, Matrix4fc projection, Matrix4fc modelView,
                                      Vec3 camera) {
        Matrix4f viewProjection = new Matrix4f(projection).mul(modelView);
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < 8; i++) {
            Vec3 corner = new Vec3(
                (i & 1) == 0 ? box.minX : box.maxX,
                (i & 2) == 0 ? box.minY : box.maxY,
                (i & 4) == 0 ? box.minZ : box.maxZ
            );
            Vec3 relative = map.apply(corner).subtract(camera);
            Vector4f clip = viewProjection.transform(new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1));
            if (clip.w <= 1.0e-4f) {
                return null;
            }
            minX = Math.min(minX, clip.x / clip.w);
            minY = Math.min(minY, clip.y / clip.w);
            maxX = Math.max(maxX, clip.x / clip.w);
            maxY = Math.max(maxY, clip.y / clip.w);
        }
        RenderTarget main = Minecraft.getInstance().getMainRenderTarget();
        int x0 = Math.max(0, (int) Math.floor((Math.max(-1, minX) * 0.5 + 0.5) * main.width) - 2);
        int y0 = Math.max(0, (int) Math.floor((Math.max(-1, minY) * 0.5 + 0.5) * main.height) - 2);
        int x1 = Math.min(main.width, (int) Math.ceil((Math.min(1, maxX) * 0.5 + 0.5) * main.width) + 2);
        int y1 = Math.min(main.height, (int) Math.ceil((Math.min(1, maxY) * 0.5 + 0.5) * main.height) + 2);
        return new int[]{x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0)};
    }

    /** {@link #of} for {@code portal}'s box, seen through it in a pass of its view. */
    public static int @Nullable [] ofPortalView(Portal portal, Matrix4fc projection, Matrix4fc modelView, Vec3 camera) {
        return of(portal.getBoundingBox(), portal::transformPoint, projection, modelView, camera);
    }

    /** @return whether a rectangle of {@link #of} shows nothing */
    public static boolean isEmpty(int @Nullable [] rect) {
        return rect != null && (rect[2] <= 0 || rect[3] <= 0);
    }

    /** @return the overlap of two rectangles of {@link #of} (null: the whole screen) */
    public static int @Nullable [] intersect(int @Nullable [] a, int @Nullable [] b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        int x0 = Math.max(a[0], b[0]);
        int y0 = Math.max(a[1], b[1]);
        int x1 = Math.min(a[0] + a[2], b[0] + b[2]);
        int y1 = Math.min(a[1] + a[3], b[1] + b[3]);
        return new int[]{x0, y0, Math.max(0, x1 - x0), Math.max(0, y1 - y0)};
    }
}
