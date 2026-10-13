/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.dh_integration;

import com.mojang.logging.LogUtils;
import com.seibel.distanthorizons.api.methods.events.sharedParameterObjects.DhApiRenderParam;
import com.seibel.distanthorizons.core.util.math.DhMat4f;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.opengl.GL20;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.render.FrontClipping;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * Applies Immersive Portals' front clipping plane to Distant Horizons' own shader programs.
 * <p>
 * IP clips everything drawn in a portal pass against the destination portal's plane
 * ({@link FrontClipping}): the vanilla, Sodium and Veil shaders get a {@code gl_ClipDistance[0]}
 * line patched into their vertex stage and a uniform holding the plane. DH draws its LODs and
 * clouds with its own programs, which never saw that plane, so everything of the destination
 * dimension that lies between the (transformed) camera and the destination portal was drawn into
 * the portal view. The farther the viewer stands from the portal, the more terrain fits in that gap.
 * <p>
 * This class patches DH's vertex shaders at load time ({@link #patchShaderSource}) and supplies
 * the plane per draw: in camera-relative world space for the terrain shader (which has that
 * position as {@code vertexWorldPos}) and in clip space for the generic object shaders (clouds,
 * beacons), whose vertex stage only has {@code gl_Position}. A clip-space plane {@code q} satisfies
 * {@code dot(q, M*p) == dot(plane, p)} for {@code q = inverse(M)^T * plane}, {@code M} being DH's
 * projection * model-view. Outside portal passes the uniform is {@code (0,0,0,1)}, which never clips.
 */
public final class IPDhPortalClip {
    private static final Logger LOGGER = LogUtils.getLogger();

    /** Camera-relative world-space plane, used by the terrain vertex shader. */
    public static final String WORLD_UNIFORM = "iportal_ClippingEquation";
    /** Clip-space plane, used by the generic object vertex shaders. */
    public static final String CLIP_SPACE_UNIFORM = "iportal_ClipSpaceClippingEquation";

    private static final String TERRAIN_VERT = "shaders/terrain/gl/vert.vert";
    private static final String GENERIC_INSTANCED_VERT = "shaders/generic/gl/instanced/vert.vert";
    private static final String GENERIC_DIRECT_VERT = "shaders/generic/gl/direct/vert.vert";

    private static final float[] NO_CLIPPING = {0f, 0f, 0f, 1f};

    private IPDhPortalClip() {
    }

    /** Patches the vertex shaders that draw DH geometry; returns {@code source} unchanged for anything else. */
    public static String patchShaderSource(@Nullable String path, @Nullable String source) {
        if (path == null || source == null || !IPDhPortalView.ENABLED) {
            return source;
        }
        String normalized = path.replace('\\', '/');
        try {
            if (normalized.endsWith(TERRAIN_VERT)) {
                return insertClipping(
                    path, source, WORLD_UNIFORM,
                    "gl_ClipDistance[0] = dot(vertexWorldPos, " + WORLD_UNIFORM + ".xyz) + " + WORLD_UNIFORM + ".w;"
                );
            }
            if (normalized.endsWith(GENERIC_INSTANCED_VERT) || normalized.endsWith(GENERIC_DIRECT_VERT)) {
                return insertClipping(
                    path, source, CLIP_SPACE_UNIFORM,
                    "gl_ClipDistance[0] = dot(gl_Position, " + CLIP_SPACE_UNIFORM + ");"
                );
            }
        } catch (Throwable e) {
            LOGGER.warn("[IPDhPortalClip] could not patch DH shader {}: {}", path, e.toString());
        }
        return source;
    }

    private static String insertClipping(String path, String source, String uniform, String clipLine) {
        int mainIndex = source.indexOf("void main");
        int lastBrace = source.lastIndexOf('}');
        if (mainIndex < 0 || lastBrace < mainIndex) {
            LOGGER.warn("[IPDhPortalClip] DH shader {} has an unexpected layout; portal clipping not applied to it", path);
            return source;
        }
        String patched = source.substring(0, mainIndex)
            + "uniform vec4 " + uniform + ";\n\n"
            + source.substring(mainIndex, lastBrace)
            + "    " + clipLine + "\n"
            + source.substring(lastBrace);
        LOGGER.debug("[IPDhPortalClip] patched DH shader {} with IP's front clipping plane", path);
        return patched;
    }

    /** IP's active front clipping plane in camera-relative world space, or null if nothing is clipped now. */
    private static double @Nullable [] activeWorldPlane() {
        if (!IPDhPortalView.ENABLED || !PortalRendering.isRendering() || !FrontClipping.isClippingEnabled) {
            return null;
        }
        double[] equation = FrontClipping.getActiveClipPlaneEquationBeforeModelView();
        return equation != null && equation.length == 4 ? equation : null;
    }

    public static float[] worldPlane() {
        double[] plane = activeWorldPlane();
        if (plane == null) {
            return NO_CLIPPING;
        }
        return new float[]{(float) plane[0], (float) plane[1], (float) plane[2], (float) plane[3]};
    }

    public static float[] clipSpacePlane(@Nullable DhApiRenderParam renderParam) {
        double[] plane = activeWorldPlane();
        if (plane == null || renderParam == null
            || renderParam.dhProjectionMatrix == null || renderParam.dhModelViewMatrix == null
        ) {
            return NO_CLIPPING;
        }
        Matrix4f projectionModelView = DhMat4f.createJomlMatrix(renderParam.dhProjectionMatrix)
            .mul(DhMat4f.createJomlMatrix(renderParam.dhModelViewMatrix));
        Vector4f q = new Vector4f((float) plane[0], (float) plane[1], (float) plane[2], (float) plane[3]);
        projectionModelView.invert().transpose().transform(q);
        return new float[]{q.x, q.y, q.z, q.w};
    }

    /**
     * Sets a vec4 uniform on the currently bound program. {@code cachedLocation} is the value from
     * the previous call for the same program (start with {@link #UNRESOLVED}); the resolved location
     * is returned so the caller can cache it.
     */
    public static int setPlaneUniform(int programId, int cachedLocation, String name, float[] plane) {
        int location = cachedLocation;
        if (location == UNRESOLVED) {
            location = GL20.glGetUniformLocation(programId, name);
            if (location < 0) {
                LOGGER.debug("[IPDhPortalClip] DH program {} has no {} uniform; portal clipping not applied to it", programId, name);
            }
        }
        if (location >= 0) {
            GL20.glUniform4f(location, plane[0], plane[1], plane[2], plane[3]);
        }
        return location;
    }

    public static final int UNRESOLVED = -2;
}
