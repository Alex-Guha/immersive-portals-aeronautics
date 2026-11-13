// From IPSable 0.5.0 by r2smith141 (https://github.com/r2smith141/IPSable), natives/rapier: Sable's sable_rapier
// crate by RyanHCode (https://github.com/ryanhcode/sable, 2.0.3) with IPSable's changes (Apache License 2.0).
// Sable's code is licensed under the PolyForm Shield License 1.0.0 (see natives/LICENSE.md), as are
// this project's changes to it. Rebased on Sable 2.0.6 and modified for Immersive Portals: Aeronautics, 2026:
// JNI symbols renamed for ipaeronautics.rapier.RapierExt; portal rim shapes registered for the interior check;
// IPSable's parent frames, dormant bodies and joint-component query removed (never called).

//! IPSable extensions to the sable_rapier natives (portal-physics spec phase 4),
//! rebased onto sable 2.0.3 (scene handles are raw `Arc<PhysicsScene>` pointers; sable
//! data lives behind `RwLock`, so hook-time reads are properly synchronized).
//!
//! Exposed under the `ipl.sable.natives.IplRapierNatives` Java class — sable's own JNI
//! surface is untouched.

use jni::JNIEnv;
use jni::objects::{JClass, JDoubleArray};
use jni::sys::{jboolean, jdouble, jint, jlong};
use std::collections::HashSet;
use marten::Real;
use rapier3d::dynamics::RigidBodyBuilder;
use rapier3d::geometry::{ColliderBuilder, Shape, SharedShape};
use rapier3d::glamx::{Pose3, Quat};
use rapier3d::math::Vec3;
use rapier3d::pipeline::PairFilterContext;
use rapier3d::prelude::{ActiveHooks, RigidBodyHandle};

use crate::scene::{LevelColliderID, PhysicsScene};

