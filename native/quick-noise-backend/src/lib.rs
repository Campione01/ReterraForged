use std::cell::RefCell;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::slice;

use quick_noise::{BatchNoise, Fbm, Grid, Perlin};

pub const TILE_SIZE: usize = 32;
pub const TILE_SAMPLES: usize = TILE_SIZE * TILE_SIZE * TILE_SIZE;

const SUCCESS: i32 = 0;
const INVALID_ARGUMENT: i32 = 1;
const PANIC: i32 = 2;
const QUANTIZATION: f32 = 4096.0;
const ABI_VERSION: u32 = 1;

#[derive(Default)]
struct Scratch {
    cheese: Vec<f32>,
    spaghetti_a: Vec<f32>,
    spaghetti_b: Vec<f32>,
    noodle_a: Vec<f32>,
    noodle_b: Vec<f32>,
}

impl Scratch {
    fn resize(&mut self) {
        self.cheese.resize(TILE_SAMPLES, 0.0);
        self.spaghetti_a.resize(TILE_SAMPLES, 0.0);
        self.spaghetti_b.resize(TILE_SAMPLES, 0.0);
        self.noodle_a.resize(TILE_SAMPLES, 0.0);
        self.noodle_b.resize(TILE_SAMPLES, 0.0);
    }
}

thread_local! {
    static SCRATCH: RefCell<Scratch> = RefCell::new(Scratch::default());
}

pub fn fill_cave_tile_v1(
    seed: i64,
    tile_x: i32,
    tile_y: i32,
    tile_z: i32,
    chamber_bias: f32,
    spaghetti_width: f32,
    noodle_width: f32,
    output: &mut [f32],
) {
    assert_eq!(output.len(), TILE_SAMPLES);

    SCRATCH.with_borrow_mut(|scratch| {
        scratch.resize();
        let grid =
            Grid::<3>::new(TILE_SIZE, TILE_SIZE, TILE_SIZE).grid_position(tile_x, tile_y, tile_z);

        fill_field(
            &grid,
            seed,
            0x4348_4545_5345,
            4,
            1.0 / 24.0,
            &mut scratch.cheese,
        );
        fill_field(
            &grid,
            seed,
            0x5350_4147_4845_41,
            3,
            1.0 / 14.0,
            &mut scratch.spaghetti_a,
        );
        fill_field(
            &grid,
            seed,
            0x5350_4147_4845_42,
            3,
            1.0 / 14.0,
            &mut scratch.spaghetti_b,
        );
        fill_field(
            &grid,
            seed,
            0x4E4F_4F44_4C45_41,
            2,
            1.0 / 28.0,
            &mut scratch.noodle_a,
        );
        fill_field(
            &grid,
            seed,
            0x4E4F_4F44_4C45_42,
            2,
            1.0 / 28.0,
            &mut scratch.noodle_b,
        );

        for (index, value) in output.iter_mut().enumerate() {
            let chamber = scratch.cheese[index] + chamber_bias;
            let spaghetti = (scratch.spaghetti_a[index].abs() - spaghetti_width)
                .max(scratch.spaghetti_b[index].abs() - spaghetti_width);
            let noodle = (scratch.noodle_a[index].abs() - noodle_width)
                .max(scratch.noodle_b[index].abs() - noodle_width);
            *value = quantize(chamber.min(spaghetti).min(noodle));
        }
    });
}

fn fill_field(
    grid: &Grid<3>,
    grid_seed: i64,
    noise_seed: i64,
    octaves: usize,
    frequency: f32,
    output: &mut [f32],
) {
    BatchNoise::<3, Fbm, Perlin>::builder(grid.x_iter(), grid.y_iter(), grid.z_iter())
        .seed_with_grid(grid_seed, noise_seed)
        .octaves(octaves)
        .frequency(frequency)
        .lacunarity(2.0)
        .persistence(0.5)
        .scaling(1.0, 2.0, 1.0)
        .fill(output);
}

fn quantize(value: f32) -> f32 {
    (value * QUANTIZATION).round() / QUANTIZATION
}

#[unsafe(no_mangle)]
pub extern "C" fn rtf_quick_noise_abi_version() -> u32 {
    ABI_VERSION
}

#[unsafe(no_mangle)]
pub extern "C" fn rtf_quick_noise_tile_samples() -> usize {
    TILE_SAMPLES
}

