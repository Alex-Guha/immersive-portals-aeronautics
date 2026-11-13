/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.engine.uniform.Uniforms;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.RenderBuffers;
import net.minecraft.util.Mth;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Quaterniondc;
import org.joml.Quaternionf;
import org.joml.Vector3f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.ClientWorldLoader;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleClientSessions;
import qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

/**
 * The far half of a straddling ship's Flywheel parts (Create cogs, shafts, propellers), drawn in the destination
 * dimension (docs/lessons/straddle-rendering.md). Those parts live only in the ship's own dimension's Flywheel
 * engine, so after the destination's Flywheel pass that engine draws again, the way IP draws a portal view: with the
 * camera mapped back through the portal (position {@code isometry^-1(camera)}, view rotated by the portal's rotation),
 * which puts every part where the far half is. In that draw only the ship's embedding is kept, cut to the part past
 * the portal plane; the rest of that engine is rejected ({@link IPFlywheelStraddleClip}), and IP's portal clip, when
 * the destination is itself drawn in a portal view, is rotated into that frame.
 * <p>
 * Create carriages reaching through a portal before or after Create splits them use the same draw
 * ({@link IPCreateCarriageCrossingClient#drawFarHalves}).
 * <p>
 * If the ship's dimension has not started its Flywheel frame yet (nothing shows it this frame), it is started here
 * with the mapped camera, so the ship's embedding follows its current pose and its visuals update.
 */
public final class IPFlywheelFarHalf {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean SABLE = ModList.get().isLoaded("sable");
    /** The frame each level last started its Flywheel frame in (weak: client levels come and go). */
    private static final Map<ClientLevel, Integer> STARTED = new WeakHashMap<>();
    private static boolean drawing;
    private static boolean failed;
    private static boolean logged;
    private static boolean missingLogged;
    /** A far-half draw ran (and overwrote the shared frame uniforms). */
    private static boolean drawn;

    private IPFlywheelFarHalf() {
    }

    /**
     * @return whether a far-half draw is in progress (IP's own portal-pass hooks on Flywheel stay out of it)
     */
    public static boolean isDrawing() {
        return drawing;
    }

    /**
     * @return whether an entity of a straddling ship drawn now, in its far half ({@code MixinLevelRenderer_StraddleFarHalf}),
     * is visualized by Flywheel: this class draws the ship's Flywheel parts there, its contraptions included. Flywheel
     * serves a level only while it is {@code Minecraft.level}, so Create's contraption renderer would otherwise draw
     * the contraption's blocks a second time on top.
     */
    public static boolean visualizesFarHalfEntities() {
        return SABLE && !failed && dev.engine_room.flywheel.api.backend.BackendManager.isBackendOn()
            && IPStraddleRender.isProjecting();
    }

    public static void onStartLevelRender(final RenderContext context) {
        STARTED.put(context.level(), RenderStates.frameIndex);
        IPCreateCarriageCrossingClient.onFrameStart();
    }

    /**
     * After a level's Flywheel pass: draws the far halves of the ships straddling into it and of the Create carriages
     * reaching into it through a portal ({@link IPCreateCarriageCrossingClient#drawFarHalves}).
     */
    public static void afterEntities(final RenderContext context) {
        if (drawing || failed) {
            return;
        }
        // Colorwheel runs afterEntities again for its translucent and shadow phases; a far half is drawn once, after
        // the solid phase (its draw runs that phase only; in the shadow pass it would go into the shadow map)
        if (IPFlywheelEmbeddings.COLORWHEEL && IPColorwheelEmbeddings.isTranslucentOrShadowContext(context)) {
            return;
        }
        if (SABLE && !IPStraddleClientSessions.isEmpty()) {
            drawShips(context);
        }
        if (!failed) {
            IPCreateCarriageCrossingClient.drawFarHalves(context);
        }
    }

    private static void drawShips(final RenderContext context) {
        final List<IPStraddleClientSessions.Session> sessions = IPStraddleClientSessions.renderSessionsInto(context.level().dimension());
        for (final IPStraddleClientSessions.Session session : sessions) {
            // a ship straddling a portal into its own dimension: its own engine draws its far half too (the skip of
            // such sessions dated from 1.14.0, before same-dimension straddles; carriages never had it)
            final ClientSubLevel ship = IPStraddleRender.shipOf(session);
            if (ship == null || !(ship.getLevel() instanceof final ClientLevel shipLevel)) {
                continue;
            }
            final Quaterniondc q = session.map().rotation();
            final Quaternionf rotation = new Quaternionf((float) q.x(), (float) q.y(), (float) q.z(), (float) q.w());
            final Vec3 cameraPos = session.map().unmapPoint(context.camera().getPosition());
            if (!draw(context, shipLevel, rotation, (float) session.map().scale(), cameraPos, new IPFlywheelStraddleClip.ShipFarHalf(ship, session))) {
                return;
            }
        }
    }

