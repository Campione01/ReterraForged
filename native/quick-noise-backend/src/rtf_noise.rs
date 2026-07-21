#[path = "rtf_cell_2d.rs"]
mod rtf_cell_2d;

use super::ProgramNode;
use quick_noise::simd::{ArchSimd, SimdSliceIterExt};
use rtf_cell_2d::CELL_2D;

type FloatVector = ArchSimd<f32>;
type IntVector = ArchSimd<i32>;
type UIntVector = ArchSimd<u32>;

const GRAD_2D_X: [f32; 8] = [-1.0, 1.0, -1.0, 1.0, 0.0, -1.0, 0.0, 1.0];
const GRAD_2D_Z: [f32; 8] = [-1.0, -1.0, 1.0, 1.0, -1.0, 0.0, 1.0, 0.0];

const GRAD_2D_24_X: [f32; 32] = [
    0.13052619,
    0.38268343,
    0.6087614,
    0.6087614,
    0.7933533,
    0.9238795,
    0.9914449,
    0.9914449,
    0.9914449,
    0.9238795,
    0.7933533,
    0.7933533,
    0.6087614,
    0.38268343,
    0.13052619,
    0.13052619,
    -0.13052619,
    -0.38268343,
    -0.6087614,
    -0.6087614,
    -0.7933533,
    -0.9238795,
    -0.9914449,
    -0.9914449,
    -0.9914449,
    -0.9238795,
    -0.7933533,
    -0.7933533,
    -0.6087614,
    -0.38268343,
    -0.13052619,
    -0.13052619,
];

const GRAD_2D_24_Z: [f32; 32] = [
    0.9914449,
    0.9238795,
    0.7933533,
    0.7933533,
    0.6087614,
    0.38268343,
    0.13052619,
    0.13052619,
    -0.13052619,
    -0.38268343,
    -0.6087614,
    -0.6087614,
    -0.7933533,
    -0.9238795,
    -0.9914449,
    -0.9914449,
    -0.9914449,
    -0.9238795,
    -0.7933533,
    -0.7933533,
    -0.6087614,
    -0.38268343,
    -0.13052619,
    -0.13052619,
    0.13052619,
    0.38268343,
    0.6087614,
    0.6087614,
    0.7933533,
    0.9238795,
    0.9914449,
    0.9914449,
];

pub(super) fn fill_perlin(
    inputs: &[Vec<f32>],
    seed: i64,
    node: &ProgramNode,
    output: &mut [f32],
    gradients_24: bool,
) {
    let (xs, zs) = coordinates(inputs, node);
    let base_seed = combined_seed(seed, node.seed_offset);
    let frequency = node.params[0];
    let lacunarity = node.params[1];
    let gain = node.params[2];
    let min = node.params[3];
    let max = node.params[4];
    let interpolation = node.params[5] as i32;
    let frequency = FloatVector::splat(frequency);
    let lacunarity = FloatVector::splat(lacunarity);
    let gain_vector = FloatVector::splat(gain);
    for ((mut target, input_x), input_z) in output
        .simd_iter_mut()
        .zip(xs.simd_iter())
        .zip(zs.simd_iter())
    {
        let mut x = input_x * frequency;
        let mut z = input_z * frequency;
        let mut sum = FloatVector::zero();
        let mut amplitude = gain_vector;
        for octave in 0..node.input_a {
            let octave_seed = base_seed.wrapping_add(octave);
            let signal = perlin_sample_simd(x, z, octave_seed, interpolation, gradients_24);
            sum += signal * amplitude;
            x *= lacunarity;
            z *= lacunarity;
            amplitude *= gain_vector;
        }
        *target = map_clamped_simd(sum, min, max, max - min);
    }
}

