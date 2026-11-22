/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.create_integration;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * A controlled contraption as the far copy of an elevator car ({@code MixinControlledContraptionEntity_ElevatorFarCopy}):
 * which pulley's car it stands for, through which shaft, and where the car put it this tick.
 */
public interface IPElevatorFarCopy {
    record FarInfo(ResourceKey<Level> homeDim, BlockPos pulleyPos, IPElevatorShaft shaft) {
    }

    @Nullable FarInfo ip$farInfo();

    void ip$setFarInfo(@Nullable FarInfo info);

    /** Where the car placed it, this tick and the one before (for interpolation); null until the first push. */
    @Nullable Vec3 ip$target();

    @Nullable Vec3 ip$prevTarget();

    void ip$setTargets(Vec3 target, Vec3 prev);

    /** Server tick of the last push from its car. */
    long ip$lastPush();

    void ip$setLastPush(long tick);
}