    /**
     * Draws part of another level's Flywheel engine into this pass as seen through a portal.
     *
     * @param sourceLevel the level whose engine draws (the far half's own dimension)
     * @param rotation    the portal's rotation: a vector v of the source level appears as R v in this pass's level
     * @param scale       the portal's scale k: v appears as k R v (1 for a straddling ship's far half)
     * @param cameraPos   this pass's camera mapped back into the source level
     * @param selection   which environments are drawn, and their cuts
     * @return false if drawing failed (turned off for the session)
     */
    static boolean draw(final RenderContext context, final ClientLevel sourceLevel, final Quaternionf rotation, final float scale,
                        final Vec3 cameraPos, final IPFlywheelStraddleClip.FarHalf selection) {
        if (drawing || failed) {
            return false;
        }
        // Flywheel serves a level's visualization only while it is Minecraft.level (IP's portal passes swap it
        // for the same reason): the source dimension is the current level for the whole draw
        final Minecraft minecraft = Minecraft.getInstance();
        final ClientLevel rendered = minecraft.level;
        minecraft.level = sourceLevel;
        try {
            final VisualizationManagerImpl manager = VisualizationManagerImpl.get(sourceLevel);
            if (manager != null) {
                draw(context, sourceLevel, manager, rotation, scale, cameraPos, selection);
            } else if (!missingLogged) {
                missingLogged = true;
                LOGGER.info("[IPStraddle] no Flywheel visualization for {}: far half of Flywheel parts not drawn", sourceLevel.dimension().location());
            }
            return true;
        } catch (final Throwable t) {
            failed = true;
            LOGGER.warn("[IPStraddle] cannot draw the far half of Flywheel parts; turned off for this session", t);
            return false;
        } finally {
            minecraft.level = rendered;
            if (drawn) {
                drawn = false;
                // the frame uniforms are shared: give them back to this pass (crumbling draws later read them)
                Uniforms.update(context);
                // Colorwheel's are shared too, and its translucent draw of this pass follows (IPColorwheelFarHalf)
                if (IPFlywheelEmbeddings.isColorwheelBackend()) {
                    IPColorwheelFarHalf.end(context);
                }
            }
        }
    }

    private static void draw(final RenderContext context, final ClientLevel sourceLevel, final VisualizationManagerImpl manager,
                             final Quaternionf rotation, final float scale, final Vec3 cameraPos,
                             final IPFlywheelStraddleClip.FarHalf selection) {
        // a vector v of the source world appears as R v in the destination, so the source world is seen with view * R
        // a scaled portal (scale k) shows the source level k times its size: d - c_d = k R (s - c_s)
        final Matrix4f modelView = new Matrix4f(context.modelView()).rotate(rotation).scale(scale);
        final Matrix4f viewProjection = new Matrix4f(context.projection()).mul(modelView);
        final Vector3f look = rotation.transformInverse(new Vector3f(context.camera().getLookVector()));
        final LevelRenderer renderer = ClientWorldLoader.getWorldRenderer(sourceLevel.dimension());
        final RenderContext sourceContext = new FarHalfContext(renderer != null ? renderer : context.renderer(), sourceLevel,
            context.buffers(), modelView, context.projection(), viewProjection, new FarHalfCamera(cameraPos, look),
            context.partialTick());

        // IP's portal plane (camera-relative, destination frame) seen from the source frame: the normal rotated back
        float[] portalPlane = IPFlywheelPortalClip.noClipping();
        boolean clipPortal = false;
        if (PortalRendering.isRendering()) {
            final double[] equation = FrontClipping.getActiveClipPlaneEquationBeforeModelView();
            if (equation != null) {
                final Vector3f n = rotation.transformInverse(new Vector3f((float) equation[0], (float) equation[1], (float) equation[2]));
                portalPlane = new float[]{n.x, n.y, n.z, (float) (equation[3] / scale)};
                clipPortal = true;
            }
        }
        final boolean clipPortalWasOn = GL11.glIsEnabled(GL30.GL_CLIP_DISTANCE0);

        drawing = true;
        drawn = true;
        IPFlywheelPortalClip.farHalfOverride = portalPlane;
        try {
            if (clipPortal && !clipPortalWasOn) {
                GL11.glEnable(GL30.GL_CLIP_DISTANCE0);
            }
            final Integer started = STARTED.get(sourceLevel);
            if (started == null || started != RenderStates.frameIndex) {
                manager.renderDispatcher().onStartLevelRender(sourceContext);
            }
            if (IPFlywheelEmbeddings.isColorwheelBackend()) {
                // the mapped camera for IP's portal clip (flw_cameraPos) and the pack, whether or not the source's
                // engine prepares its frame here
                IPColorwheelFarHalf.begin(sourceContext);
            }
            IPFlywheelStraddleClip.beginFarHalf(selection);
            manager.renderDispatcher().afterEntities(sourceContext);
        } finally {
            IPFlywheelStraddleClip.endFarHalf();
            IPFlywheelPortalClip.farHalfOverride = null;
            drawing = false;
            if (clipPortal && !clipPortalWasOn) {
                GL11.glDisable(GL30.GL_CLIP_DISTANCE0);
            }
        }
        if (!logged) {
            logged = true;
            LOGGER.info("[IPStraddle] drawing far halves of Flywheel parts through portals");
        }
    }

    /** The destination pass's camera mapped back into the ship's dimension. */
    private static final class FarHalfCamera extends Camera {
        FarHalfCamera(final Vec3 position, final Vector3f look) {
            this.setPosition(position);
            this.setRotation((float) Math.toDegrees(Math.atan2(-look.x, look.z)),
                (float) Math.toDegrees(Math.asin(Mth.clamp(-look.y, -1.0F, 1.0F))));
        }
    }

    private record FarHalfContext(LevelRenderer renderer, ClientLevel level, RenderBuffers buffers, Matrix4fc modelView,
                                  Matrix4fc projection, Matrix4fc viewProjection, Camera camera, float partialTick)
        implements RenderContext {
    }
}
