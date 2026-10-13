/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.engine_room.flywheel.backend.engine.EngineImpl;
import dev.engine_room.flywheel.backend.engine.embed.EmbeddedEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = EmbeddedEnvironment.class, remap = false)
public interface IPEmbeddedEnvironmentAccessor {
    @Accessor("engine")
    EngineImpl ip_getEngine();

    @Accessor("parent")
    EmbeddedEnvironment ip_getParent();
}
