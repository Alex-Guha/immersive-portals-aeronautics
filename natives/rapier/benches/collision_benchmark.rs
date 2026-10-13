// From IPSable 0.5.0 by r2smith141 (https://github.com/r2smith141/IPSable), natives/rapier: Sable's sable_rapier
// crate by RyanHCode (https://github.com/ryanhcode/sable, 2.0.3) with IPSable's changes (Apache License 2.0).
// Sable's code is licensed under the PolyForm Shield License 1.0.0 (see natives/LICENSE.md), as are
// this project's changes to it. Rebased on Sable 2.0.6 and modified for Immersive Portals: Aeronautics, 2026:
// crate renamed to ipa_rapier.

use criterion::{Criterion, criterion_group, criterion_main};
use marten::Real;
use marten::octree::SubLevelOctree;
use rapier3d::glamx::{DVec3, IVec3};
use rapier3d::math::Pose3;
use rapier3d::prelude::ColliderHandle;
use ipa_rapier::ActiveLevelColliderInfo;
use ipa_rapier::algo::{DEFAULT_COLLISION_PARALLEL_CUTOFF, find_collision_pairs};
use std::hint::black_box;

fn setup_dummy_sable_handle_a() -> ActiveLevelColliderInfo {
    let mut octree = SubLevelOctree::new(7);
    setup_sphere(&mut octree);

    ActiveLevelColliderInfo {
        static_mount: None,
        collider: ColliderHandle::default(),
        local_bounds_min: Some(IVec3::new(0, 0, 0)),
        local_bounds_max: Some(IVec3::new(128, 128, 128)),
        center_of_mass: Some(DVec3::new(62.5, 62.5, 62.5)),
        octree: Some(octree),
        chunk_map: None,
        scene_id: 0,
        fake_velocities: None,
    }
}

fn setup_dummy_sable_handle_b() -> ActiveLevelColliderInfo {
    let mut octree = SubLevelOctree::new(7);
    setup_sphere(&mut octree);

    ActiveLevelColliderInfo {
        static_mount: None,
        collider: ColliderHandle::default(),
        local_bounds_min: Some(IVec3::new(128, 0, 0)),
        local_bounds_max: Some(IVec3::new(256, 128, 128)),
        center_of_mass: Some(DVec3::new(128.0 + 64.5, 64.5, 64.5)),
        octree: Some(octree),
        chunk_map: None,
        scene_id: 0,
        fake_velocities: None,
    }
}

fn setup_sphere(octree: &mut SubLevelOctree) {
    // sphere
    for x in 0..128 {
        for y in 0..128 {
            for z in 0..128 {
                let dx = x as f64 - 64.5;
                let dy = y as f64 - 64.5;
                let dz = z as f64 - 64.5;
                if dx * dx + dy * dy + dz * dz <= 63.5 * 63.5 {
                    octree.insert(x, y, z, 1);
                }
            }
        }
    }
}

fn benchmark_find_collision_pairs(c: &mut Criterion) {
    let sable_body_a = setup_dummy_sable_handle_a();
    let sable_body_b = setup_dummy_sable_handle_b();
    let prediction = 0.0 as Real;

    // for i in 0..100000 {
    //     let isometry = Isometry3::new(Vector3::new(123.5, 0.0, 0.0), Vector3::new(0.0, 0.0, 0.0)); // No rotation/translation
    //     let result = find_collision_pairs(&sable_body_a, Some(&sable_body_b), &isometry, prediction, 256);
    //     black_box(result);
    //
    // }
    //
    // if (true){ return;}

    // {
    //     let mut group = c.benchmark_group("find_pairs_parallel_cutoff");
    //
    //     for cutoff in [0, 64, 128, 256, 512, 1024, 2048, 4096].iter() {
    //         group.throughput(criterion::Throughput::Elements(1));
    //         group.bench_with_input(criterion::BenchmarkId::from_parameter(cutoff), cutoff, |b, &penetration| {
    //             let isometry = Isometry3::new(Vector3::new(120.0, 0.0, 0.0), Vector3::new(0.0, 0.0, 0.0)); // No rotation/translation
    //             b.iter(|| {
    //                 let result = find_collision_pairs(&sable_body_a, Some(&sable_body_b), &isometry, prediction, *cutoff as usize);
    //                 black_box(result)
    //             });
    //         });
    //     }
    // }
    //
    //
    // if (true){ return; }
    {
        let mut group = c.benchmark_group("find_pairs_parallel");

        for penetration in [0.0, 0.5, 1.0].iter() {
            group.throughput(criterion::Throughput::Elements(1));
            group.bench_with_input(
                criterion::BenchmarkId::from_parameter(penetration),
                penetration,
                |b, &penetration| {
                    let pose = Pose3::translation(124.25 - penetration * 8.0, 0.0, 0.0);
                    b.iter(|| {
                        let result = find_collision_pairs(
                            &sable_body_a,
                            Some(&sable_body_b),
                            &pose,
                            prediction,
                            DEFAULT_COLLISION_PARALLEL_CUTOFF,
                            false,
                        );
                        black_box(result)
                    });
                },
            );
        }
    }

    {
        let mut group = c.benchmark_group("find_pairs_sequential");

        for penetration in [0.0, 0.5, 1.0].iter() {
            group.throughput(criterion::Throughput::Elements(1));
            group.bench_with_input(
                criterion::BenchmarkId::from_parameter(penetration),
                penetration,
                |b, &penetration| {
                    let pose = Pose3::translation(124.25 - penetration * 8.0, 0.0, 0.0);
                    b.iter(|| {
                        let result = find_collision_pairs(
                            &sable_body_a,
                            Some(&sable_body_b),
                            &pose,
                            prediction,
                            usize::MAX,
                            false,
                        );
                        black_box(result)
                    });
                },
            );
        }
    }

    // criterion_group!(benches, benchmark_find_collision_pairs);
    // criterion_main!(benches);
    // c.bench_function("find_collision_pairs_parallel", |b| {
    //     b.iter(|| {
    //         let result = find_collision_pairs(&sable_body_a, Some(&sable_body_b), &isometry, prediction);
    //         black_box(result)
    //     })
    // });

    // c.bench_function("find_collision_pairs_sequential", |b| {
    //     b.iter(|| {
    //         let result = find_collision_pairs_sequential(&sable_body_a, Some(&sable_body_b), &isometry, prediction);
    //         black_box(result)
    //     })
    // });
}

criterion_group!(benches, benchmark_find_collision_pairs);
criterion_main!(benches);
