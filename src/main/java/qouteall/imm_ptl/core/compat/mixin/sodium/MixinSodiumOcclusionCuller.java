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

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.RenderSectionVisitor;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.core.SectionPos;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.CHelper;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.IPFrustumCapture;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

@Mixin(OcclusionCuller.class)
public abstract class MixinSodiumOcclusionCuller {
    @Shadow(remap = false)
    protected abstract RenderSection getRenderSection(int x, int y, int z);
    
    @Shadow(remap = false)
    public static boolean isWithinFrustum(Viewport viewport, RenderSection section) {
        throw new RuntimeException();
    }
    
    @Unique
    private @Nullable SectionPos ip_modifiedStartPoint;
    
    @Unique
    private static boolean ip_tolerantInitialFrustumTestFail;
    
    // update the iteration start point modification value
    @SuppressWarnings("ConstantValue")
    @ModifyVariable(
        method = "findVisible", at = @At("HEAD"), argsOnly = true, remap = false
    )
    boolean modifyUseOcclusionCulling(
        boolean originalValue,
        RenderSectionVisitor visitor, Viewport viewport, float searchDistance, boolean useOcclusionCulling, int frame
    ) {
        boolean doUseOcclusionCulling = PortalRendering.shouldEnableSodiumCaveCulling();
        
        ip_modifiedStartPoint = null;
        ip_tolerantInitialFrustumTestFail = false;
        
        // Immersive Aeronautics: with a frustum captured (F3+U), from the captured camera, at the portal it sees this
        // view through (IPFrustumCapture)
        boolean capture = IPFrustumCapture.isCulling();
        Portal portal = capture ? IPFrustumCapture.rimPortal() :
            PortalRendering.isRendering() ? PortalRendering.getRenderingPortal() : null;
        if (portal != null) {
            Vec3 cameraPos = capture ? IPFrustumCapture.cullingPos() : CHelper.getCurrentCameraPos();
            ip_modifiedStartPoint = portal.getPortalShape().getModifiedVisibleSectionIterationOrigin(
                portal, cameraPos
            );
            if (ip_modifiedStartPoint != null) {
                doUseOcclusionCulling = false;
                
                RenderSection renderSection = getRenderSection(
                    ip_modifiedStartPoint.x(), ip_modifiedStartPoint.y(), ip_modifiedStartPoint.z()
                );
                if (renderSection != null && !isWithinFrustum(viewport, renderSection)) {
                    ip_tolerantInitialFrustumTestFail = true;
                }
            }
        }
        
        return doUseOcclusionCulling;
    }
    
    // apply start point modification
    @Redirect(
        method = "init",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;getChunkCoord()Lnet/minecraft/core/SectionPos;",
            remap = true
        ),
        remap = false
    )
    private SectionPos redirectGetChunkCoordInInit(Viewport instance) {
        if (ip_modifiedStartPoint != null) {
            return ip_modifiedStartPoint;
        }
        
        return instance.getChunkCoord();
    }
    
    // apply start point modification
    @Redirect(
        method = "initWithinWorld",
        at = @At(
            value = "INVOKE",
            target = "Lnet/caffeinemc/mods/sodium/client/render/viewport/Viewport;getChunkCoord()Lnet/minecraft/core/SectionPos;"
        ),
        remap = false
    )
    private SectionPos redirectGetChunkCoordInInitWithinWorld(Viewport instance) {
        if (ip_modifiedStartPoint != null) {
            return ip_modifiedStartPoint;
        }
        
        return instance.getChunkCoord();
    }
    
    // when iteration start point become a position that's outside of frustum
    // make it tolerant early frustum test failures to avoid wrongly halting iteration
    @Inject(
        method = "isWithinFrustum", at = @At("RETURN"), cancellable = true,
        remap = false
    )
    private static void onIsOutsideFrustum(
        Viewport viewport, RenderSection section,
        CallbackInfoReturnable<Boolean> cir
    ) {
        if (ip_tolerantInitialFrustumTestFail) {
            boolean withinFrustum = cir.getReturnValueZ();
            if (withinFrustum) {
                // when found a section that's in frustum, frustum test become normal
                ip_tolerantInitialFrustumTestFail = false;
            }
            cir.setReturnValue(true);
        }
    }
}
