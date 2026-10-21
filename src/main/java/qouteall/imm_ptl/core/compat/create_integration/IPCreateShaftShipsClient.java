/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import dev.engine_room.flywheel.api.visualization.VisualEmbedding;
import dev.engine_room.flywheel.impl.visualization.VisualManagerImpl;
import dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl;
import dev.ryanhcode.sable.neoforge.mixinhelper.compatibility.flywheel.SubLevelEmbedding;
import dev.ryanhcode.sable.neoforge.mixinterface.compatibility.flywheel.BlockEntityStorageExtension;
import org.jetbrains.annotations.Nullable;

/**
 * The client Sable side of the half shafts in portal blocks ({@link IPCreatePortalShaftHalves}): a ship's Flywheel
 * visuals are drawn in one embedding per ship, which Sable moves with the ship each frame
 * ({@code BlockEntityStorageMixin}); half shafts on a ship are instances of that embedding, so they follow the ship
 * and get its light and IP's straddle cut as its block entities do. Only loaded when Sable is.
 */
final class IPCreateShaftShipsClient {
    private IPCreateShaftShipsClient() {
    }

    /**
     * The embedding Sable draws {@code frame}'s ship with in {@code manager}, or null while it has none (no block entity
     * visual on the ship yet). Main thread, outside Flywheel's frame plan (which adds embeddings).
     */
    static @Nullable VisualEmbedding embeddingOf(final VisualizationManagerImpl manager, final IPCreatePortalShafts.Frame frame) {
        if (!(frame instanceof IPCreateShaftShips.ShipFrame ship)
            || !(manager.blockEntities() instanceof VisualManagerImpl<?, ?> blockEntities)
            || !(blockEntities.getStorage() instanceof BlockEntityStorageExtension storage)) {
            return null;
        }
        try {
            final SubLevelEmbedding info = storage.sable$getEmbeddingInfo(ship.subLevel());
            return info == null ? null : info.embedding();
        } catch (final IllegalArgumentException notClientSide) {
            return null;
        }
    }
}
