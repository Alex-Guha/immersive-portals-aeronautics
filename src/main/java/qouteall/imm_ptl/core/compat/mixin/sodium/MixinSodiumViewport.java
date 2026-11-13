/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0), with changes from
 * Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code and ItsSoBread's changes stay under the
 * Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin.sodium;

import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.render.viewport.frustum.Frustum;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
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
    
    // Immersive Aeronautics: half the box Sodium's SimpleFrustum tests a section by (a 16^3 section plus 1.125 for
    // models sticking out, baked into its planes; javap of 0.8.13)
    @Unique
    private static final float SECTION_HALF_SIZE = 9.125f;
    
    // Immersive Aeronautics: the inner culling on Sodium's section tests too, which OcclusionCuller uses
    // (isWithinFrustum, isWithinNearbySectionFrustum); isBoxVisibleDirect above is only reached by its tree collector,
    // with the camera outside the loaded sections (FrustumCuller.canCullSection)
    @Redirect(
        method = "isBoxVisible",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/viewport/frustum/Frustum;testSection(FFF)Z"
        )
    )
    private boolean ip_redirectTestSection(Frustum instance, float x, float y, float z) {
        if (!instance.testSection(x, y, z)) {
            return false;
        }
        return SodiumInterface.frustumCuller == null ||
            !SodiumInterface.frustumCuller.canCullSection(x, y, z, SECTION_HALF_SIZE);
    }
    
    @Redirect(
        method = "isBoxVisibleLooser",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/viewport/frustum/Frustum;testSectionExpanded(FFFF)Z"
        )
    )
    private boolean ip_redirectTestSectionExpanded(Frustum instance, float x, float y, float z, float margin) {
        if (!instance.testSectionExpanded(x, y, z, margin)) {
            return false;
        }
        return SodiumInterface.frustumCuller == null ||
            !SodiumInterface.frustumCuller.canCullSection(x, y, z, SECTION_HALF_SIZE + margin);
    }
}
