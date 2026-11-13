/*
 * Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
 */
package qouteall.imm_ptl.core.compat.sable_physics;

import net.minecraft.server.level.ServerLevel;

/**
 * Sable's {@code RapierVoxelColliderBakery} with the level its colliders belong to
 * ({@code MixinSableRapierBakery_LevelBound}): set only while our library runs, so the colliders' block collision
 * callbacks are bound to it ({@link LevelBoundCollisionCallback}).
 */
public interface IPRapierColliderBakeryLevel {
    void ip_bindLevel(ServerLevel level);
}
