/*
 * Copied from Sable 2.0.6 by RyanHCode (https://github.com/ryanhcode/sable), sable_rapier module.
 * Licensed under the PolyForm Shield License 1.0.0 (https://polyformproject.org/licenses/shield/1.0.0),
 * as are this project's changes to it. Modified for Immersive Portals: Aeronautics, 2026:
 * package moved to ipaeronautics.rapier.
 */
package ipaeronautics.rapier.collider;

import dev.ryanhcode.sable.api.physics.collider.VoxelColliderData;
import ipaeronautics.rapier.Rapier3D;
import org.joml.Vector3dc;

/**
 * Represents a block physics data entry in the physics world.
 *
 * @param handle the internal integer handle of the block physics data entry
 */
public record RapierVoxelColliderData(int handle) implements VoxelColliderData {
    public static final RapierVoxelColliderData EMPTY = new RapierVoxelColliderData(-1);

    /**
     * Adds a collision box to the block physics data entry.
     * Coordinates are expected to be within a single voxel space of the block, 0-1.
     *
     * @param min the minimum corner of the box
     * @param max the maximum corner of the box
     */
    @Override
    public void addBox(final Vector3dc min, final Vector3dc max) {
        Rapier3D.addVoxelColliderBox(this.handle, new double[]{min.x(), min.y(), min.z(), max.x(), max.y(), max.z()});
    }

    /**
     * Clears all collision boxes
     */
    @Override
    public void clearBoxes() {
        Rapier3D.clearVoxelColliderBoxes(this.handle);
    }
}
