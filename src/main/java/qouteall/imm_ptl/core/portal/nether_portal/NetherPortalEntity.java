/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0), with changes from
 * Immersive Aeronautics 1.1.4 by ItsSoBread (Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code and ItsSoBread's changes stay under the
 * Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.portal.nether_portal;

import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.NetherPortalBlock;
import net.minecraft.world.phys.Vec3;
import qouteall.imm_ptl.core.IPGlobal;
import qouteall.imm_ptl.core.platform_specific.O_O;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;
import qouteall.q_misc_util.my_util.DQuaternion;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

public class NetherPortalEntity extends BreakablePortalEntity {
    private static final OverlayInfo overlay_x = new OverlayInfo(
        Blocks.NETHER_PORTAL.defaultBlockState().setValue(
            NetherPortalBlock.AXIS,
            Direction.Axis.Z
        ),
        0.5,
        0,
        null
    );
    private static final OverlayInfo overlay_y_up = new OverlayInfo(
        Blocks.NETHER_PORTAL.defaultBlockState().setValue(
            NetherPortalBlock.AXIS,
            Direction.Axis.X
        ),
        0.5,
        1,
        DQuaternion.rotationByDegrees(new Vec3(1, 0, 0), 90)
    );
    private static final OverlayInfo overlay_y_down = new OverlayInfo(
        Blocks.NETHER_PORTAL.defaultBlockState().setValue(
            NetherPortalBlock.AXIS,
            Direction.Axis.X
        ),
        0.5,
        -1,
        DQuaternion.rotationByDegrees(new Vec3(1, 0, 0), 90)
    );
    private static final OverlayInfo overlay_z = new OverlayInfo(
        Blocks.NETHER_PORTAL.defaultBlockState().setValue(
            NetherPortalBlock.AXIS,
            Direction.Axis.X
        ),
        0.5,
        0,
        null
    );
    
    
    public static final EntityType<NetherPortalEntity> ENTITY_TYPE =
        createPortalEntityType(NetherPortalEntity::new);
    
    public NetherPortalEntity(EntityType<?> entityType, Level world) {
        super(entityType, world);
    }
    
    @Override
    public void tick() {
        super.tick();
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
        
        return blockPortalShape.area.stream()
            .allMatch(blockPos ->
                level().getBlockState(blockPos).getBlock() == PortalPlaceholderBlock.instance
            ) &&
            blockPortalShape.frameAreaWithoutCorner.stream()
                .allMatch(blockPos ->
                    O_O.isObsidian(level().getBlockState(blockPos))
                );
    }
    
    @Override
    //@OnlyIn(Dist.CLIENT)
    protected void addSoundAndParticle() {
        if (!IPGlobal.enableNetherPortalEffect) {
            return;
        }
        
        RandomSource random = level().getRandom();
        
        for (int i = 0; i < (int) Math.ceil(getWidth() * getHeight() / 20); i++) {
            if (random.nextInt(10) == 0) {
                double px = (random.nextDouble() * 2 - 1) * (getWidth() / 2);
                double py = (random.nextDouble() * 2 - 1) * (getHeight() / 2);
                
                Vec3 pos = getPointInPlane(px, py);
                
                double speedMultiplier = 20;
                
                double vx = speedMultiplier * ((double) random.nextFloat() - 0.5D) * 0.5D;
                double vy = speedMultiplier * ((double) random.nextFloat() - 0.5D) * 0.5D;
                double vz = speedMultiplier * ((double) random.nextFloat() - 0.5D) * 0.5D;
                
                level().addParticle(
                    ParticleTypes.PORTAL,
                    pos.x, pos.y, pos.z,
                    vx, vy, vz
                );
            }
        }
        
        if (random.nextInt(800) == 0) {
            level().playLocalSound(
                getX(),
                getY(),
                getZ(),
                SoundEvents.PORTAL_AMBIENT,
                SoundSource.BLOCKS,
                0.5F,
                random.nextFloat() * 0.4F + 0.8F,
                false
            );
        }
    }
    
    @Override
    public OverlayInfo getActualOverlay() {
        if (IPGlobal.netherPortalOverlay) {
            switch (blockPortalShape.axis) {
                case X -> {return overlay_x;}
                case Y -> {return getNormal().y > 0 ? overlay_y_up : overlay_y_down;}
                case Z -> {return overlay_z;}
            }
        }
        
        return super.getActualOverlay();
    }
}
