/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 * The approach (a second Flywheel clip distance per embedded environment) follows IPSable 0.5.0's
 * IplFlywheelCompilationMixin and IplFlywheelEmbeddedClipMixin by r2smith141 (Apache License 2.0).
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import dev.engine_room.flywheel.api.backend.RenderContext;
import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.backend.gl.shader.GlProgram;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.minecraft.core.Vec3i;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;
import org.slf4j.Logger;
import qouteall.imm_ptl.core.compat.mixin_create.IPEmbeddedEnvironmentAccessor;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

/**
 * Cuts Flywheel-drawn parts of straddling Sable ships (Create cogs, shafts, propellers) at the portal plane, like
 * their terrain (docs/lessons/straddle-rendering.md). Sable gives each ship one Flywheel embedding (an
 * {@link EmbeddedEnvironment} whose render origin is in the ship's plot); every Flywheel vertex program writes
 * {@code gl_ClipDistance[1]} from a per-environment plane {@code ipa_shipClip}:
 * <ul>
 *     <li>indirect backend: all environments draw in one multi-draw, so the planes sit in a shader storage buffer at
 *     binding {@value #BUFFER_BINDING}, indexed by the environment's matrix index like Flywheel's own matrices;</li>
 *     <li>instancing backend: a uniform, set whenever an environment is bound for a draw.</li>
 * </ul>
 * Planes are computed per Flywheel pass ({@code afterEntities}, also inside IP portal passes) and
 * {@code GL_CLIP_DISTANCE1} is enabled only for that pass and only when a ship there is cut. Planes are relative to
 * the engine's render origin, the space of {@code flw_vertexPos}. The far half is drawn by the ship's own dimension's
 * engine a second time ({@link IPFlywheelFarHalf}); in that draw the ship's environments keep the part past the plane
 * and everything else is rejected.
 * <p>
 * Create carriages crossing a portal are cut the same way, by their own and their bogeys' embeddings
 * ({@link IPCreateCarriageCrossingClient}); that needs no Sable.
 */
public final class IPFlywheelStraddleClip {
    private static final Logger LOGGER = LogUtils.getLogger();
    public static final String UNIFORM = "ipa_ShipClipEquation";
    public static final int BUFFER_BINDING = 8;
    private static final float[] NEUTRAL = {0f, 0f, 0f, 1f};
    static final float[] REJECT = {0f, 0f, 0f, -1f};
    private static final String INDIRECT_UNPACK = "_flw_unpackMatrices(_flw_matrices[draw.matrixIndex]";
    private static final String INSTANCING_MATRIX = "_flw_modelMatrix = _flw_modelMatrixUniform;";
    private static final String MAIN_START = "void main() {";

    private static final boolean SABLE = ModList.get().isLoaded("sable");
    /** Embedded environments (weak: Flywheel deletes them). Flywheel's worker threads create them. */
    private static final Set<EmbeddedEnvironment> ENVIRONMENTS = Collections.synchronizedSet(Collections.newSetFromMap(new WeakHashMap<>()));
    /** The planes of the current pass, by environment. */
    private static final Map<EmbeddedEnvironment, float[]> PASS_PLANES = new IdentityHashMap<>();
    private static int buffer;
    private static boolean enabled;
    /**
     * The far-half draw in progress ({@link IPFlywheelFarHalf}): only the environments the far half selects are drawn,
     * each cut by its plane; everything else of the engine is rejected.
     */
    @Nullable
    private static FarHalf farHalf;

    /** What a far-half draw keeps: a straddling ship's parts, or a Create carriage's. */
    interface FarHalf {
        /**
         * @return the cut of an environment in this far-half draw (keeping the part past the portal plane), relative to
         * the engine's render origin, or null if the environment is not part of the far half (rejected)
         */
        float @Nullable [] plane(net.minecraft.client.multiplayer.ClientLevel level, EmbeddedEnvironment environment,
                                 EmbeddedEnvironment root, Vec3i renderOrigin);
    }

