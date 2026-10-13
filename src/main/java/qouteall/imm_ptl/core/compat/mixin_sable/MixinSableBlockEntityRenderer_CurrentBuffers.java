/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderBuffers;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Makes Sable's ship block-entity renderer draw into the render buffers of the pass being rendered.
 * <p>
 * {@code VanillaSubLevelBlockEntityRenderer} keeps the {@link RenderBuffers} it was given when its
 * {@code LevelRenderer} was built, which is always the main set. IP's portal pass swaps in a secondary set
 * ({@code IPGlobal.useSecondaryEntityVertexConsumer}) and flushes only that one, so block entities on ships
 * seen through a portal were written into the main buffers and drawn later by the main view: missing from
 * the portal view, and drawn with the main view's state over the player's own dimension (the block-entity
 * "ghosts" of a ship in the other dimension). Before each draw the field is pointed at
 * {@code Minecraft.renderBuffers()}, which IP swaps for the duration of a portal pass and which is the main
 * set otherwise.
 */
@Mixin(
    targets = "dev.ryanhcode.sable.mixinhelpers.sublevel_render.vanilla.VanillaSubLevelBlockEntityRenderer",
    remap = false
)
public class MixinSableBlockEntityRenderer_CurrentBuffers {
    @Shadow
    @Final
    @Mutable
    private RenderBuffers renderBuffers;

    @Inject(
        method = "renderSingleBE",
        at = @At("HEAD"),
        require = 0
    )
    private void ip_useCurrentRenderBuffers(CallbackInfo ci) {
        RenderBuffers current = Minecraft.getInstance().renderBuffers();
        if (current != null && current != this.renderBuffers) {
            this.renderBuffers = current;
        }
    }
}
