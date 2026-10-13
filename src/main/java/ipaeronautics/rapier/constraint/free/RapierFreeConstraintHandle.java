/*
 * Copied from Sable 2.0.6 by RyanHCode (https://github.com/ryanhcode/sable), sable_rapier module.
 * Licensed under the PolyForm Shield License 1.0.0 (https://polyformproject.org/licenses/shield/1.0.0),
 * as are this project's changes to it. Modified for Immersive Portals: Aeronautics, 2026:
 * package moved to ipaeronautics.rapier.
 */
package ipaeronautics.rapier.constraint.free;

import dev.ryanhcode.sable.api.physics.PhysicsPipelineBody;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintConfiguration;
import dev.ryanhcode.sable.api.physics.constraint.FreeConstraintHandle;
import ipaeronautics.rapier.Rapier3D;
import ipaeronautics.rapier.constraint.RapierConstraintHandle;
import net.minecraft.server.level.ServerLevel;
import org.jetbrains.annotations.Nullable;

public class RapierFreeConstraintHandle extends RapierConstraintHandle implements FreeConstraintHandle {
    /**
     * Creates a rapier constraint handle
     */
    public static RapierFreeConstraintHandle create(final ServerLevel serverLevel, @Nullable final PhysicsPipelineBody bodyA, @Nullable final PhysicsPipelineBody bodyB, final FreeConstraintConfiguration config) {
        final long sceneHandle = Rapier3D.getSceneHandle(serverLevel);

        final long handle = Rapier3D.addFreeConstraint(
                sceneHandle,
                bodyA == null ? -1 :  Rapier3D.getID(bodyA),
                bodyB == null ? -1 :  Rapier3D.getID(bodyB),
                config.pos1().x(),
                config.pos1().y(),
                config.pos1().z(),
                config.pos2().x(),
                config.pos2().y(),
                config.pos2().z(),
                config.orientation().x(),
                config.orientation().y(),
                config.orientation().z(),
                config.orientation().w()
        );

        return new RapierFreeConstraintHandle(sceneHandle, handle);
    }

    /**
     * Creates a new constraint handle
     *
     * @param sceneHandle the scene ID that this constraint is in
     * @param handle the handle from the physics engine
     */
    public RapierFreeConstraintHandle(final long sceneHandle, final long handle) {
        super(sceneHandle, handle);
    }

}
