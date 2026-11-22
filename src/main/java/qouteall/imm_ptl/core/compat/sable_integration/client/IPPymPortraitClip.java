/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration.client;

import com.mojang.blaze3d.systems.RenderSystem;
import dev.ryanhcode.sable.sublevel.ClientSubLevel;
import net.neoforged.fml.ModList;
import org.jetbrains.annotations.Nullable;
import org.joml.Matrix4f;
import org.joml.Quaternionf;
import org.joml.Vector4f;

import java.lang.reflect.Method;

/**
 * Pym (Create: Pocket Sized) draws a hat, a case's contents or a hat stand build as a portrait: Sable's vanilla ship
 * renderer with a model-view that places the build on a player's head or in a hand ({@code Portrait.draw}), not the
 * camera's. IP's clip plane is camera-relative and tested on the vertex position before the model-view, so in a portal
 * view it has to be moved into the portrait's vertex space through that whole matrix, or the build is clipped by a plane
 * in the wrong place (a hat drawn on a player seen through a portal was not drawn at all).
 */
public final class IPPymPortraitClip {
    private static boolean resolved;
    @Nullable
    private static Method drawing;

    private IPPymPortraitClip() {
    }

    /** Whether Pym is drawing a portrait now ({@code Portrait.drawing}); false without Pym 0.7. */
    public static boolean isDrawing() {
        if (!resolved) {
            resolved = true;
            if (ModList.get().isLoaded("pym")) {
                try {
                    drawing = Class.forName("com.misterblusky9.pym.api.client.Portrait", true, IPPymPortraitClip.class.getClassLoader())
                        .getMethod("drawing");
                } catch (final ReflectiveOperationException | LinkageError e) {
                    drawing = null;
                }
            }
        }
        if (drawing == null) {
            return false;
        }
        try {
            return (boolean) drawing.invoke(null);
        } catch (final ReflectiveOperationException e) {
            return false;
        }
    }

    /**
     * IP's camera-relative clip equation in the vertex space of a portrait drawn through Sable's
     * {@code VanillaChunkedSubLevelRenderData.renderChunkedSubLevel} with {@code modelView} (the camera's model-view times
     * the portrait's placement; Sable adds the ship's render orientation, and no translation with the camera at 0).
     */
    public static double[] equation(final double[] cameraRelative, final Matrix4f modelView, final ClientSubLevel ship) {
        final Matrix4f toCamera = new Matrix4f(RenderSystem.getModelViewMatrix()).invert()
            .mul(modelView)
            .rotate(new Quaternionf(ship.renderPose().orientation()));
        final Vector4f equation = new Vector4f((float) cameraRelative[0], (float) cameraRelative[1], (float) cameraRelative[2],
            (float) cameraRelative[3]);
        toCamera.transpose().transform(equation);
        return new double[]{equation.x(), equation.y(), equation.z(), equation.w()};
    }
}
