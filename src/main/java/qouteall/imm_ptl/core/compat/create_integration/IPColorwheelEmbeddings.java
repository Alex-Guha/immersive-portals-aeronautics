/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import dev.djefrey.colorwheel.engine.ClrwlEngine;
import dev.djefrey.colorwheel.engine.embed.EmbeddedEnvironment;
import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.compat.mixin_create.IPColorwheelEmbeddedAccessor;

/** {@link IPFlywheelEmbeddings} for Colorwheel's embeddings; loaded only when Colorwheel is installed. */
final class IPColorwheelEmbeddings {
    private IPColorwheelEmbeddings() {
    }

    static boolean is(final VisualEmbedding embedding) {
        return embedding instanceof EmbeddedEnvironment;
    }

    /** Colorwheel's translucent and shadow phases, which it draws through {@code RenderDispatcher.afterEntities} too. */
    static boolean isTranslucentOrShadowContext(final dev.engine_room.flywheel.api.backend.RenderContext context) {
        return context instanceof dev.djefrey.colorwheel.engine.TranslucentRenderContext
            || context instanceof dev.djefrey.colorwheel.engine.ShadowRenderContext;
    }

    static boolean isEngine(final Object engine) {
        return engine instanceof ClrwlEngine;
    }

    static boolean isDeleted(final VisualEmbedding embedding) {
        return !(embedding instanceof final EmbeddedEnvironment environment) || environment.isDeleted();
    }

    static int matrixIndex(final VisualEmbedding embedding) {
        return embedding instanceof final EmbeddedEnvironment environment ? environment.matrixIndex : -1;
    }

    @Nullable
    static Object engine(final VisualEmbedding embedding) {
        return embedding instanceof final EmbeddedEnvironment environment
            ? ((IPColorwheelEmbeddedAccessor) environment).ip_getEngine() : null;
    }

    @Nullable
    static VisualEmbedding parent(final VisualEmbedding embedding) {
        return embedding instanceof final EmbeddedEnvironment environment
            ? ((IPColorwheelEmbeddedAccessor) environment).ip_getParent() : null;
    }
}
