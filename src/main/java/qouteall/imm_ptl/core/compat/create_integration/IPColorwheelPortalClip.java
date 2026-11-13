/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Applies Immersive Portals' front clipping plane to Colorwheel's programs: Colorwheel runs Flywheel's instancing and
 * indirect backends under an Iris shader pack, so with it trains and contraptions are Flywheel visuals drawn through
 * the pack's own vertex code.
 * <p>
 * Colorwheel compiles its programs itself ({@code ClrwlCompilation}, not Flywheel's {@code Compilation}), so
 * {@link IPFlywheelPortalClip#patchVertexSource} never sees them and their parts back-bled in portal views. Every
 * Colorwheel vertex program is assembled around {@code colorwheel:internal/common.vert}, whose
 * {@code _clrwl_main(FlwInstance, ...)} computes Flywheel's {@code flw_vertexPos} and ends by calling the pack's
 * renamed main, {@code _clrwl_shader_main();}, which writes {@code gl_Position}. {@code flw_vertexPos} and
 * {@code flw_cameraPos} are both relative to the render origin there too (Colorwheel 1.3.2,
 * {@code ClrwlFrameUniforms}), so the plane is the same expression as in Flywheel's programs.
 * <p>
 * The uniform is fed by {@code MixinFlywheelGlProgram_PortalClipPlane}: {@code ClrwlProgram} extends Flywheel's
 * {@code GlProgram} and its {@code bind} calls {@code super.bind()}. The clip window is opened by
 * {@code MixinFlywheelRenderDispatcher_PortalClipState}, as Colorwheel draws its solid, translucent and shadow phases
 * through {@code RenderDispatcher.afterEntities}. Shadow programs never clip: IP leaves a pack's own shadow programs
 * unclipped, so things behind the far portal still cast their shadows into the view.
 * <p>
 * {@code gl_ClipDistance[1]} is the per-embedding cut of {@link IPFlywheelStraddleClip} (straddling ships, Create
 * carriages crossing a portal, far-half draws), patched the way {@link IPFlywheelStraddleClip#patchMain} patches
 * Flywheel's programs: Colorwheel's {@code indirect/main.vert} and {@code instancing/main.vert} unpack an embedding's
 * matrices with the same statements as Flywheel's. The indirect planes come from a storage buffer that cannot sit at
 * Flywheel's binding 8 (Colorwheel uses 0-9 and moves the pack's own buffers to 10 and up), so it sits right after the
 * pack's buffers ({@link #shipClipBinding}). Both distances are computed from Colorwheel's {@code flw_vertexPos} before
 * the pack's main runs and written after it.
 */
public final class IPColorwheelPortalClip {
    private static final Logger LOGGER = LogUtils.getLogger();

    private static final String MAIN_SIGNATURE = "void _clrwl_main(FlwInstance";
    private static final String PACK_MAIN_CALL = "_clrwl_shader_main();";
    private static final String GEOMETRY_MAIN = "void _clrwl_main()";
    private static final String INDIRECT_UNPACK = "_flw_unpackMatrices(_flw_matrices[draw.matrixIndex]";
    private static final String INSTANCING_MATRIX = "_flw_modelMatrix = _flw_modelMatrixUniform;";
    private static final java.util.regex.Pattern MAIN_START = java.util.regex.Pattern.compile("void\\s+main\\s*\\(\\s*\\)\\s*\\{");
    /** Colorwheel reserves storage buffer bindings 0-9 and puts the pack's own after them. */
    private static final int COLORWHEEL_BINDINGS = 10;

    /** The binding of the ship cut planes in Colorwheel's indirect programs, or -1 if the GPU has none free. */
    private static volatile int shipClipBinding = -1;
    private static boolean bindingLogged;

    private static boolean logged;
    private static boolean failureLogged;
    private static boolean perVertexLogged;
    private static boolean geometryLogged;

    private IPColorwheelPortalClip() {
    }

    /** Patches an assembled Colorwheel vertex shader; any other source is returned unchanged. */
    public static CharSequence patchSource(@Nullable CharSequence source) {
        if (source == null) {
            return null;
        }
        String text = source.toString();
        int main = text.indexOf(MAIN_SIGNATURE);
        if (main < 0) {
            // a geometry shader passes no clip distance on from the vertex shader: those parts are not clipped
            if (!geometryLogged && text.contains(GEOMETRY_MAIN) && text.contains("EmitVertex")) {
                geometryLogged = true;
                LOGGER.info("[IPColorwheelClip] the shader pack gives a Colorwheel program a geometry shader: its parts are not clipped in portal views");
            }
            return source;
        }
        int call = text.lastIndexOf(PACK_MAIN_CALL);
        if (call < main) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("[IPColorwheelClip] a Colorwheel vertex shader has no \"{}\" after _clrwl_main: Colorwheel changed, its parts are not clipped in portal views", PACK_MAIN_CALL);
            }
            return source;
        }
        if (text.contains("gl_PerVertex")) {
            // a redeclared gl_PerVertex block without gl_ClipDistance would not compile with the line below
            if (!perVertexLogged) {
                perVertexLogged = true;
                LOGGER.info("[IPColorwheelClip] a Colorwheel vertex shader redeclares gl_PerVertex: left unclipped in portal views");
            }
            return source;
        }
        final String uniform = IPFlywheelPortalClip.UNIFORM;
        final String ship = IPFlywheelStraddleClip.UNIFORM;
        final String tail = text.substring(call + PACK_MAIN_CALL.length());
        final boolean indirect = tail.contains(INDIRECT_UNPACK);
        final int binding = indirect ? updateShipClipBinding() : -1;
        final StringBuilder declarations = new StringBuilder()
            .append("uniform vec4 ").append(uniform).append(";\n")
            .append("uniform vec4 ").append(ship).append(";\n")
            .append("vec4 ipa_shipClip = vec4(0.0, 0.0, 0.0, 1.0);\n");
        if (binding >= 0) {
            declarations.append("#ifdef FLW_EMBEDDED\nlayout(std430, binding = ").append(binding)
                .append(") restrict readonly buffer IpaShipClipBuffer {\n    vec4 ipa_shipClips[];\n};\n#endif\n");
        }
        final String beforeCall = "\n#ifdef _CLRWL_IS_SHADOW_PASS"
            + "\n    float ipa_clip0 = 1.0;"
            + "\n#else"
            + "\n    float ipa_clip0 = dot(flw_vertexPos.xyz - flw_cameraPos, " + uniform + ".xyz) + " + uniform + ".w;"
            + "\n#endif"
            + "\n    float ipa_clip1 = dot(flw_vertexPos.xyz, ipa_shipClip.xyz) + ipa_shipClip.w;\n    ";
        final String afterCall = "\n    gl_ClipDistance[0] = ipa_clip0;\n    gl_ClipDistance[1] = ipa_clip1;";
        final String patched = text.substring(0, main)
            + declarations
            + text.substring(main, call)
            + beforeCall
            + PACK_MAIN_CALL
            + afterCall
            + patchShipClipMain(tail, ship, binding >= 0);
        if (!logged) {
            logged = true;
            LOGGER.info("[IPColorwheelClip] patching Colorwheel vertex programs with IP's front clipping plane");
        }
        return patched;
    }

    /**
     * Makes Colorwheel's {@code main} (in the source after {@code _clrwl_main}) pick the embedding's plane: first the
     * uniform, which world-level draws keep (a far-half draw sets it to reject them), then, for an embedded draw, the
     * environment's plane from the buffer (indirect) or the uniform set per environment (instancing).
     */
    private static String patchShipClipMain(String tail, final String ship, final boolean planeBuffer) {
        final java.util.regex.Matcher matcher = MAIN_START.matcher(tail);
        int mainEnd = -1;
        while (matcher.find()) {
            mainEnd = matcher.end();
        }
        if (mainEnd < 0) {
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("[IPColorwheelClip] a Colorwheel vertex shader has no main after _clrwl_main: Colorwheel changed, crossing parts are not cut");
            }
            return tail;
        }
        tail = tail.substring(0, mainEnd) + "\n    ipa_shipClip = " + ship + ";" + tail.substring(mainEnd);
        if (planeBuffer) {
            int from = 0;
            while (true) {
                final int unpack = tail.indexOf(INDIRECT_UNPACK, from);
                if (unpack < 0) {
                    break;
                }
                final int end = tail.indexOf(';', unpack);
                if (end < 0) {
                    break;
                }
                final String insert = "\n            ipa_shipClip = ipa_shipClips[draw.matrixIndex];";
                tail = tail.substring(0, end + 1) + insert + tail.substring(end + 1);
                from = end + 1 + insert.length();
            }
        }
        final int instancing = tail.indexOf(INSTANCING_MATRIX);
        if (instancing >= 0) {
            final int end = instancing + INSTANCING_MATRIX.length();
            tail = tail.substring(0, end) + "\n        ipa_shipClip = " + ship + ";" + tail.substring(end);
        }
        return tail;
    }

    /**
     * @return the binding of the ship cut planes in Colorwheel's indirect programs: the first after the current pack's
     * own storage buffers (which Colorwheel binds from 10 on), or -1 if the GPU has no such binding
     */
    public static int shipClipBinding() {
        return shipClipBinding;
    }

    private static int updateShipClipBinding() {
        int binding = -1;
        int max = -1;
        try {
            final int packBuffers = net.irisshaders.iris.Iris.getCurrentPack()
                .map(pack -> pack.getBufferObjects().keySet().intStream().max().orElse(-1) + 1)
                .orElse(0);
            max = net.irisshaders.iris.gl.sampler.SamplerLimits.get().getMaxShaderStorageUnits();
            final int candidate = COLORWHEEL_BINDINGS + packBuffers;
            binding = candidate < max ? candidate : -1;
        } catch (final Throwable t) {
            LOGGER.warn("[IPColorwheelClip] cannot place the crossing-cut buffer: {}", t.toString());
        }
        if (binding != shipClipBinding || !bindingLogged) {
            bindingLogged = true;
            if (binding >= 0) {
                LOGGER.info("[IPColorwheelClip] crossing cut planes of Colorwheel's indirect programs at storage buffer binding {}", binding);
            } else {
                LOGGER.warn("[IPColorwheelClip] no free storage buffer binding (limit {}): Colorwheel's indirect backend draws crossing carriages and straddling ships uncut", max);
            }
        }
        shipClipBinding = binding;
        return binding;
    }
}
