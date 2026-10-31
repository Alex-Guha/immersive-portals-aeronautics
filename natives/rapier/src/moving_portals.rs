// Immersive Portals: Aeronautics, 2026 (PolyForm Shield License 1.0.0). See NOTICE and LICENSE.
//
// Moving portals (portals mounted on ships, docs/plans/ship-mounted-portals.md phase 2). A body straddling a portal
// that is mounted on another body (the carrier) has an image whose portal isometry follows the carrier: it is derived
// from the carrier's pose at the start of every step, never sent per tick from Java, and the image gets the portal's
// frame velocity (Rapier surface velocity, see the engine's contact builders) so its contacts see the portal move.
// Clip regions at a mounted portal follow the carrier the same way, and so does the rim of a mounted frameless portal
// (a kinematic body sent where the carrier will be at the end of the step, so it moves with the carrier's velocity).
//
// Java sends every transform relative to the carrier's body frame (the engine is f32 and never sees plot coordinates):
//   - carrier is the source (the body is in the carrier's world, its image at the fixed portal): `k` maps the carrier's
//     body frame to the fixed side, and the prefix is `P = k ∘ B⁻¹`;
//   - carrier is the destination (the body is at the fixed portal, its image at the carrier): `k` maps the fixed side
//     into the carrier's body frame, and `P = B ∘ k`;
//   - both sides on carriers (docs/reports/both-ends-on-ships.md; the source carrier `S` and the destination carrier `D`,
//     possibly one body): `k` maps the source carrier's body frame into the destination carrier's, and `P = D ∘ k ∘ S⁻¹`.

use jni::JNIEnv;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jdouble, jint, jlong};
use rapier3d::geometry::PortalFrameVelocity;
use rapier3d::glamx::{Pose3, Quat};
use rapier3d::math::{Real, Vec3};
use rapier3d::prelude::{ColliderHandle, RigidBodyHandle};

use crate::ipl_ext::{ClipAnchor, IplClipRegion, read_clip_regions};
use crate::scene::{LevelColliderID, PhysicsScene, SableSceneData, SimulationSceneData};

#[derive(Copy, Clone, Debug)]
pub enum AnchorMode {
    CarrierIsSource,
    CarrierIsDestination,
    /// `carrier` carries the source side, this body the destination side.
    BetweenCarriers(RigidBodyHandle),
}

/// The anchoring of one image collider to a carrier body.
#[derive(Copy, Clone, Debug)]
pub struct ImageAnchor {
    pub carrier: RigidBodyHandle,
    pub mode: AnchorMode,
    pub k: Pose3,
}

/// The anchoring of one portal rim (its kinematic body) to a carrier body: the rim's pose is `B ∘ k`.
#[derive(Copy, Clone, Debug)]
pub struct RimAnchor {
    pub carrier: RigidBodyHandle,
    pub k: Pose3,
}

/// Recomputes every anchored image's prefix and frame velocity, and every anchored clip region, from the carriers'
/// current poses, and sends anchored rims where their carriers will be after `dt`. Called before each world step.
pub fn refresh(sable: &mut SableSceneData, sim: &mut SimulationSceneData, dt: Real) {
    for (rim, anchor) in sable.ipl_rim_anchors.iter() {
        let Some(carrier) = sim.rigid_body_set.get(anchor.carrier) else {
            continue; // carrier gone: the rim stays until Java removes it
        };
        let next = carrier.predict_position_using_velocity_and_forces(dt) * anchor.k;
        if let Some(body) = sim.rigid_body_set.get_mut(*rim) {
            body.set_next_kinematic_position(next);
        }
    }
    if !sable.ipl_image_anchors.is_empty() {
        for (handle, anchor) in sable.ipl_image_anchors.iter() {
            apply_image_anchor(sim, *handle, anchor);
        }
    }
    if sable.ipl_anchored_clip_bodies.is_empty() {
        return;
    }
    for id in sable.ipl_anchored_clip_bodies.iter() {
        let Some(info) = sable.level_colliders.get_mut(id) else {
            continue;
        };
        for region in info.clip_regions.iter_mut() {
            place_region(sim, region);
        }
        for regions in info.image_clip.values_mut() {
            for region in regions.iter_mut() {
                place_region(sim, region);
            }
        }
    }
}

fn place_region(sim: &SimulationSceneData, region: &mut IplClipRegion) {
    let Some(anchor) = region.anchor else {
        return;
    };
    let Some(carrier) = sim.rigid_body_set.get(anchor.carrier) else {
        return; // carrier gone: keep the last placement until Java retracts the session
    };
    let pose = carrier.position();
    region.point = pose.transform_point(anchor.point);
    region.normal = pose.transform_vector(anchor.normal);
    region.axis_w = pose.transform_vector(anchor.axis_w);
    region.axis_h = pose.transform_vector(anchor.axis_h);
}