pub(super) fn fill_simplex(
    inputs: &[Vec<f32>],
    seed: i64,
    node: &ProgramNode,
    output: &mut [f32],
    simplex2: bool,
) {
    let (xs, zs) = coordinates(inputs, node);
    let base_seed = combined_seed(seed, node.seed_offset);
    let frequency = node.params[0];
    let lacunarity = node.params[1];
    let gain = node.params[2];
    let min = node.params[3];
    let max = node.params[4];
    let scaler = if simplex2 {
        99.83685_f32
    } else {
        79.869484_f32
    };
    for ((target, &input_x), &input_z) in output.iter_mut().zip(xs).zip(zs) {
        let mut x = input_x * frequency;
        let mut z = input_z * frequency;
        let mut sum = 0.0_f32;
        let mut amplitude = 1.0_f32;
        for octave in 0..node.input_a {
            sum += simplex_sample(x, z, base_seed.wrapping_add(octave), scaler) * amplitude;
            x *= lacunarity;
            z *= lacunarity;
            amplitude *= gain;
        }
        *target = map_clamped(sum, min, max, max - min);
    }
}

pub(super) fn fill_perlin_ridge(
    inputs: &[Vec<f32>],
    seed: i64,
    node: &ProgramNode,
    output: &mut [f32],
    invert: bool,
) {
    let (xs, zs) = coordinates(inputs, node);
    let base_seed = combined_seed(seed, node.seed_offset);
    let frequency = node.params[0];
    let lacunarity = node.params[1];
    let gain = node.params[2];
    let min = node.params[3];
    let max = node.params[4];
    let interpolation = node.params[5] as i32;
    let frequency = FloatVector::splat(frequency);
    let lacunarity_vector = FloatVector::splat(lacunarity);
    let gain_vector = FloatVector::splat(gain);
    for ((mut target, input_x), input_z) in output
        .simd_iter_mut()
        .zip(xs.simd_iter())
        .zip(zs.simd_iter())
    {
        let mut x = input_x * frequency;
        let mut z = input_z * frequency;
        let mut amplitude = FloatVector::splat(2.0);
        let mut value = FloatVector::zero();
        let mut weight = FloatVector::splat(1.0);
        let mut spectral_frequency = 1.0_f32;
        for octave in 0..node.input_a {
            let mut signal =
                perlin_sample_simd(x, z, base_seed.wrapping_add(octave), interpolation, false);
            signal = FloatVector::splat(1.0) - signal.abs();
            signal *= signal;
            signal *= weight;
            weight = clamp_simd(signal * amplitude, 0.0, 1.0);
            let spectral_weight = (1.0_f64 / spectral_frequency as f64) as f32;
            value += signal * FloatVector::splat(spectral_weight);
            x *= lacunarity_vector;
            z *= lacunarity_vector;
            spectral_frequency *= lacunarity;
            amplitude *= gain_vector;
        }
        let mapped = map_clamped_simd(value, min, max, (max - min).abs());
        *target = if invert {
            FloatVector::splat(1.0) - mapped
        } else {
            mapped
        };
    }
}

pub(super) fn fill_simplex_ridge(
    inputs: &[Vec<f32>],
    seed: i64,
    node: &ProgramNode,
    output: &mut [f32],
) {
    let (xs, zs) = coordinates(inputs, node);
    let base_seed = combined_seed(seed, node.seed_offset);
    let frequency = node.params[0];
    let lacunarity = node.params[1];
    let gain = node.params[2];
    let min = node.params[3];
    let max = node.params[4];
    for ((target, &input_x), &input_z) in output.iter_mut().zip(xs).zip(zs) {
        let mut x = input_x * frequency;
        let mut z = input_z * frequency;
        let mut amplitude = 2.0_f32;
        let mut value = 0.0_f32;
        let mut weight = 1.0_f32;
        let mut spectral_frequency = 1.0_f32;
        for octave in 0..node.input_a {
            let mut signal =
                simplex_sample(x, z, base_seed.wrapping_add(octave), 99.83685_f32).abs();
            signal = 1.0 - signal;
            signal *= signal;
            signal *= weight;
            weight = clamp(signal * amplitude, 0.0, 1.0);
            let spectral_weight = (1.0_f64 / spectral_frequency as f64) as f32;
            value += signal * spectral_weight;
            x *= lacunarity;
            z *= lacunarity;
            spectral_frequency *= lacunarity;
            amplitude *= gain;
        }
        *target = map_clamped(value, min, max, (max - min).abs());
    }
}

