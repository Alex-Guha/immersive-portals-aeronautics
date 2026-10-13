/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_integration;

import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import qouteall.imm_ptl.core.ClientWorldLoader;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Registers a compiled section's "global" block entities (drawn whatever the camera sees: rope connectors and winches,
 * beacons) with the {@code LevelRenderer} of their own level. Vanilla registers them with the renderer that owns the
 * section dispatcher; Sable compiles every ship of every dimension on one shared dispatcher, so the global block
 * entities of a ship in the nether went to one dimension's renderer (the overworld's): a rope of a ship in the nether
 * was drawn only when that renderer drew the overworld (through a portal, at nether coordinates) and never in the
 * nether (docs/lessons/portal-rendering.md). A removed block entity is unregistered from every renderer, which also clears ones
 * registered with the wrong renderer earlier.
 */
public final class IPShipGlobalBlockEntities {
    private IPShipGlobalBlockEntities() {
    }

    public static void update(LevelRenderer sectionRenderer, Collection<BlockEntity> removed, Collection<BlockEntity> added) {
        if (allOwnedBy(sectionRenderer, removed) && allOwnedBy(sectionRenderer, added)) {
            sectionRenderer.updateGlobalBlockEntities(removed, added);
            return;
        }
        Map<LevelRenderer, List<BlockEntity>> addedByRenderer = new IdentityHashMap<>();
        for (BlockEntity blockEntity : added) {
            addedByRenderer.computeIfAbsent(rendererOf(blockEntity, sectionRenderer), renderer -> new ArrayList<>()).add(blockEntity);
        }
        Set<LevelRenderer> renderers = Collections.newSetFromMap(new IdentityHashMap<>());
        renderers.add(sectionRenderer);
        renderers.addAll(ClientWorldLoader.WORLD_RENDERER_MAP.values());
        renderers.addAll(addedByRenderer.keySet());
        for (LevelRenderer renderer : renderers) {
            renderer.updateGlobalBlockEntities(removed, addedByRenderer.getOrDefault(renderer, List.of()));
        }
    }

    private static boolean allOwnedBy(LevelRenderer renderer, Collection<BlockEntity> blockEntities) {
        for (BlockEntity blockEntity : blockEntities) {
            if (rendererOf(blockEntity, renderer) != renderer) {
                return false;
            }
        }
        return true;
    }

    private static LevelRenderer rendererOf(BlockEntity blockEntity, LevelRenderer fallback) {
        Level level = blockEntity.getLevel();
        LevelRenderer renderer = level == null ? null : ClientWorldLoader.WORLD_RENDERER_MAP.get(level.dimension());
        return renderer == null ? fallback : renderer;
    }
}
