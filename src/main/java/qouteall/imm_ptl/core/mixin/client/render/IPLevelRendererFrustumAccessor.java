/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.client.renderer.culling.Frustum;
import org.joml.Vector3d;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Vanilla's frustum capture fields (F3+U), for {@code IPFrustumCapture}. */
@Mixin(LevelRenderer.class)
public interface IPLevelRendererFrustumAccessor {
    @Accessor("capturedFrustum")
    Frustum ip_getCapturedFrustum();

    @Accessor("capturedFrustum")
    void ip_setCapturedFrustum(Frustum frustum);

    @Accessor("frustumPos")
    Vector3d ip_getFrustumPos();

    @Accessor("frustumPoints")
    Vector4f[] ip_getFrustumPoints();
}