pub(super) fn fill_cubic(inputs: &[Vec<f32>], seed: i64, node: &ProgramNode, output: &mut [f32]) {
    let (xs, zs) = coordinates(inputs, node);
    let base_seed = combined_seed(seed, node.seed_offset);
    let frequency = node.params[0];
    let lacunarity = node.params[1];
    let gain = node.params[2];
    let min = node.params[3];
    let max = node.params[4];
    for ((target, &input_x), &input_z) in output.iter_mut().zip(xs).zip(zs) {
        let mut x = input_x * frequency;
        let mut z = input_z * frequency;
        let mut sum = cubic_sample(x, z, base_seed);
        let mut amplifier = 1.0_f32;
        for octave in 1..node.input_a {
            x *= lacunarity;
            z *= lacunarity;
            amplifier *= gain;
            sum += cubic_sample(x, z, base_seed.wrapping_add(octave)) * amplifier;
        }
        *target = map_clamped(sum, min, max, max - min);
    }
}

pub(super) fn fill_white(inputs: &[Vec<f32>], seed: i64, node: &ProgramNode, output: &mut [f32]) {
    let (xs, zs) = coordinates(inputs, node);
    let seed = combined_seed(seed, node.seed_offset);
    let frequency = node.params[0];
    for ((target, &x), &z) in output.iter_mut().zip(xs).zip(zs) {
        *target = val_coord_2d(
            seed,
            legacy_round(x * frequency),
            legacy_round(z * frequency),
        )
        .abs();
    }
}

pub(super) fn fill_worley(inputs: &[Vec<f32>], seed: i64, node: &ProgramNode, output: &mut [f32]) {
    let cell_function = node.params[2] as i32;
    if cell_function == 2 {
        output.fill(0.0);
        return;
    }
    let (xs, zs) = coordinates(inputs, node);
    let seed = combined_seed(seed, node.seed_offset);
    let frequency = node.params[0];
    let displacement = node.params[1];
    let distance_function = node.params[3] as i32;
    let min = node.params[4];
    let max = node.params[5];
    for ((target, &input_x), &input_z) in output.iter_mut().zip(xs).zip(zs) {
        let x = input_x * frequency;
        let z = input_z * frequency;
        let xi = legacy_floor(x);
        let zi = legacy_floor(z);
        let mut nearest = f32::MAX;
        let mut cell_x = xi;
        let mut cell_z = zi;
        for dz in -1_i32..=1 {
            for dx in -1_i32..=1 {
                let cx = xi.wrapping_add(dx);
                let cz = zi.wrapping_add(dz);
                let vector = cell(seed, cx, cz);
                let delta_x = cx as f32 + vector[0] * displacement - x;
                let delta_z = cz as f32 + vector[1] * displacement - z;
                let distance = distance_2d(delta_x, delta_z, distance_function);
                if distance < nearest {
                    nearest = distance;
                    cell_x = cx;
                    cell_z = cz;
                }
            }
        }
        let value = val_coord_2d(seed, cell_x, cell_z);
        *target = map_clamped(value, min, max, max - min);
    }
}

