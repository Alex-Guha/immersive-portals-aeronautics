/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0), with changes from
 * Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code and ItsSoBread's changes stay under the
 * Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.sodium_compatibility.SodiumInterface;

@Mixin(value = Viewport.class, remap = false)
public class MixinSodiumViewport {
    @Redirect(
        method = "isBoxVisibleDirect",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/viewport/frustum/Frustum;testAab(FFFFFF)Z"
        )
    )
    private boolean redirectTestAab(
        Frustum instance,
        float minX, float minY, float minZ, float maxX, float maxY, float maxZ
    ) {
        boolean inFrustum = instance.testAab(
            minX, minY, minZ, maxX, maxY, maxZ
        );
        
        if (inFrustum) {
            if (SodiumInterface.frustumCuller != null) {
                boolean canDetermineInvisible =
                    SodiumInterface.frustumCuller.canDetermineInvisibleWithCameraCoord(
                        minX, minY, minZ, maxX, maxY, maxZ
                    );
                return !canDetermineInvisible;
            }
        }
        
        return inFrustum;
    }
}
