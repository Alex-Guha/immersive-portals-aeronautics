/*
 * Immersive Portals Revamped, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
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
    /**
     * @param index       which portal of the chain it stands past (0: the first below the pulley)
     * @param shaft       the map from the pulley's frame to the copy's ({@link IPElevatorShaft#then}), its plane the copy's top
     * @param bottomPlane the next portal's plane below, in the pulley's frame; {@link Double#NEGATIVE_INFINITY} if none
     */
    record FarInfo(ResourceKey<Level> homeDim, BlockPos pulleyPos, int index, IPElevatorShaft shaft, double bottomPlane) {
        /** The next portal's plane in the copy's own frame. */
        public double farBottomPlaneY() {
            return bottomPlane + shaft.shift().getY();
        }

        public boolean hasBottom() {
            return bottomPlane != Double.NEGATIVE_INFINITY;
        }
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
