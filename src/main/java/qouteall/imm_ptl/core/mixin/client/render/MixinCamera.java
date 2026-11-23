/*
 * Immersive Portals Revamped - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals Revamped, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.Camera;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.material.FogType;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.ducks.IECamera;
import qouteall.imm_ptl.core.render.CrossPortalEntityRenderer;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.core.render.context_management.WorldRenderInfo;

@Mixin(Camera.class)
public abstract class MixinCamera implements IECamera {
//    private static float lastClipSpaceResult = 1;
    
    @Shadow
    private Vec3 position;
    @Shadow
    private BlockGetter level;
    @Shadow
    private Entity entity;
    @Shadow
    private float eyeHeight;
    @Shadow
    private float eyeHeightOld;
    
    @Shadow
    protected abstract void setPosition(Vec3 vec3d_1);
    
    @Shadow
    public abstract Entity getEntity();
    
    @Inject(
        method = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
        at = @At("RETURN")
    )
    private void onUpdateFinished(
        BlockGetter area, Entity focusedEntity, boolean thirdPerson,
        boolean inverseView, float partialTick, CallbackInfo ci
    ) {
        Camera this_ = (Camera) (Object) this;
        WorldRenderInfo.adjustCameraPos(this_);
        // Immersive Aeronautics: remembered so the last hook below can tell whether a later one moved the camera
        ip_placedPos = WorldRenderInfo.isRendering() ? position : null;
    }

    // Immersive Aeronautics: where onUpdateFinished left the camera of a portal view
    @Unique
    private Vec3 ip_placedPos;

    /**
     * Immersive Aeronautics: camera mods (shoulder cameras, AFK and cinematic cameras) set the camera's position at the
     * end of setup too, from the player's position in its own world, on every camera. In a portal view, or the
     * cross-portal view, that moves the camera off the view's position (often into another world's coordinates), and
     * which hook runs last depends on load order. This one runs after all of them and puts the view's position back.
     */
    @Inject(
        method = "Lnet/minecraft/client/Camera;setup(Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/world/entity/Entity;ZZF)V",
        at = @At("RETURN"),
        order = 100000
    )
    private void ip_restorePortalViewPos(
        BlockGetter area, Entity focusedEntity, boolean thirdPerson,
        boolean inverseView, float partialTick, CallbackInfo ci
    ) {
        Vec3 placed = ip_placedPos;
        ip_placedPos = null;
        if (placed == null || !WorldRenderInfo.isRendering()) {
            return;
        }
        if (!position.equals(placed)) {
            WorldRenderInfo.adjustCameraPos((Camera) (Object) this);
        }
    }

    @Inject(
        method = "Lnet/minecraft/client/Camera;getFluidInCamera()Lnet/minecraft/world/level/material/FogType;",
        at = @At("HEAD"),
        cancellable = true
    )
    private void getSubmergedFluidState(CallbackInfoReturnable<FogType> cir) {
        if (PortalRendering.isRendering()) {
            cir.setReturnValue(FogType.NONE);
            cir.cancel();
        }
    }
    
//    @Inject(method = "getMaxZoom", at = @At("HEAD"), cancellable = true)
//    private void onGetMaxZoomHead(float f, CallbackInfoReturnable<Float> cir) {
//        if (PortalRendering.isRendering()) {
//            cir.setReturnValue(lastClipSpaceResult);
//            cir.cancel();
//        }
//    }
//
//    // TODO using global variable to pass may be problematic when multiple camera objects are used
//    @Inject(method = "getMaxZoom", at = @At("RETURN"), cancellable = true)
//    private void onGetMaxZoomReturn(float f, CallbackInfoReturnable<Float> cir) {
//        lastClipSpaceResult = cir.getReturnValue();
//    }
    
    // to let the player be rendered when rendering portal
    @Inject(method = "Lnet/minecraft/client/Camera;isDetached()Z", at = @At("HEAD"), cancellable = true)
    private void onIsThirdPerson(CallbackInfoReturnable<Boolean> cir) {
        if (CrossPortalEntityRenderer.shouldRenderPlayerDefault()) {
            cir.setReturnValue(true);
        }
    }
    
    @Override
    public void ip_resetState(Vec3 pos, ClientLevel currWorld) {
        setPosition(pos);
        level = currWorld;
    }
    
    @Override
    public float ip_getCameraY() {
        return eyeHeight;
    }
    
    @Override
    public float ip_getLastCameraY() {
        return eyeHeightOld;
    }
    
    @Override
    public void ip_setCameraY(float cameraY_, float lastCameraY_) {
        eyeHeight = cameraY_;
        eyeHeightOld = lastCameraY_;
    }
    
    @Override
    public void portal_setPos(Vec3 pos) {
        setPosition(pos);
    }
    
    @Override
    public void portal_setFocusedEntity(Entity arg) {
        entity = arg;
    }
}
