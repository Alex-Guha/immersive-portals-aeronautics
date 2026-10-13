/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_straddle.client;

import com.mojang.logging.LogUtils;
import dev.eriksonn.aeronautics.content.blocks.hot_air.balloon.effect.HeatedCulledRenderRegion;
import dev.ryanhcode.sable.Sable;
import dev.ryanhcode.sable.companion.math.Pose3dc;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import foundry.veil.api.client.render.VeilRenderSystem;
import foundry.veil.api.client.render.shader.processor.ShaderPreProcessor;
import foundry.veil.api.client.render.shader.program.ShaderProgram;
import foundry.veil.platform.VeilEventPlatform;
import io.github.ocelot.glslprocessor.api.GlslInjectionPoint;
import io.github.ocelot.glslprocessor.api.GlslParser;
import io.github.ocelot.glslprocessor.api.node.GlslTree;
import io.github.ocelot.glslprocessor.api.node.function.GlslFunctionNode;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector3d;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;
import org.slf4j.Logger;

/**
 * Cuts Aeronautics' hot-air overlay of a straddling ship at its portal plane, like the ship's terrain
 * (docs/lessons/straddle-rendering.md). The overlay is drawn per balloon with Aeronautics' own Veil program
 * {@code aeronautics:hot_air_overlay}, so it had no cut: the gas of the part past the portal still showed behind the
 * portal in the ship's own world. A Veil preprocessor makes the program's vertex shader write
 * {@code gl_ClipDistance[1]} (IP's portal clip keeps index 0) from a plane uniform in the balloon's mesh space;
 * {@link #render} sets it and enables the clip distance for the balloons of cut ships. The overlay is skipped in
 * portal passes ({@code MixinAeronauticsBalloonEffect_PortalPassSkip}), so only the direct, source-side cut is needed.
 * Client only; loaded only with Aeronautics (which bundles Veil) and Sable.
 */
public final class IPBalloonOverlayClip {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String UNIFORM = "ipa_BalloonClip";
    private static boolean logged;

    private IPBalloonOverlayClip() {
    }

    /** Registers the shader patch; must run from the mod constructor (Veil's platform events require it). */
    public static void init() {
        VeilEventPlatform.INSTANCE.onVeilAddShaderProcessors(
                (resourceProvider, registry) -> registry.addPreprocessor(IPBalloonOverlayClip::patchShader));
    }

    private static void patchShader(final ShaderPreProcessor.Context context, final GlslTree tree) {
        final ResourceLocation name = context.name();
        // the shader id (aeronautics:hot_air_overlay) or its file (.../program/hot_air_overlay.vsh)
        if (!context.isVertex() || !context.isSourceFile() || name == null || !"aeronautics".equals(name.getNamespace())
                || !name.getPath().matches("(.*/)?hot_air_overlay(\\.vsh)?")) {
            return;
        }
        try {
            tree.getBody().addAll(GlslInjectionPoint.BEFORE_MAIN, GlslParser.parse("uniform vec4 " + UNIFORM + ";").getBody());
            final GlslFunctionNode clip = GlslParser.parse(
                    "void ipa_clip() { gl_ClipDistance[1] = dot(" + UNIFORM + ", vec4(Position, 1.0)); }"
            ).functions().findFirst().orElseThrow();
            final GlslFunctionNode main = tree.mainFunction().orElseThrow();
            main.getBody().addAll(clip.getBody());
            LOGGER.info("[IPBalloonClip] patched {} with the straddle clip plane", context.name());
        } catch (final Exception e) {
            LOGGER.error("[IPBalloonClip] could not patch {}; balloons of straddling ships are not cut", context.name(), e);
        }
    }

    /**
     * Draws one balloon's overlay ({@code HeatedCulledRenderRegion.render}), cut at its ship's portal plane while the
     * ship straddles. The mesh is in the region's space: world = pose(origin + p) = pose(origin) + R p.
     */
    public static void render(final HeatedCulledRenderRegion region, final Matrix4f modelView, final Matrix4f projection) {
        final Vec3 origin = region.getOrigin();
        final ClientSubLevel ship = origin == null ? null : Sable.HELPER.getContainingClient(origin);
        final IPStraddleRender.KeepPlane plane = ship == null ? null : IPStraddleRender.planeFor(ship);
        final ShaderProgram shader = plane == null ? null : VeilRenderSystem.getShader();
        if (shader == null || !shader.hasUniform(UNIFORM)) {
            region.render(modelView, projection);
            return;
        }
        final Pose3dc pose = ship.renderPose();
        final Vector3d n = pose.orientation().transformInverse(
                new Vector3d(plane.normal().x, plane.normal().y, plane.normal().z));
        final double w = plane.distance(pose.transformPosition(origin)) + IPStraddleRender.seamReach();
        shader.getUniformSafe(UNIFORM).setVector((float) n.x, (float) n.y, (float) n.z, (float) w);
        if (!logged) {
            logged = true;
            LOGGER.info("[IPBalloonClip] cutting the hot-air overlay of straddling ship {}", ship.getUniqueId());
        }
        GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
        try {
            region.render(modelView, projection);
        } finally {
            GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
        }
    }
}
