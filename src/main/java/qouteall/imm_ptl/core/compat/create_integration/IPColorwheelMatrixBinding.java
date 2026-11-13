/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Colorwheel's indirect backend places each instance's bounding sphere on the GPU once per frame
 * ({@code ClrwlIndirectDrawManager.prepareFrame} -> {@code ClrwlIndirectCullingGroup.dispatchTransform}); for an
 * embedded instance (every Create contraption, train carriage, windmill) the transform shader reads the embedding's
 * matrix from the SSBO at binding 9. {@code prepareFrame} never binds its own engine's matrix buffer there: only its
 * draws do ({@code renderSolid}/{@code renderTranslucent}), so the transform reads whatever matrix buffer was bound last
 * (Colorwheel 1.3.2, decompiled). With one world drawn per frame that is nearly always the engine's own. IP draws
 * several worlds' engines per frame (the player's world and each portal view's), so a world's contraptions got their
 * spheres from another world's matrices and were frustum-culled where they really were: windmills and trains vanished
 * through portals and around crossings. Flywheel's own indirect backend binds its matrix buffer before its cull.
 * <p>
 * Called right after {@code prepareFrame} binds its uniforms, before the transform dispatches.
 */
public final class IPColorwheelMatrixBinding {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static Field matrixBufferField;
    private static Method bindMethod;
    private static boolean failed;
    private static boolean logged;

    private IPColorwheelMatrixBinding() {
    }

    public static void bindOwnMatrices(final Object drawManager) {
        if (failed) {
            return;
        }
        try {
            if (matrixBufferField == null) {
                matrixBufferField = drawManager.getClass().getDeclaredField("matrixBuffer");
                matrixBufferField.setAccessible(true);
            }
            final Object matrixBuffer = matrixBufferField.get(drawManager);
            if (matrixBuffer == null) {
                return;
            }
            if (bindMethod == null) {
                bindMethod = matrixBuffer.getClass().getMethod("bind");
            }
            bindMethod.invoke(matrixBuffer);
            if (!logged) {
                logged = true;
                LOGGER.info("[IPColorwheel] binding each world's own matrix buffer for Colorwheel's bounding-sphere pass");
            }
        } catch (final Throwable t) {
            failed = true;
            LOGGER.warn("[IPColorwheel] cannot bind Colorwheel's matrix buffer for its bounding-sphere pass ({}): contraptions may vanish in portal views with the indirect backend", t.toString());
        }
    }
}
