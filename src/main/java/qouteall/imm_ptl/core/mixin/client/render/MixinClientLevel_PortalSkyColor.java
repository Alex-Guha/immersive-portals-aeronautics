/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.phys.Vec3;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import qouteall.imm_ptl.core.render.IPPortalBiomeSample;
import qouteall.imm_ptl.core.render.context_management.PortalRendering;

/**
 * A portal view's sky colour is the far portal's biome, like its fog ({@link IPPortalBiomeSample}). Every caller in a
 * portal pass means the view's sky: vanilla's sky and fog colour, DH's sky-coloured fog, Iris's {@code skyColor}
 * (which passes the player's own coordinates, in the far world).
 */
@Mixin(ClientLevel.class)
public class MixinClientLevel_PortalSkyColor {
    @ModifyVariable(method = "getSkyColor", at = @At("HEAD"), argsOnly = true)
    private Vec3 ip_sampleAtFarPortal(Vec3 pos) {
        if (PortalRendering.isRendering()) {
            return IPPortalBiomeSample.samplePos();
        }
        return pos;
    }
}
