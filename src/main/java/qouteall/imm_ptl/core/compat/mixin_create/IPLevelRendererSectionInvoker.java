/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Marks a section for re-meshing as a block change would, with its priority (Sodium overwrites the target). */
@Mixin(LevelRenderer.class)
public interface IPLevelRendererSectionInvoker {
    @Invoker("setSectionDirty")
    void ip$setSectionDirty(int sectionX, int sectionY, int sectionZ, boolean reRenderOnMainThread);
}