pub(super) fn fill_worley_edge(
    inputs: &[Vec<f32>],
    seed: i64,
    node: &ProgramNode,
    output: &mut [f32],
) {
    let (xs, zs) = coordinates(inputs, node);
    let seed = combined_seed(seed, node.seed_offset);
    let frequency = node.params[0];
    let displacement = node.params[1];
    let edge_function = node.params[2] as i32;
    let distance_function = node.params[3] as i32;
    let min = node.params[4];
    let max = node.params[5];
    for ((target, &input_x), &input_z) in output.iter_mut().zip(xs).zip(zs) {
        let x = input_x * frequency;
        let z = input_z * frequency;
        let xi = legacy_floor(x);
        let zi = legacy_floor(z);
        let mut nearest_1 = f32::MAX;
        let mut nearest_2 = f32::MAX;
        for dz in -1_i32..=1 {
            for dx in -1_i32..=1 {
                let cx = xi.wrapping_add(dx);
                let cz = zi.wrapping_add(dz);
                let vector = cell(seed, cx, cz);
                let delta_x = cx as f32 + vector[0] * displacement - x;
                let delta_z = cz as f32 + vector[1] * displacement - z;
                let distance = distance_2d(delta_x, delta_z, distance_function);
                if distance < nearest_1 {
                    nearest_2 = nearest_1;
                    nearest_1 = distance;
                } else if distance < nearest_2 {
                    nearest_2 = distance;
                }
            }
        }
        let value = match edge_function {
            0 => nearest_2 - 1.0,
            1 => nearest_2 + nearest_1 - 1.0,
            2 => nearest_2 - nearest_1 - 1.0,
            3 => nearest_2 * nearest_1 - 1.0,
            4 => nearest_1 / nearest_2 - 1.0,
            _ => unreachable!("validated RTF edge function"),
        };
        *target = map_clamped(value, min, max, max - min);
    }
}

fn coordinates<'a>(inputs: &'a [Vec<f32>], node: &ProgramNode) -> (&'a [f32], &'a [f32]) {
    (
        &inputs[node.input_b as usize],
        &inputs[node.input_c as usize],
    )
}

fn combined_seed(seed: i64, offset: i64) -> i32 {
    (seed as i32).wrapping_add(offset as i32)
}

fn legacy_floor(value: f32) -> i32 {
    if value >= 0.0 {
        value as i32
    } else {
        (value as i32).wrapping_sub(1)
    }
}

fn legacy_round(value: f32) -> i32 {
    if value >= 0.0 {
        (value + 0.5) as i32
    } else {
        (value - 0.5) as i32
    }
}

fn clamp(value: f32, min: f32, max: f32) -> f32 {
    if value < min {
        min
    } else if value > max {
        max
    } else {
        value
    }
}

fn map_clamped(value: f32, min: f32, max: f32, range: f32) -> f32 {
    let difference = clamp(value, min, max) - min;
    if difference >= range {
        1.0
    } else {
        difference / range
    }
}

#[inline(always)]
fn clamp_simd(value: FloatVector, min: f32, max: f32) -> FloatVector {
    let min = FloatVector::splat(min);
    let max = FloatVector::splat(max);
    let clamped_min = value.simd_lt(min).select(min, value);
    clamped_min.simd_gt(max).select(max, clamped_min)
}

#[inline(always)]
fn map_clamped_simd(value: FloatVector, min: f32, max: f32, range: f32) -> FloatVector {
    let difference = clamp_simd(value, min, max) - FloatVector::splat(min);
    let range = FloatVector::splat(range);
    difference
        .simd_ge(range)
        .select(FloatVector::splat(1.0), difference / range)
}

#[cfg(test)]
fn interpolation(value: f32, kind: i32) -> f32 {
    match kind {
        0 => value,
        1 => value * value * (3.0 - 2.0 * value),
        2 => value * value * value * (value * (value * 6.0 - 15.0) + 10.0),
        _ => unreachable!("validated RTF interpolation"),
    }
}

#[inline(always)]
fn interpolation_simd(value: FloatVector, kind: i32) -> FloatVector {
    match kind {
        0 => value,
        1 => value * value * (FloatVector::splat(3.0) - FloatVector::splat(2.0) * value),
        2 => {
            value
                * value
                * value
                * (value * (value * FloatVector::splat(6.0) - FloatVector::splat(15.0))
                    + FloatVector::splat(10.0))
        }
        _ => unreachable!("validated RTF interpolation"),
    }
}

fn hash_2d(seed: i32, x: i32, z: i32) -> i32 {
    let mut hash = seed;
    hash ^= 1619_i32.wrapping_mul(x);
    hash ^= 31337_i32.wrapping_mul(z);
    hash = hash
        .wrapping_mul(hash)
        .wrapping_mul(hash)
        .wrapping_mul(60493);
    hash ^ (hash >> 13)
}

