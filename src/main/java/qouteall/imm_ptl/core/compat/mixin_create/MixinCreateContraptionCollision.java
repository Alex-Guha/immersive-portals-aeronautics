/*
 * Immersive Portals: Aeronautics - reconstructed and modified file.
 * Original: Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0), a derivative of
 * Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0). Rebuilt as source from the
 * binary release and modified for Immersive Portals: Aeronautics, 2026. This project's changes are
 * licensed under the PolyForm Shield License 1.0.0; the original code stays under the Apache
 * License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import com.simibubi.create.content.contraptions.AbstractContraptionEntity;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import qouteall.imm_ptl.core.portal.Portal;

/**
 * Makes Create contraptions ({@code AbstractContraptionEntity.canCollideWith}) ignore IP
 * {@link Portal} entities, so moving contraptions are not blocked by portal entities.
 */
@Mixin(
    value = AbstractContraptionEntity.class,
    remap = false
)
public class MixinCreateContraptionCollision {
    @Inject(
        method = "canCollideWith",
        at = @At("HEAD"),
        cancellable = true,
        require = 0,
        expect = 0
    )
    private void ip_skipPortals(Entity other, CallbackInfoReturnable<Boolean> cir) {
        if (other instanceof Portal) {
            cir.setReturnValue(false);
        }
    }
}
