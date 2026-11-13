/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.render;

import com.mojang.logging.LogUtils;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.mixin.client.render.IPLevelRendererFrustumAccessor;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * Vanilla's frustum capture (F3+U, Shift+F3+U releases it) carried through portals, as a debug view of what is
 * culled. Vanilla freezes only the player's world's chunk and entity culling at the captured camera, and IP chose
 * portals with the live camera, so every portal in the live view was drawn with live culling inside.
 * <p>
 * While a capture is held, the frozen camera is the culling camera everywhere. A view is reached from the captured
 * camera along a path of portals: the portals the player has crossed since the capture ({@link #onPlayerCrossed}),
 * then the portals of the pass being drawn ({@code PortalRendering}'s layers), with a portal followed by its reverse
 * cancelled out. The culling camera of that view is the captured position and view matrix carried along the path,
 * exactly as the live camera is carried into a portal pass ({@code transformPoint}, the portal's additional camera
 * transformation), and the last portal of the path limits culling to its rim. So after the player walks through a
 * portal, their own world is culled as seen through that portal from the captured spot, and a portal leading back
 * shows the captured world culled as seen directly. The views are still drawn from the live camera.
 * <p>
 * The culling camera goes into vanilla's own capture fields of the level renderer drawing each view: the player's
 * world's at every frame start ({@link #onFrameStart}) and each portal pass's ({@link #beginPass}, put back after the
 * pass), so vanilla's culling, Sodium's (its viewport comes from that frustum), Veil's cull frustum and vanilla's
 * drawing of the frozen frustum follow it. IP's own checks ask {@link #isCulling}, {@link #cullingPos} and
 * {@link #rimPortal}: the choice of portals ({@code PortalRenderer}), the section search origin (vanilla
 * {@code VisibleSectionDiscovery}, Sodium's occlusion culler) and the rim culling ({@code FrustumCuller}). Not covered:
 * Flywheel's instance culling, Sable's ship culling and the Iris shadow pass (all live in vanilla's capture too), DH
 * and Voxy LODs. One path is followed: a place the captured camera sees both directly and through a portal is culled
 * by the path the player took.
 * <p>
 * Without a capture nothing here changes anything. The capture applies only while the player is in the world its path
 * ends in (a dimension change without a crossing seen here, such as a command, suspends it); a pass whose level
 * renderer holds a capture that does not apply runs with it hidden.
 */
public final class IPFrustumCapture {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final Vector4f[] NDC_CORNERS = {
        new Vector4f(-1, -1, -1, 1), new Vector4f(1, -1, -1, 1), new Vector4f(1, 1, -1, 1), new Vector4f(-1, 1, -1, 1),
        new Vector4f(-1, -1, 1, 1), new Vector4f(1, -1, 1, 1), new Vector4f(1, 1, 1, 1), new Vector4f(-1, 1, 1, 1)
    };

    /** The capture: the world, position and matrices of the captured camera, and the portals crossed since. */
    private static final class Capture {
        final ResourceKey<Level> dimension;
        final Vec3 pos;
        final Matrix4f view;
        final Matrix4f projection;
        /** Portals the player crossed since the capture, a portal followed by its reverse cancelled out. */
        final ArrayList<Portal> crossed = new ArrayList<>();

        Capture(ResourceKey<Level> dimension, Vec3 pos, Matrix4f view, Matrix4f projection) {
            this.dimension = dimension;
            this.pos = pos;
            this.view = view;
            this.projection = projection;
        }

        ResourceKey<Level> playerDimension() {
            return crossed.isEmpty() ? dimension : crossed.get(crossed.size() - 1).getDestDim();
        }
    }

    /** The culling camera of one view, in that view's world, and the portal whose rim limits it (null: none). */
    private record View(Vec3 pos, Matrix4f view, @Nullable Portal rim) {}

    /** One portal pass: its culling camera (null: live culling) and the renderer's capture fields to put back. */
    private record Pass(
        @Nullable View view, LevelRenderer renderer,
        @Nullable Frustum oldFrustum, @Nullable Vector3d oldPos, @Nullable Vector4f[] oldPoints
    ) {}

    private static @Nullable Capture capture;
    /** The capture applies this frame: the player is in the world its path ends in. */
    private static boolean active;
    /** The player's world's culling camera this frame, while {@link #active}. */
    private static @Nullable View outerView;
    /** The level renderer whose capture fields hold the player's world's culling camera. */
    private static @Nullable LevelRenderer fieldsOwner;
    private static final ArrayList<Pass> passes = new ArrayList<>();

    private IPFrustumCapture() {}

    /** Vanilla's {@code LevelRenderer.captureFrustum(Matrix4f, Matrix4f, double, double, double, Frustum)}. */
    public static void onCapture(LevelRenderer renderer, Matrix4f view, Matrix4f projection, double x, double y, double z) {
        ClientLevel level = Minecraft.getInstance().level;
        if (WorldRenderInfo.isRendering() || level == null) {
            // the key's flag is consumed by the player's world's pass, which comes first in the frame
            return;
        }
        if (fieldsOwner != null && fieldsOwner != renderer) {
            ((IPLevelRendererFrustumAccessor) fieldsOwner).ip_setCapturedFrustum(null);
        }
        capture = new Capture(level.dimension(), new Vec3(x, y, z), new Matrix4f(view), new Matrix4f(projection));
        active = true;
        outerView = new View(capture.pos, capture.view, null);
        fieldsOwner = renderer;
        LOGGER.info("[IPFrustumCapture] frustum captured in {} at {} {} {}; portals and portal views now cull from there",
            level.dimension().location(), String.format("%.2f", x), String.format("%.2f", y), String.format("%.2f", z));
    }

    /** Vanilla's {@code LevelRenderer.killFrustum()} (Shift+F3+U, on the player's world's renderer). */
    public static void onKill(LevelRenderer renderer) {
        if (capture == null) {
            return;
        }
        if (fieldsOwner != null && fieldsOwner != renderer) {
            ((IPLevelRendererFrustumAccessor) fieldsOwner).ip_setCapturedFrustum(null);
        }
        capture = null;
        active = false;
        outerView = null;
        fieldsOwner = null;
        LOGGER.info("[IPFrustumCapture] frustum released");
    }

    /** The client player went through {@code portal}: the captured camera's path goes through it too. */
    public static void onPlayerCrossed(Portal portal) {
        Capture c = capture;
        if (c == null) {
            return;
        }
        if (c.playerDimension() != portal.level().dimension()) {
            // the capture is suspended (the player left its path's world some other way): leave the path as it is
            return;
        }
        append(c.crossed, portal);
        LOGGER.info("[IPFrustumCapture] capture followed through portal {} into {}; {} portal(s) between it and the player",
            portal.getUUID(), portal.getDestDim().location(), c.crossed.size());
    }

    /** Head of the player's world's {@code LevelRenderer.renderLevel}, before vanilla captures. */
    public static void onFrameStart(LevelRenderer renderer) {
        passes.clear();
        active = false;
        outerView = null;
        Capture c = capture;
        ClientLevel level = Minecraft.getInstance().level;
        if (c == null || level == null) {
            return;
        }
        if (fieldsOwner != null && fieldsOwner != renderer) {
            ((IPLevelRendererFrustumAccessor) fieldsOwner).ip_setCapturedFrustum(null);
            fieldsOwner = null;
        }
        if (level.dimension() != c.playerDimension()) {
            if (fieldsOwner == renderer) {
                ((IPLevelRendererFrustumAccessor) renderer).ip_setCapturedFrustum(null);
                fieldsOwner = null;
            }
            return;
        }
        active = true;
        outerView = viewAlong(c, List.of());
        writeFields(renderer, c, outerView);
        fieldsOwner = renderer;
    }

    /** Whether culling goes by the captured camera in the view being drawn. */
    public static boolean isCulling() {
        return currentView() != null;
    }

    /** The captured camera's position in the view being drawn; only while {@link #isCulling}. */
    public static Vec3 cullingPos() {
        View view = currentView();
        return view == null ? Vec3.ZERO : view.pos;
    }

    /** A new frustum of the captured camera in the view being drawn, prepared at {@link #cullingPos}. */
    public static Frustum cullingFrustum() {
        View view = currentView();
        Frustum frustum = new Frustum(view.view, capture.projection);
        frustum.prepare(view.pos.x, view.pos.y, view.pos.z);
        return frustum;
    }

    /**
     * While {@link #isCulling}: the portal through which the captured camera sees the view being drawn (its rim limits
     * the culling), or null when the captured camera sees this world directly.
     */
    public static @Nullable Portal rimPortal() {
        View view = currentView();
        return view == null ? null : view.rim;
    }

    /**
     * While {@link #isCulling}: whether {@code portal} leads back along the captured camera's path, to where the
     * captured camera is or sees more directly. Its view is reached by cancelling the path's last portal, so it is
     * not judged from the culling camera of this view (which faces its back).
     */
    public static boolean leadsBack(Portal portal) {
        Portal rim = rimPortal();
        return rim != null && Portal.isReversePortal(rim, portal);
    }

    /**
     * Before a portal pass draws with {@code renderer}: the pass's WorldRenderInfo and portal layer are pushed.
     * Puts the captured camera, carried along the path to this view, into the renderer's capture fields.
     */
    public static void beginPass(LevelRenderer renderer) {
        IPLevelRendererFrustumAccessor fields = (IPLevelRendererFrustumAccessor) renderer;
        Capture c = capture;
        View view = null;
        if (active && c != null && PortalRendering.isRendering()) {
            view = viewAlong(c, PortalRendering.getPortalLayersView());
        }

        Frustum oldFrustum = fields.ip_getCapturedFrustum();
        if (view == null && oldFrustum == null) {
            // nothing captured anywhere: the usual case, nothing to swap
            passes.add(new Pass(null, renderer, null, null, null));
            return;
        }
        passes.add(new Pass(
            view, renderer, oldFrustum, new Vector3d(fields.ip_getFrustumPos()), fields.ip_getFrustumPoints().clone()
        ));
        if (view != null) {
            writeFields(renderer, c, view);
        }
        else {
            fields.ip_setCapturedFrustum(null);
        }
    }

    /** After the pass {@link #beginPass} began: puts the renderer's capture fields back. */
    public static void endPass(LevelRenderer renderer) {
        if (passes.isEmpty()) {
            return;
        }
        Pass pass = passes.remove(passes.size() - 1);
        if (pass.renderer != renderer) {
            LOGGER.warn("[IPFrustumCapture] portal passes ended out of order; capture fields not restored");
            return;
        }
        if (pass.oldPos == null) {
            return;
        }
        IPLevelRendererFrustumAccessor fields = (IPLevelRendererFrustumAccessor) renderer;
        fields.ip_setCapturedFrustum(pass.oldFrustum);
        fields.ip_getFrustumPos().set(pass.oldPos);
        System.arraycopy(pass.oldPoints, 0, fields.ip_getFrustumPoints(), 0, 8);
    }

    private static @Nullable View currentView() {
        if (!active) {
            return null;
        }
        if (passes.isEmpty()) {
            return WorldRenderInfo.isRendering() ? null : outerView;
        }
        return passes.get(passes.size() - 1).view;
    }

    /** Appends {@code portal} to a path, cancelling the last portal instead when {@code portal} is its reverse. */
    private static void append(List<Portal> path, Portal portal) {
        if (!path.isEmpty() && Portal.isReversePortal(path.get(path.size() - 1), portal)) {
            path.remove(path.size() - 1);
        }
        else {
            path.add(portal);
        }
    }

    /** The captured camera carried through the portals it crossed, then through {@code layers}. */
    private static View viewAlong(Capture c, List<Portal> layers) {
        ArrayList<Portal> path = new ArrayList<>(c.crossed);
        for (Portal portal : layers) {
            append(path, portal);
        }
        Vec3 pos = c.pos;
        Matrix4f view = new Matrix4f(c.view);
        for (Portal portal : path) {
            pos = portal.transformPoint(pos);
            Matrix4f transformation = portal.getAdditionalCameraTransformation();
            if (transformation != null) {
                view.mul(transformation);
            }
        }
        return new View(pos, view, path.isEmpty() ? null : path.get(path.size() - 1));
    }

    /** Puts {@code view} into vanilla's capture fields of {@code renderer}, as its {@code captureFrustum} would. */
    private static void writeFields(LevelRenderer renderer, Capture c, View view) {
        IPLevelRendererFrustumAccessor fields = (IPLevelRendererFrustumAccessor) renderer;
        fields.ip_setCapturedFrustum(new Frustum(view.view, c.projection));
        fields.ip_getFrustumPos().set(view.pos.x, view.pos.y, view.pos.z);
        Vector4f[] points = fields.ip_getFrustumPoints();
        Matrix4f inverse = new Matrix4f(c.projection).mul(view.view).invert();
        for (int i = 0; i < 8; i++) {
            Vector4f corner = inverse.transform(new Vector4f(NDC_CORNERS[i]));
            points[i] = corner.div(corner.w());
        }
    }
}