#[inline(always)]
fn legacy_floor_simd(value: FloatVector) -> IntVector {
    let truncated = value.cast_int_trunc();
    value
        .simd_ge(FloatVector::zero())
        .select(
            truncated.raw_cast(),
            (truncated - IntVector::splat(1)).raw_cast(),
        )
        .raw_cast()
}

#[inline(always)]
fn hash_2d_simd(seed: i32, x: IntVector, z: IntVector) -> IntVector {
    let mut hash = IntVector::splat(seed);
    hash ^= IntVector::splat(1619) * x;
    hash ^= IntVector::splat(31337) * z;
    hash = hash * hash * hash * IntVector::splat(60493);
    hash ^ (hash >> 13_usize)
}

fn val_coord_2d(seed: i32, x: i32, z: i32) -> f32 {
    let mut value = seed;
    value ^= 1619_i32.wrapping_mul(x);
    value ^= 31337_i32.wrapping_mul(z);
    value = value
        .wrapping_mul(value)
        .wrapping_mul(value)
        .wrapping_mul(60493);
    value as f32 / 2.14748365e9_f32
}

fn gradient(seed: i32, x: i32, z: i32, gradients_24: bool) -> [f32; 2] {
    let hash = hash_2d(seed, x, z);
    if gradients_24 {
        let selector = ((((hash & 0x3f_ffff) as f32) * 1.3333334_f32) as i32 & 0x1f) as usize;
        [GRAD_2D_24_X[selector], GRAD_2D_24_Z[selector]]
    } else {
        let selector = (hash & 0x7) as usize;
        [GRAD_2D_X[selector], GRAD_2D_Z[selector]]
    }
}

#[inline(always)]
fn gradient_simd(
    seed: i32,
    x: IntVector,
    z: IntVector,
    gradients_24: bool,
) -> (FloatVector, FloatVector) {
    let hash = hash_2d_simd(seed, x, z);
    let selector: UIntVector = if gradients_24 {
        (((hash & IntVector::splat(0x3f_ffff)).cast_float() * FloatVector::splat(1.3333334))
            .cast_int_trunc()
            & IntVector::splat(0x1f))
        .cast_unsigned()
    } else {
        (hash & IntVector::splat(0x7)).cast_unsigned()
    };
    if gradients_24 {
        (
            selector.gather(&GRAD_2D_24_X),
            selector.gather(&GRAD_2D_24_Z),
        )
    } else {
        (selector.gather(&GRAD_2D_X), selector.gather(&GRAD_2D_Z))
    }
}

fn grad_coord_2d(seed: i32, x: i32, z: i32, delta_x: f32, delta_z: f32, gradients_24: bool) -> f32 {
    let gradient = gradient(seed, x, z, gradients_24);
    delta_x * gradient[0] + delta_z * gradient[1]
}

#[inline(always)]
fn grad_coord_2d_simd(
    seed: i32,
    x: IntVector,
    z: IntVector,
    delta_x: FloatVector,
    delta_z: FloatVector,
    gradients_24: bool,
) -> FloatVector {
    let (gradient_x, gradient_z) = gradient_simd(seed, x, z, gradients_24);
    delta_x * gradient_x + delta_z * gradient_z
}

