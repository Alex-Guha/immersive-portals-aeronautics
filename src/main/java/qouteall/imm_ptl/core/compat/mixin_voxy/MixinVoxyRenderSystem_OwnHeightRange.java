/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import me.cortex.voxy.client.core.VoxyRenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.voxy_integration.IPVoxyPortalView;

/**
 * A Voxy render system's vertical range (the top-level sections its {@code RenderDistanceTracker} loads) comes from
 * {@code Minecraft.level} at creation, the player's dimension, not the one it is made for. Made while the player stood
 * in the Nether (build height 0 to 256), the Overworld's render system loaded nothing below y 0: a superflat Overworld
 * showed no LODs at all, through the portal and after crossing (rebuilt after switching a shader pack off in the
 * Nether). The range now comes from the level the render system is made for ({@link IPVoxyPortalView#creationLevel}).
 */
@Mixin(value = VoxyRenderSystem.class, remap = false)
public class MixinVoxyRenderSystem_OwnHeightRange {
    @Redirect(
        method = "<init>",
        at = @At(value = "FIELD", target = "Lnet/minecraft/client/Minecraft;level:Lnet/minecraft/client/multiplayer/ClientLevel;"),
        require = 0
    )
    private ClientLevel ip_ownLevel(Minecraft minecraft) {
        ClientLevel level = IPVoxyPortalView.creationLevel();
        return level != null ? level : minecraft.level;
    }
}
