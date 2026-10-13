/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.block.entity.BlockEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;
import qouteall.imm_ptl.core.compat.sable_integration.IPShipGlobalBlockEntities;

import java.util.Collection;

/**
 * A section's global block entities go to the renderer of their own level ({@link IPShipGlobalBlockEntities}), not to
 * the renderer owning the section dispatcher (one for all ships).
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection", remap = false)
public class MixinRenderSection_GlobalBlockEntitiesPerLevel {
    @Redirect(method = "updateGlobalBlockEntities", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/renderer/LevelRenderer;updateGlobalBlockEntities(Ljava/util/Collection;Ljava/util/Collection;)V"),
            require = 0)
    private void ip_registerWithOwnLevel(final LevelRenderer renderer, final Collection<BlockEntity> removed,
                                         final Collection<BlockEntity> added) {
        IPShipGlobalBlockEntities.update(renderer, removed, added);
    }
}
