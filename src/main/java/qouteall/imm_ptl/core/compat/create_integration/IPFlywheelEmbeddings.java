/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import dev.engine_room.flywheel.api.backend.Engine;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.mixin_create.IPEmbeddedEnvironmentAccessor;

import java.lang.reflect.Field;

/**
 * What the straddle and carriage cuts read from a Flywheel embedding, for either engine that makes them: Flywheel's
 * own ({@link EmbeddedEnvironment}) or Colorwheel's, which runs Flywheel under an Iris shader pack with an engine and
 * embeddings of its own ({@code dev.djefrey.colorwheel.engine.embed.EmbeddedEnvironment}, {@link IPColorwheelEmbeddings}).
 * Both keep a parent, a deleted flag and an index into their engine's matrix buffer, the same way.
 */
public final class IPFlywheelEmbeddings {
    static final boolean COLORWHEEL = ModList.get().isLoaded("colorwheel");
    private static Field lateInitField;
    private static Field engineField;

    private IPFlywheelEmbeddings() {
    }

    /** @return whether this is an embedding made by Flywheel's or Colorwheel's engine (one the cuts can place) */
    static boolean isEnvironment(final VisualEmbedding embedding) {
        return embedding instanceof EmbeddedEnvironment || COLORWHEEL && IPColorwheelEmbeddings.is(embedding);
    }

    static boolean isDeleted(final VisualEmbedding embedding) {
        if (embedding instanceof final EmbeddedEnvironment environment) {
            return environment.isDeleted();
        }
        return !COLORWHEEL || IPColorwheelEmbeddings.isDeleted(embedding);
    }

    static int matrixIndex(final VisualEmbedding embedding) {
        if (embedding instanceof final EmbeddedEnvironment environment) {
            return environment.matrixIndex;
        }
        return COLORWHEEL ? IPColorwheelEmbeddings.matrixIndex(embedding) : -1;
    }

    @Nullable
    static Object engine(final VisualEmbedding embedding) {
        if (embedding instanceof final EmbeddedEnvironment environment) {
            return ((IPEmbeddedEnvironmentAccessor) environment).ip_getEngine();
        }
        return COLORWHEEL ? IPColorwheelEmbeddings.engine(embedding) : null;
    }

    @Nullable
    static VisualEmbedding parent(final VisualEmbedding embedding) {
        if (embedding instanceof final EmbeddedEnvironment environment) {
            return ((IPEmbeddedEnvironmentAccessor) environment).ip_getParent();
        }
        return COLORWHEEL ? IPColorwheelEmbeddings.parent(embedding) : null;
    }

    /** @return whether Colorwheel's backend (indirect or instancing) is the one Flywheel draws with */
    static boolean isColorwheelBackend() {
        if (!COLORWHEEL) {
            return false;
        }
        try {
            final dev.engine_room.flywheel.api.backend.Backend backend = dev.engine_room.flywheel.api.backend.BackendManager.currentBackend();
            final net.minecraft.resources.ResourceLocation id = dev.engine_room.flywheel.api.backend.Backend.REGISTRY.getId(backend);
            return id != null && id.getNamespace().equals("colorwheel");
        } catch (final Throwable t) {
            return false;
        }
    }

    /** @return whether an engine is Colorwheel's (its programs take the ship cut from another buffer binding) */
    static boolean isColorwheelEngine(@Nullable final Object engine) {
        return COLORWHEEL && engine != null && IPColorwheelEmbeddings.isEngine(engine);
    }

    /**
     * @return the engine a level's visualization manager draws with, whichever backend made it
     * ({@code VisualizationManagerImpl.getEngineImpl()} answers only for Flywheel's own); null before it exists
     */
    @Nullable
    static Engine engineOf(final VisualizationManagerImpl manager) throws ReflectiveOperationException {
        if (lateInitField == null) {
            lateInitField = VisualizationManagerImpl.class.getDeclaredField("lateInit");
            lateInitField.setAccessible(true);
        }
        final Object lateInit = lateInitField.get(manager);
        if (lateInit == null) {
            return null;
        }
        if (engineField == null) {
            engineField = lateInit.getClass().getDeclaredField("engine");
            engineField.setAccessible(true);
        }
        return (Engine) engineField.get(lateInit);
    }
}
