/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.mixin_create;

import dev.djefrey.colorwheel.engine.ClrwlEngine;
import dev.djefrey.colorwheel.engine.embed.EmbeddedEnvironment;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Colorwheel's embedding's engine and parent ({@code IPColorwheelEmbeddings}); applied only with Colorwheel. */
@Mixin(value = EmbeddedEnvironment.class, remap = false)
public interface IPColorwheelEmbeddedAccessor {
    @Accessor("engine")
    ClrwlEngine ip_getEngine();

    @Accessor("parent")
    EmbeddedEnvironment ip_getParent();
}