/// Native portal rim: four directly-authored Rapier cuboids, deliberately outside the
/// aperture. This bypasses Sable's voxel/neighborhood and chunk-octree machinery, which
/// has block-sized broad-phase cells even when a baked block shape is thin.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_createPortalRim<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    hole_width: jdouble,
    hole_height: jdouble,
    width: jdouble,
    half_thickness: jdouble,
) -> jint {
    if scene_handle == 0 || hole_width <= 0.0 || hole_height <= 0.0 || width <= 0.0
        || half_thickness <= 0.0 {
        return -1;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let width = width as Real;
    let half_thickness = half_thickness as Real;
    let hole_width = hole_width as Real;
    let hole_height = hole_height as Real;
    let half_w = hole_width * 0.5;
    let half_h = hole_height * 0.5;

    let bars = [
        (Vec3::new(-half_w - width * 0.5, 0.0, 0.0), width * 0.5, half_thickness, half_h + width),
        (Vec3::new(half_w + width * 0.5, 0.0, 0.0), width * 0.5, half_thickness, half_h + width),
        (Vec3::new(0.0, 0.0, -half_h - width * 0.5), half_w, half_thickness, width * 0.5),
        (Vec3::new(0.0, 0.0, half_h + width * 0.5), half_w, half_thickness, width * 0.5),
    ];

    let mut sim = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let body = sim.rigid_body_set.insert(RigidBodyBuilder::kinematic_position_based());
    let crate::scene::SimulationSceneData { collider_set, rigid_body_set, .. } = &mut *sim;
    let mut shapes = Vec::with_capacity(bars.len());
    for (position, x, y, z) in bars {
        let shape = SharedShape::cuboid(x, y, z);
        shapes.push(shape_address(&*shape));
        let collider = ColliderBuilder::new(shape)
            .position(Pose3::from_translation(position))
            .friction(0.45)
            .active_hooks(ActiveHooks::FILTER_CONTACT_PAIRS | ActiveHooks::MODIFY_SOLVER_CONTACTS)
            .collision_groups(crate::groups::level_group(scene.chart))
            .build();
        collider_set.insert_with_parent(collider, body, rigid_body_set);
    }
    // Collider handles are generational; keep an opaque native-side id instead of exposing
    // them to Java as a body id. A kinematic body is unique to exactly one rim.
    let id = next_portal_rim_id();
    drop(sim);
    IPL_PORTAL_RIM_SHAPES.write().unwrap_or_else(std::sync::PoisonError::into_inner).extend(shapes.iter().copied());
    let mut rims = IPL_PORTAL_RIMS.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    rims.insert((scene_handle, id), PortalRim {
        body, exclusions: HashSet::new(), positioned: false, shapes,
    });
    id
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_setPortalRimTransform<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    rim_id: jint,
    x: jdouble, y: jdouble, z: jdouble,
    qx: jdouble, qy: jdouble, qz: jdouble, qw: jdouble,
) {
    let Some((body_handle, first_pose)) = IPL_PORTAL_RIMS.write().unwrap_or_else(std::sync::PoisonError::into_inner)
        .get_mut(&(scene_handle, rim_id))
        .map(|rim| {
            let first_pose = !rim.positioned;
            rim.positioned = true;
            (rim.body, first_pose)
        }) else {
        return;
    };
    if scene_handle == 0 { return; }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sim = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    if let Some(body) = sim.rigid_body_set.get_mut(body_handle) {
        let pose = Pose3 {
            translation: Vec3::new(x as Real, y as Real, z as Real),
            rotation: Quat::from_xyzw(qx as Real, qy as Real, qz as Real, qw as Real).normalize(),
        };
        // Spawn directly at the aperture; later updates use Rapier's next pose so a
        // moving portal remains a genuine kinematic obstacle with correct velocity.
        if first_pose {
            body.set_position(pose, false);
        }
        body.set_next_kinematic_position(pose);
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_removePortalRim<'local>(
    _env: JNIEnv<'local>, _class: JClass<'local>, scene_handle: jlong, rim_id: jint,
) {
    let Some(rim) = IPL_PORTAL_RIMS.write().unwrap_or_else(std::sync::PoisonError::into_inner).remove(&(scene_handle, rim_id)) else {
        return;
    };
    if scene_handle == 0 {
        forget_rim_shapes(&rim);
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    drop_rim(scene, rim);
}

/// Immersive Portals: Aeronautics: removes every rim of the scene `scene_handle` while that scene is still alive
/// (from `Rapier3D_dispose`, before the scene is dropped). Java forgets its rims once the scene is gone without
/// removing them, since the scene pointer is dangling by then, so without this the process-wide tables kept every
/// rim of every world played in the session: a stale rim's exclusions could drop a new rim's contacts with a ship
/// (body handles restart in a new world), and a reused shape address could get the rim interior check.
pub fn purge_scene_rims(scene_handle: jlong, scene: &PhysicsScene) {
    let rims: Vec<PortalRim> = {
        let mut table = IPL_PORTAL_RIMS.write().unwrap_or_else(std::sync::PoisonError::into_inner);
        let keys: Vec<(jlong, jint)> = table.keys().filter(|key| key.0 == scene_handle).copied().collect();
        keys.iter().filter_map(|key| table.remove(key)).collect()
    };
    for rim in rims {
        drop_rim(scene, rim);
    }
}

fn forget_rim_shapes(rim: &PortalRim) {
    let mut shapes = IPL_PORTAL_RIM_SHAPES.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    for shape in &rim.shapes {
        shapes.remove(shape);
    }
}

/// A rim already taken out of `IPL_PORTAL_RIMS`: its shapes, its anchor and its body (with its bars).
fn drop_rim(scene: &PhysicsScene, rim: PortalRim) {
    forget_rim_shapes(&rim);
    crate::moving_portals::forget_rim(
        &mut scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner), rim.body);
    let mut sim = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    // Removing the parent body removes its attached compound collider too. Destructure
    // the scene first so Rust can prove these mutable fields do not overlap.
    let crate::scene::SimulationSceneData {
        rigid_body_set, island_manager, collider_set, impulse_joint_set,
        multibody_joint_set, ..
    } = &mut *sim;
    rigid_body_set.remove(rim.body, island_manager, collider_set,
        impulse_joint_set, multibody_joint_set, true);
}

static IPL_PORTAL_RIMS: std::sync::LazyLock<std::sync::RwLock<std::collections::HashMap<
    (jlong, jint), PortalRim
>>> = std::sync::LazyLock::new(|| std::sync::RwLock::new(std::collections::HashMap::new()));
static IPL_PORTAL_RIM_IDS: std::sync::atomic::AtomicI32 = std::sync::atomic::AtomicI32::new(-2);

struct PortalRim {
    body: RigidBodyHandle,
    exclusions: HashSet<RigidBodyHandle>,
    positioned: bool,
    /// Immersive Portals: Aeronautics: the addresses of its bars' shapes (`IPL_PORTAL_RIM_SHAPES`).
    shapes: Vec<usize>,
}

/// Immersive Portals: Aeronautics: the shapes of every rim's bars, by address (a collider's shared shape never moves).
/// The dispatcher gives ship blocks against these Sable's interior check (`is_portal_rim_shape`).
static IPL_PORTAL_RIM_SHAPES: std::sync::LazyLock<std::sync::RwLock<HashSet<usize>>> =
    std::sync::LazyLock::new(|| std::sync::RwLock::new(HashSet::new()));

fn shape_address(shape: &dyn Shape) -> usize {
    shape as *const dyn Shape as *const () as usize
}

/// Immersive Portals: Aeronautics: whether `shape` is a bar of a portal rim. Ship blocks collide with the bars one by
/// one, without the neighbour check Sable gives voxel pairs: a ship lying on a rim sank in by the contact skin and its
/// blocks above the opening touched a bar's inner face edge-on, which held the ship like a wall.
pub fn is_portal_rim_shape(shape: &dyn Shape) -> bool {
    IPL_PORTAL_RIM_SHAPES.read().unwrap_or_else(std::sync::PoisonError::into_inner).contains(&shape_address(shape))
}

/// The kinematic body of the rim `rim_id` in `scene_handle`'s scene.
pub fn portal_rim_body(scene_handle: jlong, rim_id: jint) -> Option<RigidBodyHandle> {
    IPL_PORTAL_RIMS.read().unwrap_or_else(std::sync::PoisonError::into_inner).get(&(scene_handle, rim_id)).map(|rim| rim.body)
}

fn next_portal_rim_id() -> jint {
    IPL_PORTAL_RIM_IDS.fetch_sub(1, std::sync::atomic::Ordering::Relaxed)
}

pub fn portal_rim_contact_allowed(context: &PairFilterContext) -> bool {
    let Some(body1) = context.rigid_body1 else { return true; };
    let Some(body2) = context.rigid_body2 else { return true; };
    IPL_PORTAL_RIMS.read().unwrap_or_else(std::sync::PoisonError::into_inner).values().all(|rim| {
        !((body1 == rim.body && rim.exclusions.contains(&body2))
            || (body2 == rim.body && rim.exclusions.contains(&body1)))
    })
}

fn set_portal_rim_exclusion(
    scene_handle: jlong, rim_id: jint, body_id: jint, excluded: jboolean,
) {
    if scene_handle == 0 || body_id < 0 { return; }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let Some(body) = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner).rigid_bodies
        .get(&(body_id as LevelColliderID)).copied() else { return; };
    let mut rims = IPL_PORTAL_RIMS.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(rim) = rims.get_mut(&(scene_handle, rim_id)) else { return; };
    if excluded != 0 { rim.exclusions.insert(body); } else { rim.exclusions.remove(&body); }
}

/// An oriented clip volume: solver contacts past the plane (signed distance >= 0 along
/// `normal`) and inside the lateral rectangle are dropped from the owning body's manifolds.
/// Infinite half extents remain available to callers, but Atlas keeps both real and image
/// clipping bounded to the portal aperture so terrain beside a frame remains solid.
#[derive(Debug, Clone)]
pub struct IplClipRegion {
    pub point: Vec3,
    pub normal: Vec3,
    pub axis_w: Vec3,
    pub half_w: Real,
    pub axis_h: Vec3,
    pub half_h: Real,
    /// Tolerance BEFORE the plane. A contact whose point sits a hair on the near side of
    /// the cut while its geometry is already past it used to survive, which is what a
    /// player still "feels" when entering the doorway from the opposite side. The band is
    /// tiny (sub-millimetre by default) and does not widen the cut anywhere else.
    pub skin: Real,
    /// Depth bound BEYOND the plane. Previously the region was an infinite half-space, so
    /// it could reach arbitrarily far past the portal and neutralize contacts that had
    /// nothing to do with this crossing -- the "microscopically affects other sub-levels"
    /// symptom. Java sends the owning body's own diagonal, so the cutter is a closed box
    /// around exactly the volume that can be straddling.
    pub max_depth: Real,
    /// Immersive Portals: Aeronautics, moving portals: a region at a portal mounted on a body follows that body
    /// (`crate::moving_portals`); None for still portals.
    pub anchor: Option<ClipAnchor>,
}

/// The placement of a clip region in its carrier's body frame (see `IplClipRegion::anchor`).
#[derive(Debug, Clone, Copy)]
pub struct ClipAnchor {
    pub carrier: RigidBodyHandle,
    pub point: Vec3,
    pub normal: Vec3,
    pub axis_w: Vec3,
    pub axis_h: Vec3,
}

/// Reads a clip-region array (`setClipRegions` layout); None when the array cannot be read.
pub fn read_clip_regions(env: &JNIEnv, data: &JDoubleArray) -> Option<Vec<IplClipRegion>> {
    let len = env.get_array_length(data).ok()? as usize;
    let mut values = vec![0.0f64; len];
    if len > 0 && env.get_double_array_region(data, 0, &mut values).is_err() {
        return None;
    }
    let mut regions = Vec::with_capacity(values.len() / IPL_CLIP_STRIDE);
    if values.is_empty() {
        return Some(regions);
    }
    for c in values.chunks_exact(IPL_CLIP_STRIDE) {
        regions.push(IplClipRegion {
            point: Vec3::new(c[0] as Real, c[1] as Real, c[2] as Real),
            normal: Vec3::new(c[3] as Real, c[4] as Real, c[5] as Real),
            axis_w: Vec3::new(c[6] as Real, c[7] as Real, c[8] as Real),
            half_w: c[9] as Real,
            axis_h: Vec3::new(c[10] as Real, c[11] as Real, c[12] as Real),
            half_h: c[13] as Real,
            skin: c[14] as Real,
            max_depth: c[15] as Real,
            anchor: None,
        });
    }
    Some(regions)
}

/// Clip-region stride (`RapierExt.CLIP_REGION_DOUBLES`; Java and this library ship in one jar):
/// `[px py pz  nx ny nz  wx wy wz halfW  hx hy hz halfH  skin maxDepth]`.
const IPL_CLIP_STRIDE: usize = 16;

impl IplClipRegion {
    #[inline]
    pub fn contains(&self, p: Vec3) -> bool {
        let rel = p - self.point;
        let depth = rel.dot(self.normal);
        depth >= -self.skin
            && depth <= self.max_depth
            && rel.dot(self.axis_w).abs() <= self.half_w
            && rel.dot(self.axis_h).abs() <= self.half_h
    }
}

/// Set (or clear, with an empty array) the clip regions of a body.
/// Layout: N regions x `IPL_CLIP_STRIDE` doubles.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_setClipRegions<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    data: JDoubleArray<'local>,
) {
    if scene_handle == 0 {
        return;
    }
    let len = match env.get_array_length(&data) {
        Ok(l) => l as usize,
        Err(_) => return,
    };
    let mut values = vec![0.0f64; len];
    if len > 0 && env.get_double_array_region(&data, 0, &mut values).is_err() {
        return;
    }

    // Same handle-deref pattern as the upstream natives (with_handle).
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    else {
        // Diagnostic (stderr -> launcher log): a silent miss here means Java is
        // clipping a body id / scene pair that native doesn't know.
        eprintln!(
            "[ipl-natives] setClipRegions MISS: body {body_id} not in scene {scene_handle:x}"
        );
        return; // body already gone — nothing to clip
    };

    info.clip_regions.clear();
    for c in values.chunks_exact(IPL_CLIP_STRIDE) {
        info.clip_regions.push(IplClipRegion {
            point: Vec3::new(c[0] as Real, c[1] as Real, c[2] as Real),
            normal: Vec3::new(c[3] as Real, c[4] as Real, c[5] as Real),
            axis_w: Vec3::new(c[6] as Real, c[7] as Real, c[8] as Real),
            half_w: c[9] as Real,
            axis_h: Vec3::new(c[10] as Real, c[11] as Real, c[12] as Real),
            half_h: c[13] as Real,
            skin: c[14] as Real,
            max_depth: c[15] as Real,
            anchor: None,
        });
    }
    // No success log: straddle sessions re-push regions per tick — an unconditional
    // stderr write here is a server-thread tick cost. The MISS branch above stays.
}

