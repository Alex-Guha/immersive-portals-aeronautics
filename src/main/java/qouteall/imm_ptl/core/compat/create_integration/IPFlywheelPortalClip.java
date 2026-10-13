/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Applies Immersive Portals' front clipping plane to Flywheel's shader programs, which draw Create
 * contraptions (trains, ships' kinetic blocks) and other Flywheel visuals.
 * <p>
 * Flywheel renders inside portal views since the fork (see {@code MixinFlywheelRenderDispatcher_PortalPassSkip}),
 * but its vertex shaders never received the plane IP clips the vanilla/Sodium/Veil shaders with, so a
 * contraption of the destination dimension lying between the (transformed) camera and the destination
 * portal was drawn into the portal view. Flywheel assembles every vertex program from
 * {@code flywheel:internal/common.vert}, whose {@code _flw_main} ends in
 * {@code gl_Position = flw_viewProjection * flw_vertexPos;}; {@code flw_vertexPos} and
 * {@code flw_cameraPos} are both relative to Flywheel's render origin, so their difference is the
 * camera-relative world position IP's plane is expressed in.
 */
public final class IPFlywheelPortalClip {
    private static final Logger LOGGER = LogUtils.getLogger();

    public static final String UNIFORM = "iportal_ClippingEquation";

    private static final String POSITION_LINE = "gl_Position = flw_viewProjection * flw_vertexPos;";
    private static final String MAIN_SIGNATURE = "void _flw_main(";
    private static final float[] NO_CLIPPING = {0f, 0f, 0f, 1f};
    private static boolean logged;
    private static boolean planeLogged;

    private IPFlywheelPortalClip() {
    }

    /** Patches an assembled Flywheel vertex shader; anything without the main-vertex position line is returned unchanged. */
    public static CharSequence patchVertexSource(@Nullable CharSequence source) {
        if (source == null) {
            return null;
        }
        String text = source.toString();
        int main = text.indexOf(MAIN_SIGNATURE);
        int position = text.indexOf(POSITION_LINE);
        if (main < 0 || position < main) {
            return source;
        }
        String clipLine = POSITION_LINE
            + "\n    gl_ClipDistance[0] = dot(flw_vertexPos.xyz - flw_cameraPos, " + UNIFORM + ".xyz) + " + UNIFORM + ".w;"
            // the straddle cut of the ship this vertex belongs to, relative to Flywheel's render origin
            + "\n    gl_ClipDistance[1] = dot(flw_vertexPos.xyz, ipa_shipClip.xyz) + ipa_shipClip.w;";
        String patched = text.substring(0, main)
            + "uniform vec4 " + UNIFORM + ";\n"
            + IPFlywheelStraddleClip.declarations(text) + "\n"
            + text.substring(main, position)
            + clipLine
            + text.substring(position + POSITION_LINE.length());
        patched = IPFlywheelStraddleClip.patchMain(patched);
        if (!logged) {
            logged = true;
            LOGGER.debug("[IPFlywheelPortalClip] patching Flywheel vertex programs with IP's front clipping plane");
        }
        return patched;
    }

    /** IP's plane for the far-half draw in progress ({@link IPFlywheelFarHalf}), in that engine's camera frame. */
    static float @Nullable [] farHalfOverride;

    /** IP's active front clipping plane (camera-relative world space) while a portal pass clips, else null. */
    public static float @Nullable [] activePlane() {
        if (farHalfOverride != null) {
            return farHalfOverride;
        }
        if (!PortalRendering.isRendering() || !FrontClipping.isClippingEnabled) {
            return null;
        }
        double[] plane = FrontClipping.getActiveClipPlaneEquationBeforeModelView();
        if (plane == null || plane.length != 4) {
            return null;
        }
        if (!planeLogged) {
            planeLogged = true;
            LOGGER.debug("[IPFlywheelPortalClip] feeding IP's clip plane to a Flywheel program in a portal pass");
        }
        return new float[]{(float) plane[0], (float) plane[1], (float) plane[2], (float) plane[3]};
    }

    public static float[] noClipping() {
        return NO_CLIPPING;
    }
}
