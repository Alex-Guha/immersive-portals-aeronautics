/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import qouteall.imm_ptl.core.compat.portal_pieces.IPPortalPieceTargets;
import com.simibubi.create.content.kinetics.base.RotatedPillarKineticBlock;
import com.simibubi.create.content.kinetics.simpleRelays.AbstractSimpleShaftBlock;
import com.simibubi.create.content.kinetics.simpleRelays.ShaftBlock;
import java.util.function.Predicate;
import net.createmod.catnip.placement.IPlacementHelper;
import net.createmod.catnip.placement.PlacementHelpers;
import net.createmod.catnip.placement.PlacementOffset;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;
import qouteall.imm_ptl.core.portal.PortalPlaceholderBlock;

/**
 * Create's shaft placement helper for a half shaft coming out of a portal ({@link IPPortalPieceTargets}): with a
 * shaft in hand, clicking any face of the piece places the shaft where it continues, and Create's ghost shows it there
 * first, as on a shaft ({@code ShaftBlock.PlacementHelper}). Create's own helper never sees the piece: the block hit is
 * the portal block.
 * <p>
 * The spot is worked out from the portal block alone, so the server, which has no pieces, agrees with the client: the
 * piece lies in the half of the portal block on the side of the hit, and its spot is the block next to that half
 * when a shaft across the portal faces it ({@link IPCreatePortalShafts#preferredAxis}'s test).
 */
public final class IPCreatePortalShaftPlacement implements IPlacementHelper {
    private static int id = -1;

    private IPCreatePortalShaftPlacement() {
    }

    /** Once, on the main thread after mods are constructed. */
    public static void register() {
        if (id < 0) {
            id = PlacementHelpers.register(new IPCreatePortalShaftPlacement());
        }
    }

    /** {@code PortalPlaceholderBlock.useItemOn}: places the shaft as Create's {@code ShaftBlock.useItemOn} does; null passes. */
    public static @Nullable ItemInteractionResult useItemOn(
        final ItemStack stack, final BlockState state, final Level level, final BlockPos pos, final Player player,
        final InteractionHand hand, final BlockHitResult hit
    ) {
        if (id < 0 || player.isShiftKeyDown() || !player.mayBuild()) {
            return null;
        }
        final IPlacementHelper helper = PlacementHelpers.get(id);
        if (!helper.matchesItem(stack)) {
            return null;
        }
        final PlacementOffset offset = helper.getOffset(player, level, state, pos, hit);
        if (!offset.isSuccessful()) {
            return null;
        }
        final ItemInteractionResult result = offset.placeInWorld(level, (BlockItem) stack.getItem(), player, hand, hit);
        return result == ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION ? null : result;
    }

    @Override
    public Predicate<ItemStack> getItemPredicate() {
        return stack -> stack.getItem() instanceof final BlockItem item && item.getBlock() instanceof AbstractSimpleShaftBlock;
    }

    @Override
    public Predicate<BlockState> getStatePredicate() {
        return state -> state.getBlock() == PortalPlaceholderBlock.instance;
    }

    @Override
    public PlacementOffset getOffset(final Player player, final Level world, final BlockState state, final BlockPos pos, final BlockHitResult ray) {
        final Direction.Axis axis = state.getValue(PortalPlaceholderBlock.AXIS);
        final Vec3 local = ray.getLocation().subtract(Vec3.atCenterOf(pos));
        final double along = axis.choose(local.x, local.y, local.z);
        if (Math.abs(along) < 1.0e-4) {
            return PlacementOffset.fail(); // on the plane: no side
        }
        final Direction out = Direction.get(along > 0 ? Direction.AxisDirection.POSITIVE : Direction.AxisDirection.NEGATIVE, axis);
        final BlockPos spot = pos.relative(out);
        if (!world.getBlockState(spot).canBeReplaced() || !IPCreatePortalShafts.shaftAcross(world, spot, out.getOpposite())) {
            return PlacementOffset.fail();
        }
        return PlacementOffset.success(spot, s -> {
            final BlockState placed = s.setValue(RotatedPillarKineticBlock.AXIS, axis);
            return world.isClientSide() ? placed : ShaftBlock.pickCorrectShaftType(placed, world, spot);
        });
    }
}
