/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * Mutable accessor for {@code Minecraft.levelRenderer}.
 * Used only by {@code IPSablePortalCullImpl} to temporarily swap the active
 * {@link LevelRenderer} so Sable's sub-level render data is rebuilt against
 * the portal-side world's {@code SectionRenderDispatcher}.
 */
@Mixin(Minecraft.class)
public interface IPMinecraftLevelRendererAccessor {
    @Mutable
    @Accessor("levelRenderer")
    void ip_setLevelRenderer(LevelRenderer levelRenderer);
}