fn apply_image_anchor(sim: &mut SimulationSceneData, handle: ColliderHandle, anchor: &ImageAnchor) {
    let Some(carrier) = sim.rigid_body_set.get(anchor.carrier) else {
        return;
    };
    let body = *carrier.position();
    let linvel = carrier.linvel();
    let angvel = carrier.angvel();
    let com = carrier.center_of_mass();

    let (prefix, frame) = match anchor.mode {
        AnchorMode::CarrierIsSource => {
            // q = P x with P = k ∘ B⁻¹: a fixed x moves through the image as the carrier moves,
            // u(q) = −R_P (v + ω × (x − c)) = −R_P v − (R_P ω) × (q − P c)
            let prefix = anchor.k * body.inverse();
            let frame = PortalFrameVelocity {
                linvel: -(prefix.rotation * linvel),
                angvel: -(prefix.rotation * angvel),
                center: prefix.transform_point(com),
            };
            (prefix, frame)
        }
        AnchorMode::CarrierIsDestination => {
            // q = B (k x): image points move with the carrier, u(q) = v + ω × (q − c)
            let prefix = body * anchor.k;
            let frame = PortalFrameVelocity { linvel, angvel, center: com };
            (prefix, frame)
        }
        AnchorMode::BetweenCarriers(destination) => {
            // q = D (k (S⁻¹ x)): the sum of both fields above, the source's mapped through P and the destination's own,
            // u(q) = v_D + ω_D × (q − c_D) − R_P v_S − (R_P ω_S) × (q − P c_S), one rigid field re-centred on c_D.
            // With one carrier at both ends the two reads are of the same body (copies, no second borrow).
            let Some(dest) = sim.rigid_body_set.get(destination) else {
                return;
            };
            let dest_body = *dest.position();
            let dest_linvel = dest.linvel();
            let dest_angvel = dest.angvel();
            let dest_com = dest.center_of_mass();
            let prefix = dest_body * anchor.k * body.inverse();
            let source_angvel = prefix.rotation * angvel;
            let frame = PortalFrameVelocity {
                linvel: dest_linvel - prefix.rotation * linvel - source_angvel.cross(dest_com - prefix.transform_point(com)),
                angvel: dest_angvel - source_angvel,
                center: dest_com,
            };
            (prefix, frame)
        }
    };

    let Some(collider) = sim.collider_set.get(handle) else {
        return;
    };
    let Some(parent) = collider.parent() else {
        return;
    };
    let Some(parent_pose) = sim.rigid_body_set.get(parent).map(|b| *b.position()) else {
        return;
    };
    let collider = sim.collider_set.get_mut(handle).unwrap();
    collider.set_portal_prefix(Some(prefix));
    collider.refresh_portal_prefixed_pose(&parent_pose);
    collider.set_portal_frame_velocity(Some(frame));
}

fn unpack(packed_handle: jlong) -> ColliderHandle {
    ColliderHandle::from_raw_parts((packed_handle >> 32) as u32, (packed_handle & 0xFFFF_FFFF) as u32)
}

/// Anchors an image collider to the carrier body `carrier_id` (`mode` 0: the carrier is the source, 1: the carrier is
/// the destination; `k` as above), or with a negative `carrier_id` drops the anchoring (the image keeps its last prefix
/// and stops moving).
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_setImageAnchor<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    packed_handle: jlong,
    carrier_id: jint,
    mode: jint,
    tx: jdouble,
    ty: jdouble,
    tz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
) {
    if scene_handle == 0 || packed_handle < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let sim_data = &mut *sim_data;
    let handle = unpack(packed_handle);

    let carrier = if carrier_id < 0 {
        None
    } else {
        sable_data.rigid_bodies.get(&(carrier_id as LevelColliderID)).copied()
    };
    let Some(carrier) = carrier else {
        sable_data.ipl_image_anchors.remove(&handle);
        if let Some(collider) = sim_data.collider_set.get_mut(handle) {
            collider.set_portal_frame_velocity(None);
        }
        return;
    };
    let anchor = ImageAnchor {
        carrier,
        mode: if mode == 1 { AnchorMode::CarrierIsDestination } else { AnchorMode::CarrierIsSource },
        k: Pose3::from_parts(
            Vec3::new(tx as Real, ty as Real, tz as Real),
            Quat::from_xyzw(qx as Real, qy as Real, qz as Real, qw as Real).normalize(),
        ),
    };
    apply_image_anchor(sim_data, handle, &anchor);
    sable_data.ipl_image_anchors.insert(handle, anchor);
}

