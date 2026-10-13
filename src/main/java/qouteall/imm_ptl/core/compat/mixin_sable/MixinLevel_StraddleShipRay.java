/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import org.spongepowered.asm.mixin.Mixin;
import qouteall.imm_ptl.core.compat.sable_straddle.IPStraddleInteraction;

/**
 * A raycast that starts inside a ship's plot is the ship's own machinery (Aeronautics' hot-air burner finding its
 * envelope, lasers, optical sensors): it must see the whole ship, also the part a straddling ship has past the portal
 * plane, which {@code MixinClipContext_StraddleCut} hides from every other raycast. Sable overwrites
 * {@code BlockGetter.clip} as an interface default, which cannot be injected into, so {@code Level} overrides it here and
 * calls the default (Sable's) inside a marker.
 */
@Mixin(Level.class)
public abstract class MixinLevel_StraddleShipRay implements BlockGetter {
    @Override
    public BlockHitResult clip(final ClipContext context) {
        final boolean marked = IPStraddleInteraction.beginRay(context.getFrom());
        try {
            return BlockGetter.super.clip(context);
        } finally {
            IPStraddleInteraction.endRay(marked);
        }
    }
}
