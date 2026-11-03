/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.common.debug;

import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.server.level.GenerationChunkHolder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The generation task a chunk is the centre of, for {@code IPUnloadSpinTrace}. */
@Mixin(GenerationChunkHolder.class)
public interface IPGenerationChunkHolderAccessor {
    @Accessor("task")
    AtomicReference<ChunkGenerationTask> ip_getTask();
}
