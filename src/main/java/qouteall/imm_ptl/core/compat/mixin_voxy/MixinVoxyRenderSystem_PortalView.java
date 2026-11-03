/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import me.cortex.voxy.client.core.AbstractRenderPipeline;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.rendering.RenderDistanceTracker;
import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.compat.iris_compatibility.IrisInterface;
import qouteall.imm_ptl.core.compat.voxy_integration.IPVoxyIrisPipelines;
import qouteall.imm_ptl.core.compat.voxy_integration.IPVoxyPortalView;
import qouteall.imm_ptl.core.portal.Portal;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.RenderStates;

import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Voxy (through Roxy, or Neo Voxy, a NeoForge port with the same classes) draws its LODs from Sodium's cutout pass
 * with the render system of {@code Minecraft.levelRenderer}, which IP swaps to the viewed dimension's renderer in a
 * portal pass, so a portal pass runs the viewed dimension's Voxy pipeline from the portal camera
 * ({@link IPVoxyPortalView}).
 * <p>
 * Voxy's occlusion is temporal (each viewport's HiZ comes from its own last frame), so every portal gets a viewport
 * of its own, made by Voxy's own factory (as for Vivecraft's eyes) and keyed by the portal and its nesting depth;
 * the default viewport stays the player's view. One left unused for {@link #IP_VIEWPORT_IDLE_FRAMES} frames is
 * freed. Voxy's distance tracker recentres on the camera of every draw; only a render system's first draw of a frame
 * ({@code renderOpaque}; Neo Voxy with a pack sets a viewport up twice in a pass) moves it (the main pass comes first,
 * as portals are drawn before its translucent layer), so a portal into the player's own dimension or a second portal
 * into one dimension leaves the centre where it was (Voxy's distance of thousands of blocks still covers the far side
 * unless the portal leads farther than that). With a shader pack Roxy sets the viewport up at {@code renderLevel}'s
 * start (Voxy's original {@code setupViewport}, from Iris's captured matrices) and fetches it with
 * {@code getViewport()}; the portal's viewport is chosen in both {@code setupViewport}s and returned by
 * {@code getViewport()} for the rest of the pass. Skipping Voxy in a shader-pack portal pass left the pack compositing
 * Voxy's depth from that dimension's last draw: a frozen image of LODs on screen. Neo Voxy has one
 * {@code setupViewport} (Iris's captured matrices or Sodium's) and recentres the tracker in {@code renderOpaqueNow}.
 * Voxy draws nothing in Iris's shadow pass ({@code getViewport()} is null there), so none of this runs in it.
 * <p>
 * With Iris installed, every draw first checks that the render system's pipeline matches its dimension's Iris
 * pipeline ({@link IPVoxyIrisPipelines}).
 */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public class MixinVoxyRenderSystem_PortalView {
    @Unique
    private static final int IP_VIEWPORT_IDLE_FRAMES = 600;

    @Shadow
    @Final
    private ViewportSelector<?> viewportSelector;
    @Shadow
    @Final
    private AbstractRenderPipeline pipeline;

    @Unique
    private int ip_drawnFrame = Integer.MIN_VALUE;
    @Unique
    private boolean ip_portalPassApproved;
    @Unique
    private boolean ip_keepTrackerCentre;
    @Unique
    private Viewport<?> ip_passViewport;
    @Unique
    private final Map<Object, Viewport<?>> ip_portalViewports = new HashMap<>();
    @Unique
    private final Map<Object, Integer> ip_portalViewportFrames = new HashMap<>();

    @Inject(
        method = {
            "setupViewport(Lnet/caffeinemc/mods/sodium/client/render/chunk/ChunkRenderMatrices;DDD)Lme/cortex/voxy/client/core/rendering/Viewport;",
            "setupViewport(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;Lnet/rasanovum/roxy/fog/RoxyFogParameters;DDD)Lme/cortex/voxy/client/core/rendering/Viewport;",
            "setupViewport(Lorg/joml/Matrix4fc;Lorg/joml/Matrix4fc;DDD)Lme/cortex/voxy/client/core/rendering/Viewport;"
        },
        at = @At("HEAD"), cancellable = true, require = 0
    )
    private void ip_choosePortalViewport(CallbackInfoReturnable<Viewport<?>> cir) {
        if (IrisInterface.invoker.isRenderingShadowMap()) {
            return;
        }
        ip_passViewport = null;
        if (!PortalRendering.isRendering()) {
            return;
        }
        if (!IPVoxyPortalView.ENABLED) {
            cir.setReturnValue(null);
            return;
        }
        ip_passViewport = ip_viewportFor(PortalRendering.getRenderingPortal(), RenderStates.frameIndex);
        ip_portalPassApproved = true;
    }

    /**
     * Both {@code setupViewport}s set up {@code getViewport()}'s viewport, and with a shader pack Roxy's draw fetches
     * it again with {@code getViewport()} (set up at {@code renderLevel}'s start from Iris's captured matrices).
     */
    @Inject(method = "getViewport", at = @At("HEAD"), cancellable = true, require = 0)
    private void ip_usePortalViewport(CallbackInfoReturnable<Viewport<?>> cir) {
        Viewport<?> viewport = ip_passViewport;
        if (viewport != null && PortalRendering.isRendering() && !IrisInterface.invoker.isRenderingShadowMap()) {
            cir.setReturnValue(viewport);
        }
    }

    @Inject(method = "renderOpaque", at = @At("HEAD"), cancellable = true, require = 0)
    private void ip_beginPortalPass(CallbackInfo ci) {
        if (IrisInterface.invoker.isRenderingShadowMap()) {
            return;
        }
        int frame = RenderStates.frameIndex;
        boolean firstDrawThisFrame = ip_drawnFrame != frame;
        ip_drawnFrame = frame;
        if (firstDrawThisFrame) {
            ip_freeIdleViewports(frame);
        }
        ip_keepTrackerCentre = !firstDrawThisFrame;
        boolean approved = ip_portalPassApproved;
        ip_portalPassApproved = false;
        if (IrisInterface.invoker.isIrisPresent() && !IPVoxyIrisPipelines.checkBeforeDraw(pipeline)) {
            ci.cancel();
            return;
        }
        if (!PortalRendering.isRendering()) {
            return;
        }
        if (!approved) {
            ci.cancel();
            return;
        }
        // with a shader pack IP renders a portal's world full screen and copies it into the portal afterwards
        if (!IrisInterface.invoker.isShaders() && !IPVoxyPortalView.beginPortalPass()) {
            ci.cancel();
        }
    }

    @WrapOperation(
        method = {"renderOpaque", "renderOpaqueNow"},
        at = @At(
            value = "INVOKE",
            target = "Lme/cortex/voxy/client/core/rendering/RenderDistanceTracker;setCenterAndProcess(DD)Z"
        ),
        require = 0
    )
    private boolean ip_keepTrackerCentre(
        RenderDistanceTracker tracker, double x, double z, Operation<Boolean> original
    ) {
        if (PortalRendering.isRendering() && ip_keepTrackerCentre) {
            return false;
        }
        return original.call(tracker, x, z);
    }

    @Inject(method = "renderOpaque", at = @At("RETURN"), require = 0)
    private void ip_endPortalPass(CallbackInfo ci) {
        if (IrisInterface.invoker.isRenderingShadowMap()) {
            return;
        }
        IPVoxyPortalView.endPortalPass();
        ip_passViewport = null;
    }

    @Inject(method = "shutdown", at = @At("HEAD"), require = 0)
    private void ip_freePortalViewports(CallbackInfo ci) {
        ip_portalViewports.values().forEach(Viewport::delete);
        ip_portalViewports.clear();
        ip_portalViewportFrames.clear();
        ip_passViewport = null;
    }

    @Unique
    private Viewport<?> ip_viewportFor(Portal portal, int frame) {
        Object key = List.of(portal.getUUID(), PortalRendering.getPortalLayer());
        Viewport<?> viewport = ip_portalViewports.get(key);
        if (viewport == null) {
            viewport = ((IPVoxyViewportSelectorAccessor) viewportSelector).ip_getCreator().get();
            ip_portalViewports.put(key, viewport);
        }
        ip_portalViewportFrames.put(key, frame);
        return viewport;
    }

    @Unique
    private void ip_freeIdleViewports(int frame) {
        Iterator<Map.Entry<Object, Integer>> it = ip_portalViewportFrames.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<Object, Integer> entry = it.next();
            if (frame - entry.getValue() > IP_VIEWPORT_IDLE_FRAMES) {
                Viewport<?> viewport = ip_portalViewports.remove(entry.getKey());
                if (viewport != null) {
                    viewport.delete();
                }
                it.remove();
            }
        }
    }
}