/// Register (`excluded != 0`) or clear a contact exclusion between a portal rim (a negative
/// id, either argument) and a body, in the rim's own exclusion set (`portal_rim_contact_allowed`).
/// Portal rims use this to exclude their carrier and ships let through. Idempotent, no-op on
/// a null scene or an unknown rim.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_setBodyPairExclusion<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    id_a: jint,
    id_b: jint,
    excluded: jboolean,
) {
    if id_a < 0 {
        set_portal_rim_exclusion(scene_handle, id_a, id_b, excluded);
        return;
    }
    if id_b < 0 {
        set_portal_rim_exclusion(scene_handle, id_b, id_a, excluded);
    }
    // Only portal rims (negative ids) exclude pairs; two ship ids have nothing to do.
}

// ---------------------------------------------------------------------------
// Atlas M2 (spec v3 §2.2): image colliders — the body's geometry projected into
// a far chart through a translation-only portal isometry (Tier 1). Contacts on
// an image act on the parent body EXACTLY via the engine's mapped-COM lever
// arms; there is no clone body, no servo, no feedback.
// ---------------------------------------------------------------------------

/// Create an image collider for `body_id` in the CALLING view's chart, with the
/// portal isometry `P = (R, t)`: translation `(dx, dy, dz)` and rotation quat
/// `(qx, qy, qz, qw)` (identity for translation-only portals — Tier 1). The
/// prefix maps the body's pose into the far chart. Returns the packed collider
/// handle (index << 32 | generation), or -1 if the body is unknown.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_createImageCollider<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
) -> jlong {
    // Immersive Portals: Aeronautics: a panic here crossed the JNI boundary and killed the game (user, 2026-11-10,
    // after rejoining a world). Caught, it goes to Java as a RuntimeException with the scene's state, and the session
    // is refused.
    create_image_collider_caught(&mut env, scene_handle, body_id, dx, dy, dz, qx, qy, qz, qw, 1.0)
}

