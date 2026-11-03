/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.mixin.common.debug;

import net.minecraft.server.level.ChunkGenerationTask;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Where a generation task stands, for {@code IPUnloadSpinTrace}. */
@Mixin(ChunkGenerationTask.class)
public interface IPChunkGenerationTaskAccessor {
    @Accessor("pos")
    ChunkPos ip_getPos();

    @Accessor("scheduledStatus")
    ChunkStatus ip_getScheduledStatus();

    @Accessor("markedForCancellation")
    boolean ip_isMarkedForCancellation();
}
