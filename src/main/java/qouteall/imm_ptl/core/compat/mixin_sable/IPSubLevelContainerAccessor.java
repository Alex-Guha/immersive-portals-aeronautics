/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_sable;

import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelObserver;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * The observers {@code SubLevelContainer.tick} calls, which {@code IPFusedPhysicsStep} calls itself.
 */
@Mixin(
    value = SubLevelContainer.class,
    remap = false
)
public interface IPSubLevelContainerAccessor {
    @Accessor("observers")
    List<SubLevelObserver> ip_getObservers();
}