/// Immersive Portals: Aeronautics, scaled portals: `createImageCollider` through a portal of scale `k`, whose map is
/// `S(x) = k R x + t` (`(dx, dy, dz)` = t). The image is k times its body: it collides at that size, and its contacts
/// act on the body as through the similarity (engine: `ColliderParent::portal_scale`). `k = 1` is
/// `createImageCollider`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_createScaledImageCollider<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
    k: jdouble,
) -> jlong {
    if !(k.is_finite() && k > 0.0) {
        let _ = env.throw_new("java/lang/IllegalArgumentException", format!("image scale {k}"));
        return -1;
    }
    create_image_collider_caught(&mut env, scene_handle, body_id, dx, dy, dz, qx, qy, qz, qw, k)
}

#[allow(clippy::too_many_arguments)]
fn create_image_collider_caught(
    env: &mut JNIEnv,
    scene_handle: jlong,
    body_id: jint,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
    k: jdouble,
) -> jlong {
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
        create_image_collider(scene_handle, body_id, dx, dy, dz, qx, qy, qz, qw, k)
    }));
    match result {
        Ok(handle) => handle,
        Err(payload) => {
            let msg = format!(
                "Rapier native panic in createImageCollider: {} [{}]",
                crate::panic_message(&payload),
                describe_scene(scene_handle, Some(body_id))
            );
            let _ = env.throw_new("java/lang/RuntimeException", &msg);
            -1
        }
    }
}

