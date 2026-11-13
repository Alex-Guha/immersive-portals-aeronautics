/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0), with changes from
 * Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog/. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code and ItsSoBread's changes stay under the
 * Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.portal.nether_portal;

import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public class GeneralBreakablePortal extends BreakablePortalEntity {
    
    public static final EntityType<GeneralBreakablePortal> ENTITY_TYPE =
        createPortalEntityType(GeneralBreakablePortal::new);
    
    public GeneralBreakablePortal(EntityType<?> entityType, Level world) {
        super(entityType, world);
    }
    
    @Override
    protected boolean isPortalIntactOnThisSide() {
        // Immersive Aeronautics: never break a portal whose frame chunks are not fully loaded
        for (BlockPos blockPos : blockPortalShape.area) {
            if (level().getChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4, ChunkStatus.FULL, false) == null) {
                return true;
            }
        }
        for (BlockPos blockPos : blockPortalShape.frameAreaWithoutCorner) {
            if (level().getChunk(blockPos.getX() >> 4, blockPos.getZ() >> 4, ChunkStatus.FULL, false) == null) {
                return true;
            }
        }
        
        boolean areaIntact = blockPortalShape.area.stream()
            .allMatch(blockPos ->
                level().getBlockState(blockPos).getBlock() == PortalPlaceholderBlock.instance
            );
        boolean frameIntact = blockPortalShape.frameAreaWithoutCorner.stream()
            .allMatch(blockPos -> !level().isEmptyBlock(blockPos));
        return areaIntact && frameIntact;
    }
    
    @Override
    protected void addSoundAndParticle() {
    
    }
}