    /**
     * @param ship    the ship whose parts form the far half
     * @param session its straddle session (the plane is placed against the drawing engine's render origin per pass)
     */
    record ShipFarHalf(dev.ryanhcode.sable.sublevel.ClientSubLevel ship,
                       qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleClientSessions.Session session) implements FarHalf {
        @Override
        public float @Nullable [] plane(final net.minecraft.client.multiplayer.ClientLevel level, final EmbeddedEnvironment environment,
                                        final EmbeddedEnvironment root, final Vec3i renderOrigin) {
            return isOfShip(level, environment, ship)
                ? qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender.flywheelFarHalfPlane(session, renderOrigin)
                : null;
        }
    }
    private static boolean logged;
    private static boolean failureLogged;

    private IPFlywheelStraddleClip() {
    }

    /**
     * @return the GLSL declarations to put before {@code _flw_main} of an assembled vertex program
     */
    static String declarations(final String source) {
        String decl = "uniform vec4 " + UNIFORM + ";\nvec4 ipa_shipClip = vec4(0.0, 0.0, 0.0, 1.0);\n";
        if (source.contains(INDIRECT_UNPACK)) {
            decl += "#ifdef FLW_EMBEDDED\nlayout(std430, binding = " + BUFFER_BINDING + ") restrict readonly buffer IpaShipClipBuffer {\n"
                    + "    vec4 ipa_shipClips[];\n};\n#endif\n";
        }
        return decl;
    }

    /**
     * Makes {@code main} of an assembled vertex program pick its environment's plane (both are inside
     * {@code #ifdef FLW_EMBEDDED} there). Every program first takes the uniform, which world-level (not embedded) draws
     * keep: the far-half draw sets it to reject them.
     */
    static String patchMain(String source) {
        final int mainStart = source.indexOf(MAIN_START);
        if (mainStart >= 0) {
            final int end = mainStart + MAIN_START.length();
            source = source.substring(0, end) + "\n    ipa_shipClip = " + UNIFORM + ";" + source.substring(end);
        }
        final int indirect = source.indexOf(INDIRECT_UNPACK);
        if (indirect >= 0) {
            final int end = source.indexOf(';', indirect);
            return source.substring(0, end + 1) + "\n    ipa_shipClip = ipa_shipClips[draw.matrixIndex];" + source.substring(end + 1);
        }
        final int instancing = source.indexOf(INSTANCING_MATRIX);
        if (instancing >= 0) {
            final int end = instancing + INSTANCING_MATRIX.length();
            return source.substring(0, end) + "\n    ipa_shipClip = " + UNIFORM + ";" + source.substring(end);
        }
        if (!failureLogged) {
            failureLogged = true;
            LOGGER.warn("[IPStraddle] a Flywheel vertex shader has neither \"{}\" nor \"{}\": Flywheel changed, straddling ships' parts are not cut",
                    INDIRECT_UNPACK, INSTANCING_MATRIX);
        }
        return source;
    }

    public static void onFlush(final EmbeddedEnvironment environment) {
        ENVIRONMENTS.add(environment);
    }

    public static void onSetupDraw(final EmbeddedEnvironment environment, final GlProgram program) {
        ENVIRONMENTS.add(environment);
        if (enabled) {
            upload(program, PASS_PLANES.getOrDefault(environment, farHalf != null ? REJECT : NEUTRAL));
        }
    }

    public static void onGlobalSetupDraw(final GlProgram program) {
        if (enabled) {
            upload(program, farHalf != null ? REJECT : NEUTRAL);
        }
    }

    /**
     * @return whether a far-half draw is in progress (world-level draws of its engine are rejected)
     */
    public static boolean isFarHalfPass() {
        return farHalf != null;
    }

    static void beginFarHalf(final FarHalf far) {
        farHalf = far;
    }

    static void endFarHalf() {
        farHalf = null;
    }