/// Immersive Portals: Aeronautics: a scene's chart and the shared world's counts, and what the world knows of
/// `body_id`, for the message of a caught panic. Takes the locks itself (after a caught panic they are released, possibly poisoned).
pub(crate) fn describe_scene(scene_handle: jlong, body_id: Option<jint>) -> String {
    if scene_handle == 0 {
        return "no scene".to_string();
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let sable_data = scene.sable_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
    let sim_data = scene.sim_data.read().unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut out = format!(
        "chart {} of {} charts made in this native world, {} bodies, {} colliders, {} Sable bodies by id, {} charts live",
        scene.chart,
        scene.world.next_chart.load(std::sync::atomic::Ordering::Relaxed),
        sim_data.rigid_body_set.len(),
        sim_data.collider_set.len(),
        sable_data.rigid_bodies.len(),
        sable_data.charts.len()
    );
    if let Some(id) = body_id {
        let key = id as LevelColliderID;
        match sable_data.rigid_bodies.get(&key) {
            Some(handle) => out.push_str(&format!(
                "; body {id}: {:?}, in the body set {}",
                handle,
                sim_data.rigid_body_set.contains(*handle)
            )),
            None => out.push_str(&format!("; body {id}: not in the id map")),
        }
        match sable_data.level_colliders.get(&key) {
            Some(info) => out.push_str(&format!(
                ", level collider {:?} in the collider set {}, {} images",
                info.collider,
                sim_data.collider_set.contains(info.collider),
                info.image_colliders.len()
            )),
            None => out.push_str(", no level collider info"),
        }
    }
    out
}

#[allow(clippy::too_many_arguments)]
fn create_image_collider(
    scene_handle: jlong,
    body_id: jint,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
    k: jdouble,
) -> jlong {
    use rapier3d::prelude::*;

    if scene_handle == 0 || body_id < 0 {
        return -1;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let sim_data = &mut *sim_data;

    let Some(body_handle) = sable_data.rigid_bodies.get(&(body_id as LevelColliderID)).copied() else {
        eprintln!("[ipl-natives] createImageCollider MISS: body {body_id} unknown");
        return -1;
    };
    let Some(info) = sable_data.level_colliders.get_mut(&(body_id as LevelColliderID)) else {
        eprintln!("[ipl-natives] createImageCollider MISS: body {body_id} unknown");
        return -1;
    };

    // Mirror the native collider's shape (same id → same info lookups in the
    // hooks/dispatcher), but tagged with the CALLING chart so it pairs with the
    // far chart's terrain and bodies.
    let native = sim_data
        .collider_set
        .get(info.collider)
        .and_then(|c| c.shape().as_shape::<crate::collider::LevelCollider>())
        .copied();
    let Some(native_shape) = native else {
        eprintln!("[ipl-natives] createImageCollider: body {body_id} has no LevelCollider shape");
        return -1;
    };
    let image_shape = crate::collider::LevelCollider {
        chart: scene.chart,
        scale: k as Real,
        ..native_shape
    };

    let prefix = rapier3d::math::Pose {
        translation: Vec3::new(dx as Real, dy as Real, dz as Real),
        rotation: rapier3d::math::Rotation::from_xyzw(
            qx as Real,
            qy as Real,
            qz as Real,
            qw as Real,
        )
        .normalize(),
    };
    let collider = ColliderBuilder::new(SharedShape::new(image_shape))
        .friction(0.525)
        .active_events(ActiveEvents::CONTACT_FORCE_EVENTS)
        .active_hooks(ActiveHooks::MODIFY_SOLVER_CONTACTS)
        .density(0.0)
        .collision_groups(crate::groups::image_group(scene.chart))
        .position(rapier3d::math::Pose::IDENTITY)
        .build();

    let handle =
        sim_data
            .collider_set
            .insert_with_parent(collider, body_handle, &mut sim_data.rigid_body_set);
    sim_data.collider_set.get_mut(handle).unwrap().set_portal_prefix(Some(prefix));
    if k != 1.0 {
        sim_data.collider_set.get_mut(handle).unwrap().set_portal_scale(k as Real);
    }
    let parent_pose = sim_data.rigid_body_set[body_handle].position().clone();
    sim_data.collider_set.get_mut(handle).unwrap()
        .refresh_portal_prefixed_pose(&parent_pose);
    // `insert_with_parent` composes before the Atlas prefix is attached. Recompose now so
    // this image never enters broad phase at identity and leaves a one-step real/image twin.

    info.image_colliders.push(handle);

    let (idx, generation) = handle.into_raw_parts();
    ((idx as jlong) << 32) | (generation as jlong)
}

/// Remove an image collider previously created by `createImageCollider`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_removeImageCollider<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    packed_handle: jlong,
) {
    use rapier3d::prelude::ColliderHandle;

    if scene_handle == 0 || packed_handle < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let sim_data = &mut *sim_data;

    let handle = ColliderHandle::from_raw_parts(
        (packed_handle >> 32) as u32,
        (packed_handle & 0xFFFF_FFFF) as u32,
    );

    if let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    {
        info.image_colliders.retain(|h| *h != handle);
        info.image_clip.remove(&handle);
    }
    crate::moving_portals::forget_image(&mut sable_data, handle);

    sim_data.collider_set.remove(
        handle,
        &mut sim_data.island_manager,
        &mut sim_data.rigid_body_set,
        true,
    );
}

/// Set (or clear, with an empty array) the clip regions of one IMAGE collider —
/// the far side of the half-open aperture seam. Layout matches `setClipRegions`
/// (N x `IPL_CLIP_STRIDE` doubles).
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_setImageClipRegions<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    body_id: jint,
    packed_handle: jlong,
    data: JDoubleArray<'local>,
) {
    use rapier3d::prelude::ColliderHandle;

    if scene_handle == 0 || packed_handle < 0 {
        return;
    }
    let len = match env.get_array_length(&data) {
        Ok(l) => l as usize,
        Err(_) => return,
    };
    let mut values = vec![0.0f64; len];
    if len > 0 && env.get_double_array_region(&data, 0, &mut values).is_err() {
        return;
    }

    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sable_data = scene.sable_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let Some(info) = sable_data
        .level_colliders
        .get_mut(&(body_id as LevelColliderID))
    else {
        return;
    };

    let handle = ColliderHandle::from_raw_parts(
        (packed_handle >> 32) as u32,
        (packed_handle & 0xFFFF_FFFF) as u32,
    );
    let mut regions = Vec::with_capacity(values.len() / IPL_CLIP_STRIDE);
    for c in values.chunks_exact(IPL_CLIP_STRIDE) {
        regions.push(IplClipRegion {
            point: Vec3::new(c[0] as Real, c[1] as Real, c[2] as Real),
            normal: Vec3::new(c[3] as Real, c[4] as Real, c[5] as Real),
            axis_w: Vec3::new(c[6] as Real, c[7] as Real, c[8] as Real),
            half_w: c[9] as Real,
            axis_h: Vec3::new(c[10] as Real, c[11] as Real, c[12] as Real),
            half_h: c[13] as Real,
            skin: c[14] as Real,
            max_depth: c[15] as Real,
            anchor: None,
        });
    }
    if regions.is_empty() {
        info.image_clip.remove(&handle);
    } else {
        info.image_clip.insert(handle, regions);
    }
}

