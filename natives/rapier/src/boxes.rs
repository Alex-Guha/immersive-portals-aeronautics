// From IPSable 0.5.0 by r2smith141 (https://github.com/r2smith141/IPSable), natives/rapier: Sable's sable_rapier
// crate by RyanHCode (https://github.com/ryanhcode/sable, 2.0.3) with IPSable's changes (Apache License 2.0).
// Sable's code is licensed under the PolyForm Shield License 1.0.0 (see natives/LICENSE.md), as are
// this project's changes to it. Rebased on Sable 2.0.6 for Immersive Portals: Aeronautics, 2026.

use jni::JNIEnv;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jdouble, jint, jlong};
use marten::Real;
use rapier3d::dynamics::RigidBodyBuilder;
use rapier3d::geometry::{ColliderBuilder, SharedShape};
use rapier3d::glamx::Quat;
use rapier3d::math::Vec3;

use crate::scene::LevelColliderID;
use crate::with_handle;

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_createBox<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
    mass: jdouble,
    half_extent_x: jdouble,
    half_extent_y: jdouble,
    half_extent_z: jdouble,
    pose: JDoubleArray<'local>,
) {
    let mut pose_arr: [jdouble; 7] = [0.0, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0];
    env.get_double_array_region(pose, 0, &mut pose_arr).unwrap();

    let quat = Quat::from_xyzw(
        pose_arr[3] as Real,
        pose_arr[4] as Real,
        pose_arr[5] as Real,
        pose_arr[6] as Real,
    );

    let mut rigid_body = RigidBodyBuilder::dynamic()
        .ccd_enabled(true)
        .translation(Vec3::new(
            pose_arr[0] as Real,
            pose_arr[1] as Real,
            pose_arr[2] as Real,
        ))
        .build();
    rigid_body.set_rotation(quat, false);

    with_handle(handle, |scene| {
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let sim_data = &mut *sim_data;
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);

        let handle = sim_data.rigid_body_set.insert(rigid_body);

        // make a level collider
        // Atlas: boxes are level-class in the calling chart (previously ungrouped =
        // all/all, which would leak contacts across charts in the merged world).
        let collider = ColliderBuilder::new(SharedShape::cuboid(
            half_extent_x as Real,
            half_extent_y as Real,
            half_extent_z as Real,
        ))
        .mass(mass as Real)
        .friction(0.45)
        .collision_groups(crate::groups::level_group(scene.chart))
        .build();

        sim_data
            .collider_set
            .insert_with_parent(collider, handle, &mut sim_data.rigid_body_set);

        sable_data
            .rigid_bodies
            .insert(id as LevelColliderID, handle);
    })
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_dev_ryanhcode_sable_physics_impl_rapier_Rapier3D_removeBox<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    id: jint,
) {
    with_handle(handle, |scene| {
        let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let sim_data = &mut *sim_data;
        let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);

        let handle = sable_data.rigid_bodies[&(id as LevelColliderID)];
        sim_data.rigid_body_set.remove(
            handle,
            &mut sim_data.island_manager,
            &mut sim_data.collider_set,
            &mut sim_data.impulse_joint_set,
            &mut sim_data.multibody_joint_set,
            true,
        );

        sable_data.rigid_bodies.remove(&(id as LevelColliderID));
    })
}
