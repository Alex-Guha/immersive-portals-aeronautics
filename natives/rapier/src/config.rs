// From IPSable 0.5.0 by r2smith141 (https://github.com/r2smith141/IPSable), natives/rapier: Sable's sable_rapier
// crate by RyanHCode (https://github.com/ryanhcode/sable, 2.0.3) with IPSable's changes (Apache License 2.0).
// Sable's code is licensed under the PolyForm Shield License 1.0.0 (see natives/LICENSE.md), as are
// this project's changes to it. Rebased on Sable 2.0.6 and modified for Immersive Portals: Aeronautics, 2026:
// JNI symbols renamed for ipaeronautics.rapier.Rapier3D.

use jni::JNIEnv;
use jni::objects::JClass;
use jni::sys::{jdouble, jint};
use marten::Real;

use crate::get_physics_state_mut;

/// Global spring frequency for joints (Hz)
pub const JOINT_SPRING_FREQUENCY: Real = 550.0;

/// Global damping ratio for joints
pub const JOINT_SPRING_DAMPING_RATIO: Real = 4.0;

#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_Rapier3D_configFrequencyAndDamping<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    collision_natural_frequency: jdouble,
    collision_damping_ratio: jdouble,
) {
    let mut state = get_physics_state_mut();
    state
        .integration_parameters
        .contact_softness
        .natural_frequency = collision_natural_frequency as Real;
    state.integration_parameters.contact_softness.damping_ratio = collision_damping_ratio as Real;
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_Rapier3D_configSolverIterations<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    num_solver_iterations: jint,
    num_internal_pgs_iterations: jint,
    num_internal_stabilization_iterations: jint,
) {
    let mut state = get_physics_state_mut();
    state.integration_parameters.num_solver_iterations = num_solver_iterations as usize;
    state.integration_parameters.num_internal_pgs_iterations = num_internal_pgs_iterations as usize;
    state
        .integration_parameters
        .num_internal_stabilization_iterations = num_internal_stabilization_iterations as usize;
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_Rapier3D_configMinIslandSize<
    'local,
>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    island_size: jint,
) {
    get_physics_state_mut()
        .integration_parameters
        .min_island_size = island_size as usize;
}