/// Atlas M5 (spec v3 §2.8): update an image collider's portal prefix — moving
/// portals re-derive P = (R, t) per tick (animated portals, portals anchored to
/// physics structures). The engine recomposes the collider pose and re-runs
/// broad/narrow phase from the PARENT change flag.
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_setImagePrefix<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    packed_handle: jlong,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
) {
    use rapier3d::prelude::ColliderHandle;

    if scene_handle == 0 || packed_handle < 0 {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let handle = ColliderHandle::from_raw_parts(
        (packed_handle >> 32) as u32,
        (packed_handle & 0xFFFF_FFFF) as u32,
    );
    let Some(collider) = sim_data.collider_set.get_mut(handle) else {
        return; // image already retired — a moving-portal refresh racing session end
    };
    collider.set_portal_prefix(Some(rapier3d::math::Pose {
        translation: Vec3::new(dx as Real, dy as Real, dz as Real),
        rotation: rapier3d::math::Rotation::from_xyzw(
            qx as Real,
            qy as Real,
            qz as Real,
            qw as Real,
        )
        .normalize(),
    }));
}

/// Immersive Portals: Aeronautics, scaled portals: `setImagePrefix` with the portal's scale `k` (the map
/// `S(x) = k R x + t`): the image's shape and its contacts take the new scale too. Not for a moving portal's image
/// (its anchor composes rigid poses, `moving_portals.rs`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_ipaeronautics_rapier_RapierExt_setImagePrefixScaled<'local>(
    _env: JNIEnv<'local>,
    _class: JClass<'local>,
    scene_handle: jlong,
    packed_handle: jlong,
    dx: jdouble,
    dy: jdouble,
    dz: jdouble,
    qx: jdouble,
    qy: jdouble,
    qz: jdouble,
    qw: jdouble,
    k: jdouble,
) {
    use rapier3d::prelude::ColliderHandle;

    if scene_handle == 0 || packed_handle < 0 || !(k.is_finite() && k > 0.0) {
        return;
    }
    let scene = unsafe { &*(scene_handle as *const PhysicsScene) };
    let mut sim_data = scene.sim_data.write().unwrap_or_else(std::sync::PoisonError::into_inner);
    let handle = ColliderHandle::from_raw_parts(
        (packed_handle >> 32) as u32,
        (packed_handle & 0xFFFF_FFFF) as u32,
    );
    let Some(collider) = sim_data.collider_set.get_mut(handle) else {
        return; // image already retired
    };
    let Some(existing) = collider.shape().as_shape::<crate::collider::LevelCollider>().copied() else {
        return;
    };
    if existing.scale != k as Real {
        collider.set_shape(rapier3d::geometry::SharedShape::new(crate::collider::LevelCollider {
            scale: k as Real,
            ..existing
        }));
    }
    collider.set_portal_scale(k as Real);
    collider.set_portal_prefix(Some(rapier3d::math::Pose {
        translation: Vec3::new(dx as Real, dy as Real, dz as Real),
        rotation: rapier3d::math::Rotation::from_xyzw(
            qx as Real,
            qy as Real,
            qz as Real,
            qw as Real,
        )
        .normalize(),
    }));
}
