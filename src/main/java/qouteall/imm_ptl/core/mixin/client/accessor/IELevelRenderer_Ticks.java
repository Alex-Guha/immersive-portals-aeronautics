/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.accessor;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** A level renderer's tick counter ({@code ClientWorldLoader.alignRendererTicks}). */
@Mixin(LevelRenderer.class)
public interface IELevelRenderer_Ticks {
    @Accessor("ticks")
    int ip_getTicks();

    @Accessor("ticks")
    void ip_setTicks(int ticks);
}
