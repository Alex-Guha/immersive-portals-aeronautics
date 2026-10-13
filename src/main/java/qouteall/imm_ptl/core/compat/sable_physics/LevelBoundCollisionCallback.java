/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_physics;

import dev.ryanhcode.sable.api.physics.callback.BlockSubLevelCollisionCallback;
import dev.ryanhcode.sable.sublevel.system.SubLevelPhysicsSystem;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3d;

/**
 * A block collision callback bound to the level whose colliders carry it. Sable's callbacks are shared singletons
 * (one per block type) that find their level through {@link SubLevelPhysicsSystem#getCurrentlySteppingSystem()};
 * Sable steps each level in its own call, so that is always the right level. With ipa_rapier one native step serves
 * every level ({@link IPFusedPhysicsStep}), so the native calls back for contacts of any level while one system is
 * marked as stepping: {@code FragileBlockCallback} then re-read the block in the wrong level, found it not fragile and
 * dropped the contact, and ships fell through leaves. Each level's collider bakery wraps its callbacks in one of
 * these, and contacts always use the colliders of the level they belong to.
 */
public final class LevelBoundCollisionCallback implements BlockSubLevelCollisionCallback {
    private final BlockSubLevelCollisionCallback delegate;
    private final ServerLevel level;

    public LevelBoundCollisionCallback(final BlockSubLevelCollisionCallback delegate, final ServerLevel level) {
        this.delegate = delegate;
        this.level = level;
    }

    @Override
    public double[] onCollision(final int x, final int y, final int z, final int otherX, final int otherY, final int otherZ,
                                final double x1, final double y1, final double z1, final double impactVelocity,
                                final boolean hasOtherBlock) {
        final SubLevelPhysicsSystem previous = SubLevelPhysicsSystem.currentlySteppingSystem;
        SubLevelPhysicsSystem.currentlySteppingSystem = this.system(previous);
        try {
            return this.delegate.onCollision(x, y, z, otherX, otherY, otherZ, x1, y1, z1, impactVelocity, hasOtherBlock);
        } finally {
            SubLevelPhysicsSystem.currentlySteppingSystem = previous;
        }
    }

    @Override
    public CollisionResult sable$onCollision(final BlockPos hitBlockPos, @Nullable final BlockPos otherHitBlockPos,
                                             final Vector3d impactPosition, final double impactVelocity) {
        final SubLevelPhysicsSystem previous = SubLevelPhysicsSystem.currentlySteppingSystem;
        SubLevelPhysicsSystem.currentlySteppingSystem = this.system(previous);
        try {
            return this.delegate.sable$onCollision(hitBlockPos, otherHitBlockPos, impactPosition, impactVelocity);
        } finally {
            SubLevelPhysicsSystem.currentlySteppingSystem = previous;
        }
    }

    private SubLevelPhysicsSystem system(final SubLevelPhysicsSystem fallback) {
        final SubLevelPhysicsSystem system = SubLevelPhysicsSystem.get(this.level);
        return system != null ? system : fallback;
    }
}
