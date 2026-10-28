/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.client.accessor;

import net.minecraft.client.renderer.chunk.RenderChunkRegion;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(RenderChunkRegion.class)
public interface IERenderChunkRegion {
    @Accessor("level")
    Level ip_getLevel();
}