/// Anchors an image collider to two carriers: `source_id` carries the source side, `dest_id` the destination side
/// (both may be one body), `k` maps the source carrier's body frame into the destination carrier's. A negative id drops
/// the anchoring, as for `setImageAnchor`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_setImageAnchorBetween<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    packed_handle: jlong,
    source_id: jint,
    dest_id: jint,
    tx: jdouble,
    ty: jdouble,
    tz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
) {
    if scene_handle == 0 || packed_handle < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let sim_data = &mut *sim_data;
    let handle = unpack(packed_handle);

    let body = |id: jint| if id < 0 { None } else { sable_data.rigid_bodies.get(&(id as LevelColliderID)).copied() };
    let (Some(source), Some(dest)) = (body(source_id), body(dest_id)) else {
        sable_data.ipl_image_anchors.remove(&handle);
        if let Some(collider) = sim_data.collider_set.get_mut(handle) {
            collider.set_portal_frame_velocity(None);
        }
        return;
    };
    let anchor = ImageAnchor {
        carrier: source,
        mode: AnchorMode::BetweenCarriers(dest),
        k: Pose3::from_parts(
            Vec3::new(tx as Real, ty as Real, tz as Real),
            Quat::from_xyzw(qx as Real, qy as Real, qz as Real, qw as Real).normalize(),
        ),
    };
    apply_image_anchor(sim_data, handle, &anchor);
    sable_data.ipl_image_anchors.insert(handle, anchor);
}

/// Sets the clip regions of body `body_id` (`packed_handle` < 0) or of one of its image colliders, in the body frame
/// of the carrier `carrier_id`; they follow the carrier from then on. Same layout as `setClipRegions`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_setAnchoredClipRegions<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    packed_handle: jlong,
    carrier_id: jint,
    data: JDoubleArray<'local>,
) {
    if scene_handle == 0 || body_id < 0 || carrier_id < 0 {
        return;
    }
    let Some(mut regions) = read_clip_regions(&env, &data) else {
        return;
    };
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let sim_data = scene.sim_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(carrier) = sable_data.rigid_bodies.get(&(carrier_id as LevelColliderID)).copied() else {
        return;
    };
    for region in regions.iter_mut() {
        region.anchor = Some(ClipAnchor {
            carrier,
            point: region.point,
            normal: region.normal,
            axis_w: region.axis_w,
            axis_h: region.axis_h,
        });
        place_region(&sim_data, region);
    }
    let id = body_id as LevelColliderID;
    let Some(info) = sable_data.level_colliders.get_mut(&id) else {
        return;
    };
    if packed_handle < 0 {
        info.clip_regions = regions;
    } else if regions.is_empty() {
        info.image_clip.remove(&unpack(packed_handle));
    } else {
        info.image_clip.insert(unpack(packed_handle), regions);
    }
    sable_data.ipl_anchored_clip_bodies.insert(id);
}

/// Drops the image anchors of a removed image collider.
pub fn forget_image(sable: &mut SableSceneData, handle: ColliderHandle) {
    sable.ipl_image_anchors.remove(&handle);
}

/// Anchors the portal rim `rim_id` to the carrier body `carrier_id` with `k` (the rim's pose in the carrier's body
/// frame) and puts it there now, or with a negative `carrier_id` drops the anchoring (the rim stays where it is).
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_setPortalRimAnchor<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    rim_id: jint,
    carrier_id: jint,
    tx: jdouble,
    ty: jdouble,
    tz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
) {
    if scene_handle == 0 {
        return;
    }
    let Some(rim) = crate::ipl_ext::portal_rim_body(scene_handle, rim_id) else {
        return;
    };
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let carrier = if carrier_id < 0 {
        None
    } else {
        sable_data.rigid_bodies.get(&(carrier_id as LevelColliderID)).copied()
    };
    let Some(carrier) = carrier else {
        sable_data.ipl_rim_anchors.remove(&rim);
        return;
    };
    let anchor = RimAnchor {
        carrier,
        k: Pose3::from_parts(
            Vec3::new(tx as Real, ty as Real, tz as Real),
            Quat::from_xyzw(qx as Real, qy as Real, qz as Real, qw as Real).normalize(),
        ),
    };
    let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(pose) = sim_data.rigid_body_set.get(carrier).map(|b| *b.position() * anchor.k) else {
        return;
    };
    if let Some(body) = sim_data.rigid_body_set.get_mut(rim) {
        body.set_position(pose, false);
        body.set_next_kinematic_position(pose);
    }
    sable_data.ipl_rim_anchors.insert(rim, anchor);
}

/// Drops the anchoring of a removed rim's body.
pub fn forget_rim(sable: &mut SableSceneData, rim: RigidBodyHandle) {
    sable.ipl_rim_anchors.remove(&rim);
}
