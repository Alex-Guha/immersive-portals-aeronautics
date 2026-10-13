/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Reads the {@link SectionRenderDispatcher} that a Sable {@code VanillaChunkedSubLevelRenderData}
 * was built against. Used by {@code IPSablePortalCullImpl} to detect ship render data bound to
 * another world's dispatcher.
 */
@Mixin(
    targets = "dev.ryanhcode.sable.sublevel.render.vanilla.VanillaChunkedSubLevelRenderData",
    remap = false
)
public interface IPVanillaChunkedSubLevelRenderDataAccessor {
    @Accessor("sectionRenderDispatcher")
    SectionRenderDispatcher ip_getSectionRenderDispatcher();
}