#[cfg(test)]
fn perlin_sample(x: f32, z: f32, seed: i32, interpolation_kind: i32, gradients_24: bool) -> f32 {
    let x0 = legacy_floor(x);
    let z0 = legacy_floor(z);
    let x1 = x0.wrapping_add(1);
    let z1 = z0.wrapping_add(1);
    let alpha_x = interpolation(x - x0 as f32, interpolation_kind);
    let alpha_z = interpolation(z - z0 as f32, interpolation_kind);
    let delta_x0 = x - x0 as f32;
    let delta_z0 = z - z0 as f32;
    let delta_x1 = delta_x0 - 1.0;
    let delta_z1 = delta_z0 - 1.0;
    let lower = lerp(
        grad_coord_2d(seed, x0, z0, delta_x0, delta_z0, gradients_24),
        grad_coord_2d(seed, x1, z0, delta_x1, delta_z0, gradients_24),
        alpha_x,
    );
    let upper = lerp(
        grad_coord_2d(seed, x0, z1, delta_x0, delta_z1, gradients_24),
        grad_coord_2d(seed, x1, z1, delta_x1, delta_z1, gradients_24),
        alpha_x,
    );
    lerp(lower, upper, alpha_z)
}

#[inline(always)]
fn perlin_sample_simd(
    x: FloatVector,
    z: FloatVector,
    seed: i32,
    interpolation_kind: i32,
    gradients_24: bool,
) -> FloatVector {
    let x0 = legacy_floor_simd(x);
    let z0 = legacy_floor_simd(z);
    let x1 = x0 + IntVector::splat(1);
    let z1 = z0 + IntVector::splat(1);
    let delta_x0 = x - x0.cast_float();
    let delta_z0 = z - z0.cast_float();
    let alpha_x = interpolation_simd(delta_x0, interpolation_kind);
    let alpha_z = interpolation_simd(delta_z0, interpolation_kind);
    let delta_x1 = delta_x0 - FloatVector::splat(1.0);
    let delta_z1 = delta_z0 - FloatVector::splat(1.0);
    let lower_start = grad_coord_2d_simd(seed, x0, z0, delta_x0, delta_z0, gradients_24);
    let lower_end = grad_coord_2d_simd(seed, x1, z0, delta_x1, delta_z0, gradients_24);
    let lower = lower_start + alpha_x * (lower_end - lower_start);
    let upper_start = grad_coord_2d_simd(seed, x0, z1, delta_x0, delta_z1, gradients_24);
    let upper_end = grad_coord_2d_simd(seed, x1, z1, delta_x1, delta_z1, gradients_24);
    let upper = upper_start + alpha_x * (upper_end - upper_start);
    lower + alpha_z * (upper - lower)
}

fn simplex_sample(x: f32, z: f32, seed: i32, scaler: f32) -> f32 {
    let skew = (x + z) * 0.36602542_f32;
    let i = legacy_floor(x + skew);
    let j = legacy_floor(z + skew);
    let unskew = i.wrapping_add(j) as f32 * 0.21132487_f32;
    let origin_x = i as f32 - unskew;
    let origin_z = j as f32 - unskew;
    let x0 = x - origin_x;
    let z0 = z - origin_z;
    let (i1, j1) = if x0 > z0 {
        (1_i32, 0_i32)
    } else {
        (0_i32, 1_i32)
    };
    let x1 = x0 - i1 as f32 + 0.21132487_f32;
    let z1 = z0 - j1 as f32 + 0.21132487_f32;
    let x2 = x0 - 1.0 + 0.42264974_f32;
    let z2 = z0 - 1.0 + 0.42264974_f32;
    let n0 = simplex_corner(seed, i, j, x0, z0);
    let n1 = simplex_corner(seed, i.wrapping_add(i1), j.wrapping_add(j1), x1, z1);
    let n2 = simplex_corner(seed, i.wrapping_add(1), j.wrapping_add(1), x2, z2);
    scaler * (n0 + n1 + n2)
}

fn simplex_corner(seed: i32, x_cell: i32, z_cell: i32, x: f32, z: f32) -> f32 {
    let mut attenuation = 0.5 - x * x - z * z;
    if attenuation < 0.0 {
        0.0
    } else {
        attenuation *= attenuation;
        attenuation * attenuation * grad_coord_2d(seed, x_cell, z_cell, x, z, true)
    }
}

