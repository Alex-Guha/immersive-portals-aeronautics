/*
 * Immersive Portals: Aeronautics - modified file.
 * Original: Immersive Portals for NeoForge (iPortalTeam; Apache License 2.0).
 * Modified for Immersive Portals: Aeronautics, 2026; changes are marked "Immersive Aeronautics:"
 * in the code and summarised in changelog.md. This project's changes are licensed under the
 * PolyForm Shield License 1.0.0; the original code stays under the Apache License 2.0. See NOTICE.
 */
package qouteall.imm_ptl.core.portal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;
import qouteall.imm_ptl.core.McHelper;
import qouteall.imm_ptl.core.portal.nether_portal.BreakablePortalEntity;

public class PortalPlaceholderBlock extends Block {
    public static final EnumProperty<Direction.Axis> AXIS = BlockStateProperties.AXIS;
    public static final VoxelShape X_AABB = Block.box(
        6.0D,
        0.0D,
        0.0D,
        10.0D,
        16.0D,
        16.0D
    );
    public static final VoxelShape Y_AABB = Block.box(
        0.0D,
        6.0D,
        0.0D,
        16.0D,
        10.0D,
        16.0D
    );
    public static final VoxelShape Z_AABB = Block.box(
        0.0D,
        0.0D,
        6.0D,
        16.0D,
        16.0D,
        10.0D
    );
    
    public static final PortalPlaceholderBlock instance = new PortalPlaceholderBlock(
        Properties.of()
            .noCollission()
            .sound(SoundType.GLASS)
            .strength(1.0f, 0)
            .noOcclusion()
            .noLootTable()
            .lightLevel((s) -> 15)
    );
    
    public PortalPlaceholderBlock(Properties properties) {
        super(properties);
        this.registerDefaultState(
                this.getStateDefinition().any().setValue(
                    AXIS, Direction.Axis.X
                )
        );
    }
    
    @Override
    public VoxelShape getShape(
        BlockState state, BlockGetter world, BlockPos blockPos, CollisionContext shapeContext
    ) {
        switch (state.getValue(AXIS)) {
            case Z:
                return Z_AABB;
            case Y:
                return Y_AABB;
            case X:
            default:
                return X_AABB;
        }
    }
    
    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(AXIS);
    }
    
    // Immersive Aeronautics: Create tracks laid up to a portal before it opened get their bridge into it now
    private static final boolean CREATE = net.neoforged.fml.ModList.get().isLoaded("create");
    
    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        if (CREATE && level instanceof ServerLevel serverLevel && !oldState.is(this)) {
            qouteall.imm_ptl.core.compat.create_integration.IPCreatePortalTracks.onPortalBlockPlaced(
                serverLevel, pos, state.getValue(AXIS)
            );
        }
    }
    
    @Override
    public BlockState updateShape(
        BlockState thisState,
        Direction direction,
        BlockState neighborState,
        LevelAccessor worldAccess,
        BlockPos blockPos,
        BlockPos neighborPos
    ) {
        if (!worldAccess.isClientSide()) {
            if (worldAccess instanceof Level world) {

                world.getProfiler().push("portal_placeholder");
                
                Direction.Axis axis = thisState.getValue(AXIS);
                if (direction.getAxis() != axis) {
                    McHelper.findEntitiesRough(
                        BreakablePortalEntity.class,
                        world,
                        Vec3.atLowerCornerOf(blockPos),
                        2,
                        e -> true
                    ).forEach(
                        portal -> {
                            portal.notifyPlaceholderUpdate();
                        }
                    );
                }
                
                world.getProfiler().pop();
            }
        }
        
        return super.updateShape(
            thisState,
            direction,
            neighborState,
            worldAccess,
            blockPos,
            neighborPos
        );
    }
    
    public static boolean isHitOnPlaceholder(HitResult hitResult, Level world) {
        if (hitResult.getType() == HitResult.Type.BLOCK) {
            if (hitResult instanceof BlockHitResult blockHitResult) {
                Block hittingBlock = world.getBlockState(blockHitResult.getBlockPos()).getBlock();
                return hittingBlock == PortalPlaceholderBlock.instance;
            }
        }
        return false;
    }
    
    //---------These are copied from BlockBarrier
    @Override
    public boolean propagatesSkylightDown(
        BlockState blockState_1,
        BlockGetter blockView_1,
        BlockPos blockPos_1
    ) {
        return true;
    }
    
    @Override
    public RenderShape getRenderShape(BlockState blockState_1) {
        return RenderShape.INVISIBLE;
    }
    
    //@OnlyIn(Dist.CLIENT)
    @Override
    public float getShadeBrightness(
        BlockState blockState_1,
        BlockGetter blockView_1,
        BlockPos blockPos_1
    ) {
        return 1.0F;
    }
}
