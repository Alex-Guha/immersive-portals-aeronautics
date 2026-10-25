/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_voxy;

import me.cortex.voxy.client.core.rendering.Viewport;
import me.cortex.voxy.client.core.rendering.ViewportSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.function.Supplier;

/** Voxy's viewport factory, for the viewports of portal views ({@link MixinVoxyRenderSystem_PortalView}). */
@Mixin(value = ViewportSelector.class, remap = false)
public interface IPVoxyViewportSelectorAccessor {
    @Accessor("creator")
    Supplier<? extends Viewport<?>> ip_getCreator();
}