/// Fills one globally aligned QUICK_V1 cave tile in x-fastest order.
///
/// # Safety
/// `output` must point to at least `TILE_SAMPLES` writable `f32` values.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rtf_quick_noise_fill_cave_tile_v1(
    seed: i64,
    tile_x: i32,
    tile_y: i32,
    tile_z: i32,
    chamber_bias: f32,
    spaghetti_width: f32,
    noodle_width: f32,
    output: *mut f32,
) -> i32 {
    if output.is_null() {
        return INVALID_ARGUMENT;
    }

    let result = catch_unwind(AssertUnwindSafe(|| {
        let output = unsafe { slice::from_raw_parts_mut(output, TILE_SAMPLES) };
        fill_cave_tile_v1(
            seed,
            tile_x,
            tile_y,
            tile_z,
            chamber_bias,
            spaghetti_width,
            noodle_width,
            output,
        );
    }));
    if result.is_ok() { SUCCESS } else { PANIC }
}

#[cfg(test)]
mod tests {
    use std::thread;
    use std::time::Instant;

    use super::*;

    const CHAMBER_BIAS: f32 = 0.32;
    const SPAGHETTI_WIDTH: f32 = 0.085;
    const NOODLE_WIDTH: f32 = 0.055;

    fn fill(seed: i64, tile_x: i32, tile_y: i32, tile_z: i32, output: &mut [f32]) {
        fill_cave_tile_v1(
            seed,
            tile_x,
            tile_y,
            tile_z,
            CHAMBER_BIAS,
            SPAGHETTI_WIDTH,
            NOODLE_WIDTH,
            output,
        );
    }

    #[test]
    fn cave_tile_is_deterministic_and_seeded() {
        let mut first = vec![0.0; TILE_SAMPLES];
        let mut repeated = vec![0.0; TILE_SAMPLES];
        let mut other_seed = vec![0.0; TILE_SAMPLES];
        fill(0x5EED_1234, -2, -1, 3, &mut first);
        fill(0x5EED_1234, -2, -1, 3, &mut repeated);
        fill(0x5EED_1235, -2, -1, 3, &mut other_seed);

        assert_eq!(first, repeated);
        assert_ne!(first, other_seed);
        assert!(first.iter().all(|value| value.is_finite()));
        assert!(first.iter().any(|value| *value < 0.0));
        assert!(first.iter().any(|value| *value > 0.0));
    }

    #[test]
    fn cave_tiles_are_thread_safe() {
        let expected = thread::spawn(|| {
            let mut output = vec![0.0; TILE_SAMPLES];
            fill(91, 4, -2, -7, &mut output);
            output
        })
        .join()
        .unwrap();

        let workers: Vec<_> = (0..8)
            .map(|_| {
                thread::spawn(|| {
                    let mut output = vec![0.0; TILE_SAMPLES];
                    fill(91, 4, -2, -7, &mut output);
                    output
                })
            })
            .collect();
        for worker in workers {
            assert_eq!(expected, worker.join().unwrap());
        }
    }

    #[test]
    fn adjacent_tile_boundary_has_no_discontinuity_spike() {
        let mut left = vec![0.0; TILE_SAMPLES];
        let mut right = vec![0.0; TILE_SAMPLES];
        fill(1234, 0, 0, 0, &mut left);
        fill(1234, 1, 0, 0, &mut right);

        let mut max_boundary_delta = 0.0_f32;
        for z in 0..TILE_SIZE {
            for y in 0..TILE_SIZE {
                let left_index = (z * TILE_SIZE + y) * TILE_SIZE + TILE_SIZE - 1;
                let right_index = (z * TILE_SIZE + y) * TILE_SIZE;
                max_boundary_delta =
                    max_boundary_delta.max((left[left_index] - right[right_index]).abs());
            }
        }
        assert!(
            max_boundary_delta < 0.5,
            "boundary delta was {max_boundary_delta}"
        );
    }

    #[test]
    #[ignore = "run explicitly for local performance measurements"]
    fn benchmark_cave_tile_generation() {
        let mut output = vec![0.0; TILE_SAMPLES];
        for tile_x in 0..16 {
            fill(1234, tile_x, 0, 0, &mut output);
        }

        let iterations = 256;
        let start = Instant::now();
        for tile_x in 0..iterations {
            fill(1234, tile_x, -1, 7, &mut output);
        }
        let elapsed = start.elapsed();
        println!(
            "RTF_QUICK_NOISE_BENCH tiles={} samples={} total_ms={:.3} us_per_tile={:.3} million_samples_per_second={:.3}",
            iterations,
            iterations as usize * TILE_SAMPLES,
            elapsed.as_secs_f64() * 1000.0,
            elapsed.as_secs_f64() * 1_000_000.0 / iterations as f64,
            iterations as f64 * TILE_SAMPLES as f64 / elapsed.as_secs_f64() / 1_000_000.0,
        );
    }
}