fn cubic_sample(x: f32, z: f32, seed: i32) -> f32 {
    let x0 = legacy_floor(x);
    let z0 = legacy_floor(z);
    let x_minus = x0.wrapping_sub(1);
    let z_minus = z0.wrapping_sub(1);
    let x_plus = x0.wrapping_add(1);
    let z_plus = z0.wrapping_add(1);
    let x_far = x0.wrapping_add(2);
    let z_far = z0.wrapping_add(2);
    let alpha_x = x - x0 as f32;
    let alpha_z = z - z0 as f32;
    let row = |row_z| {
        cubic_lerp(
            val_coord_2d(seed, x_minus, row_z),
            val_coord_2d(seed, x0, row_z),
            val_coord_2d(seed, x_plus, row_z),
            val_coord_2d(seed, x_far, row_z),
            alpha_x,
        )
    };
    cubic_lerp(row(z_minus), row(z0), row(z_plus), row(z_far), alpha_z) * 0.44444445_f32
}

fn cubic_lerp(a: f32, b: f32, c: f32, d: f32, alpha: f32) -> f32 {
    let p = d - c - (a - b);
    alpha * alpha * alpha * p + alpha * alpha * (a - b - p) + alpha * (c - a) + b
}

fn cell(seed: i32, x: i32, z: i32) -> [f32; 2] {
    CELL_2D[(hash_2d(seed, x, z) & 0xff) as usize]
}

fn distance_2d(x: f32, z: f32, kind: i32) -> f32 {
    match kind {
        0 => x * x + z * z,
        1 => x.abs() + z.abs(),
        2 => x.abs() + z.abs() + (x * x + z * z),
        _ => unreachable!("validated RTF distance function"),
    }
}

#[cfg(test)]
fn lerp(lower: f32, upper: f32, alpha: f32) -> f32 {
    lower + alpha * (upper - lower)
}

pub(super) fn legacy_sin(radians: f32) -> f32 {
    const SIN_COUNT: f32 = 4096.0;
    const RAD_FULL: f32 = 6.2831855;
    let index = ((radians * (SIN_COUNT / RAD_FULL)) as i32 & 0x0fff) as usize;
    match index {
        0 => 0.0,
        1024 => 1.0,
        2048 => std::f64::consts::PI.sin() as f32,
        3072 => -1.0,
        _ => {
            let angle = ((index as f32 + 0.5) / SIN_COUNT) * RAD_FULL;
            (angle as f64).sin() as f32
        }
    }
}

pub(super) fn legacy_cos(radians: f32) -> f32 {
    legacy_sin(radians + 1.5708_f32)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn generated_cell_table_has_expected_endpoints() {
        assert_eq!(CELL_2D.len(), 256);
        assert_eq!(CELL_2D[0], [0.06864607, 0.62819433]);
        assert_eq!(CELL_2D[255], [0.06854904, 0.62786734]);
    }

    #[test]
    fn integer_hashing_wraps_like_java() {
        assert_eq!(hash_2d(17, -4096, 3072), 767_490_113);
        assert_eq!(val_coord_2d(17, -4096, 3072).to_bits(), 0x3eb6_fe57);
    }

    #[test]
    fn simd_perlin_preserves_scalar_operation_order() {
        let coordinates = [
            (-4096.25_f32, 3072.75_f32),
            (-1.0_f32, -1.0_f32),
            (-0.125_f32, 0.875_f32),
            (0.0_f32, 0.0_f32),
            (17.375_f32, -31.625_f32),
            (8191.5_f32, 2048.25_f32),
        ];
        for interpolation_kind in 0..=2 {
            for gradients_24 in [false, true] {
                for (x, z) in coordinates {
                    let expected =
                        perlin_sample(x, z, 0x5a17_39c1, interpolation_kind, gradients_24);
                    let actual = perlin_sample_simd(
                        FloatVector::splat(x),
                        FloatVector::splat(z),
                        0x5a17_39c1,
                        interpolation_kind,
                        gradients_24,
                    );
                    let mut lanes = vec![0.0_f32; FloatVector::LANES];
                    actual.copy_to_slice(&mut lanes);
                    assert_eq!(lanes[0].to_bits(), expected.to_bits());
                }
            }
        }
    }
}
