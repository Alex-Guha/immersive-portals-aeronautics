/*
 * Copied from Sable 2.0.6 by RyanHCode (https://github.com/ryanhcode/sable), sable_rapier module.
 * Licensed under the PolyForm Shield License 1.0.0 (https://polyformproject.org/licenses/shield/1.0.0),
 * as are this project's changes to it. Modified for Immersive Portals: Aeronautics, 2026:
 * package moved to ipaeronautics.rapier.
 */
package ipaeronautics.rapier.collider;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.FluidState;
import net.minecraft.world.level.material.Fluids;
import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import java.util.Objects;

/**
 * Physics data is specific for each block regardless of where it's placed or what possible block entity state may exist.
 * This class makes sure any blocks that attempt to change state based on their neighbors will not cause crashes.
 */
@ApiStatus.Internal
public final class PhysicsColliderBlockGetter implements BlockGetter {

    private final BlockGetter level;
    private BlockState state;

    public PhysicsColliderBlockGetter(final BlockGetter level) {
        this.level = level;
        this.state = Blocks.AIR.defaultBlockState();
    }

    public void setup(final BlockState state) {
        this.state = Objects.requireNonNull(state, "state");
    }

    @Override
    public @Nullable BlockEntity getBlockEntity(final @NotNull BlockPos pos) {
        return null;
    }

    @Override
    public @NotNull BlockState getBlockState(@NotNull final BlockPos pos) {
        return BlockPos.ZERO.equals(pos) ? this.state : Blocks.AIR.defaultBlockState();
    }

    @Override
    public @NotNull FluidState getFluidState(@NotNull final BlockPos pos) {
        return BlockPos.ZERO.equals(pos) ? this.state.getFluidState() : Fluids.EMPTY.defaultFluidState();
    }

    @Override
    public int getHeight() {
        return this.level.getHeight();
    }

    @Override
    public int getMinBuildHeight() {
        return this.level.getMinBuildHeight();
    }
}
