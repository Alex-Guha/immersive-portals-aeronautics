/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.peripheral.mixin.client.alternate_dimension;

import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.FogRenderer;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.render.IPPortalBiomeSample;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;
import qouteall.imm_ptl.peripheral.alternate_dimension.AlternateDimensions;

@Mixin(FogRenderer.class)
public class MixinFogRenderer_A_CVB {
    //avoid alternate dimension dark when seeing from overworld
    @Redirect(
        method = "Lnet/minecraft/client/renderer/FogRenderer;setupColor(Lnet/minecraft/client/Camera;FLnet/minecraft/client/multiplayer/ClientLevel;IF)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/Camera;getPosition()Lnet/minecraft/world/phys/Vec3;"
        )
    )
    private static Vec3 redirectCameraGetPos(Camera camera) {
        ClientLevel world = Minecraft.getInstance().level;
        // Immersive Aeronautics: a portal view's fog colour is the far portal's biome (IPPortalBiomeSample); this is
        // the one redirect of these calls, so it is done here
        Vec3 pos = PortalRendering.isRendering() ? IPPortalBiomeSample.samplePos() : camera.getPosition();
        // Immersive Aeronautics: back with the alternate dimensions
        if (world != null && AlternateDimensions.isAlternateDimension(world)) {
            return new Vec3(
                pos.x,
                Math.max(32.0, pos.y),
                pos.z
            );
        }
        else {
            return pos;
        }
    }
}