    /**
     * Start of a Flywheel pass (one per level renderer pass): computes the planes of the cut ships' environments of
     * this level's engine, and if there are any, uploads and binds them and enables the clip distance.
     */
    public static void beginPass(final RenderContext context) {
        PASS_PLANES.clear();
        enabled = false;
        IPCreateCrossingTrace.beginPass(context.level(), farHalf != null);
        try {
            final VisualizationManagerImpl manager = VisualizationManagerImpl.get(context.level());
            if (manager == null) {
                return;
            }
            final EngineImpl engine = manager.getEngineImpl();
            final Vec3i renderOrigin = engine.renderOrigin();
            int maxIndex = -1;
            final FarHalf far = farHalf;
            final List<EmbeddedEnvironment> environments;
            synchronized (ENVIRONMENTS) {
                environments = List.copyOf(ENVIRONMENTS);
            }
            for (final EmbeddedEnvironment environment : environments) {
                final IPEmbeddedEnvironmentAccessor access = (IPEmbeddedEnvironmentAccessor) environment;
                if (environment.isDeleted() || access.ip_getEngine() != engine) {
                    continue;
                }
                maxIndex = Math.max(maxIndex, environment.matrixIndex);
                final EmbeddedEnvironment root = root(environment);
                float[] plane;
                if (far != null) {
                    plane = far.plane(context.level(), environment, root, renderOrigin);
                } else {
                    // Create carriages crossing a portal, then straddling ships
                    plane = IPCreateCarriageCrossingClient.flywheelPlane(environment, root, renderOrigin);
                    if (plane == null && SABLE) {
                        plane = qouteall.imm_ptl.core.compat.sable_straddle.client.IPStraddleRender
                            .flywheelPlane(context.level(), root.renderOrigin(), renderOrigin);
                    }
                }
                if (plane != null) {
                    PASS_PLANES.put(environment, plane);
                }
            }
            if (PASS_PLANES.isEmpty() && far == null) {
                return;
            }

            // in a far-half draw every environment without a plane (not the ship's) is rejected
            final float[] fill = far != null ? REJECT : NEUTRAL;
            final float[] data = new float[Math.max(1, maxIndex + 1) * 4];
            for (int i = 0; i < data.length; i += 4) {
                System.arraycopy(fill, 0, data, i, 4);
            }
            for (final Map.Entry<EmbeddedEnvironment, float[]> entry : PASS_PLANES.entrySet()) {
                final int index = entry.getKey().matrixIndex;
                if (index >= 0 && index <= maxIndex) {
                    System.arraycopy(entry.getValue(), 0, data, index * 4, 4);
                }
            }
            if (buffer == 0) {
                buffer = GL15.glGenBuffers();
            }
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, buffer);
            GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, data, GL15.GL_STREAM_DRAW);
            GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
            GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, BUFFER_BINDING, buffer);
            GL11.glEnable(GL30.GL_CLIP_DISTANCE1);
            enabled = true;
            if (!logged) {
                logged = true;
                LOGGER.debug("[IPStraddle] cutting Flywheel parts of straddling ships");
            }
        } catch (final Throwable t) {
            PASS_PLANES.clear();
            if (!failureLogged) {
                failureLogged = true;
                LOGGER.warn("[IPStraddle] could not cut Flywheel parts of straddling ships: {}", t.toString());
            }
        }
    }

    public static void endPass() {
        if (enabled) {
            enabled = false;
            GL11.glDisable(GL30.GL_CLIP_DISTANCE1);
        }
        PASS_PLANES.clear();
    }

    static boolean isOfShip(final net.minecraft.client.multiplayer.ClientLevel level, final EmbeddedEnvironment environment,
                                    final dev.ryanhcode.sable.sublevel.ClientSubLevel ship) {
        final dev.ryanhcode.sable.api.sublevel.SubLevelContainer container =
                dev.ryanhcode.sable.api.sublevel.SubLevelContainer.getContainer(level);
        if (container == null) {
            return false;
        }
        final dev.ryanhcode.sable.sublevel.plot.LevelPlot plot =
                container.getPlot(new net.minecraft.world.level.ChunkPos(new net.minecraft.core.BlockPos(rootOrigin(environment))));
        return plot != null && plot.getSubLevel() == ship;
    }

    private static Vec3i rootOrigin(final EmbeddedEnvironment environment) {
        return root(environment).renderOrigin();
    }

    private static EmbeddedEnvironment root(final EmbeddedEnvironment environment) {
        EmbeddedEnvironment root = environment;
        for (int depth = 0; depth < 16; depth++) {
            final @Nullable EmbeddedEnvironment parent = ((IPEmbeddedEnvironmentAccessor) root).ip_getParent();
            if (parent == null) {
                break;
            }
            root = parent;
        }
        return root;
    }

    private static void upload(final GlProgram program, final float[] plane) {
        final int location = program.getUniformLocation(UNIFORM);
        if (location >= 0) {
            GL20.glUniform4f(location, plane[0], plane[1], plane[2], plane[3]);
        }
    }
}
