/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.mixin.client.render;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.SectionBufferBuilderPack;
import net.minecraft.client.renderer.chunk.SectionRenderDispatcher;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(SectionRenderDispatcher.class)
public interface IESectionRenderDispatcher {
    @Accessor("fixedBuffers")
    SectionBufferBuilderPack ip_getFixedBuffers();
    
    @Mutable
    @Accessor("fixedBuffers")
    void ip_setFixedBuffers(SectionBufferBuilderPack arg);

    // Immersive Aeronautics: read by MixinSableChunkedRenderData_DispatcherLevel to restore the level after a ship compile
    @Accessor("level")
    ClientLevel ip_getLevel();
}
