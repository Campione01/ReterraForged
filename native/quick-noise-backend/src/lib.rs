use std::cell::RefCell;
use std::collections::HashMap;
use std::panic::{AssertUnwindSafe, catch_unwind};
use std::slice;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::{Arc, OnceLock, RwLock};

use quick_noise::simd::SimdSliceIterExt;
use quick_noise::{BatchNoise, Billow, Cellular, Fbm, Grid, Perlin, Ridged, Simplex, Value};

mod rtf_noise;

pub const TILE_SIZE: usize = 32;
pub const TILE_SAMPLES: usize = TILE_SIZE * TILE_SIZE * TILE_SIZE;

const SUCCESS: i32 = 0;
const INVALID_ARGUMENT: i32 = 1;
const PANIC: i32 = 2;
const INVALID_PROGRAM: i32 = 3;
const QUANTIZATION: f32 = 4096.0;
const ABI_VERSION: u32 = 2;

const PROGRAM_MAGIC: u32 = u32::from_le_bytes(*b"QNV2");
const PROGRAM_VERSION: u32 = 6;
const PROGRAM_HEADER_BYTES: usize = 20;
const PROGRAM_NODE_BYTES: usize = 48;

const GRID_BACKEND_AVAILABLE: bool = cfg!(any(
    all(target_arch = "x86_64", target_feature = "sse4.2"),
    all(
        target_arch = "x86_64",
        target_feature = "avx2",
        target_feature = "fma"
    ),
    all(
        target_arch = "x86_64",
        target_feature = "avx512f",
        target_feature = "fma"
    ),
    all(target_arch = "aarch64", target_feature = "neon")
));

const OP_CONSTANT: u32 = 0;
const OP_PERLIN_FBM: u32 = 1;
const OP_VALUE_FBM: u32 = 2;
const OP_SIMPLEX_FBM: u32 = 3;
const OP_CELLULAR_FBM: u32 = 4;
const OP_PERLIN_BILLOW: u32 = 5;
const OP_PERLIN_RIDGED: u32 = 6;
const OP_SIMPLEX_RIDGED: u32 = 7;
const OP_RTF_PERLIN: u32 = 8;
const OP_RTF_PERLIN2: u32 = 9;
const OP_RTF_SIMPLEX: u32 = 10;
const OP_RTF_SIMPLEX2: u32 = 11;
const OP_RTF_PERLIN_RIDGE: u32 = 12;
const OP_RTF_SIMPLEX_RIDGE: u32 = 13;
const OP_RTF_BILLOW: u32 = 14;
const OP_RTF_CUBIC: u32 = 15;
const OP_ADD: u32 = 16;
const OP_MULTIPLY: u32 = 17;
const OP_MIN: u32 = 18;
const OP_MAX: u32 = 19;
const OP_ABS: u32 = 20;
const OP_CLAMP: u32 = 21;
const OP_MAP: u32 = 22;
const OP_INVERT: u32 = 23;
const OP_CURVE3: u32 = 24;
const OP_CURVE5: u32 = 25;
const OP_LERP: u32 = 26;
const OP_POW: u32 = 27;
const OP_GREATER: u32 = 28;
const OP_SIGNED_POW: u32 = 29;
const OP_BOOST: u32 = 30;
const OP_STEPS: u32 = 31;
const OP_COORD_X: u32 = 32;
const OP_COORD_Z: u32 = 33;
const OP_SIN: u32 = 34;
const OP_COS: u32 = 35;
const OP_DIVIDE: u32 = 36;
const OP_POW_DYNAMIC: u32 = 37;
const OP_ROUND: u32 = 38;
const OP_GREATER_EQUAL: u32 = 39;
const OP_RTF_WHITE: u32 = 40;
const OP_RTF_WORLEY: u32 = 41;
const OP_RTF_WORLEY_EDGE: u32 = 42;
const OP_RTF_SIN: u32 = 43;
const OP_RTF_COS: u32 = 44;
const OP_SUBTRACT: u32 = 45;
const OP_ALPHA: u32 = 46;
const OP_SIGNED_INT_POW: u32 = 47;
const OP_SELECT: u32 = 48;
const OP_RTF_PERLIN_FIXED: u32 = 49;
const OP_RTF_PERLIN2_FIXED: u32 = 50;

static NEXT_PROGRAM_HANDLE: AtomicU64 = AtomicU64::new(1);
static PROGRAMS: OnceLock<RwLock<HashMap<u64, Arc<Program>>>> = OnceLock::new();

#[derive(Default)]
struct Scratch {
    cheese: Vec<f32>,
    spaghetti_a: Vec<f32>,
    spaghetti_b: Vec<f32>,
    noodle_a: Vec<f32>,
    noodle_b: Vec<f32>,
}

#[derive(Clone, Debug)]
struct ProgramNode {
    opcode: u32,
    input_a: i32,
    input_b: i32,
    input_c: i32,
    seed_offset: i64,
    params: [f32; 6],
}

#[derive(Debug)]
struct Program {
    nodes: Vec<ProgramNode>,
    roots: Vec<usize>,
}

#[derive(Default)]
struct ProgramScratch {
    buffers: Vec<Vec<f32>>,
}

impl ProgramScratch {
    fn resize(&mut self, node_count: usize, sample_count: usize) {
        self.buffers.resize_with(node_count, Vec::new);
        for buffer in &mut self.buffers {
            buffer.resize(sample_count, 0.0);
        }
    }
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
    static PROGRAM_SCRATCH: RefCell<ProgramScratch> = RefCell::new(ProgramScratch::default());
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

fn programs() -> &'static RwLock<HashMap<u64, Arc<Program>>> {
    PROGRAMS.get_or_init(|| RwLock::new(HashMap::new()))
}

fn parse_program(bytes: &[u8]) -> Result<Program, ()> {
    if bytes.len() < PROGRAM_HEADER_BYTES {
        return Err(());
    }
    let mut offset = 0;
    let magic = read_u32(bytes, &mut offset)?;
    let version = read_u32(bytes, &mut offset)?;
    let node_count = read_u32(bytes, &mut offset)? as usize;
    let root_count = read_u32(bytes, &mut offset)? as usize;
    let node_bytes = read_u32(bytes, &mut offset)? as usize;
    if magic != PROGRAM_MAGIC
        || version != PROGRAM_VERSION
        || node_bytes != PROGRAM_NODE_BYTES
        || node_count == 0
        || node_count > 4096
        || root_count == 0
        || root_count > 64
    {
        return Err(());
    }
    let expected = PROGRAM_HEADER_BYTES
        .checked_add(node_count.checked_mul(PROGRAM_NODE_BYTES).ok_or(())?)
        .and_then(|length| length.checked_add(root_count.checked_mul(4)?))
        .ok_or(())?;
    if bytes.len() != expected {
        return Err(());
    }

    let mut nodes = Vec::with_capacity(node_count);
    for index in 0..node_count {
        let opcode = read_u32(bytes, &mut offset)?;
        let input_a = read_i32(bytes, &mut offset)?;
        let input_b = read_i32(bytes, &mut offset)?;
        let input_c = read_i32(bytes, &mut offset)?;
        let seed_offset = read_i64(bytes, &mut offset)?;
        let mut params = [0.0; 6];
        for value in &mut params {
            *value = read_f32(bytes, &mut offset)?;
            if !value.is_finite() {
                return Err(());
            }
        }
        validate_node(opcode, input_a, input_b, input_c, &params, index)?;
        if opcode == OP_STEPS && params[0] <= 0.0 {
            return Err(());
        }
        nodes.push(ProgramNode {
            opcode,
            input_a,
            input_b,
            input_c,
            seed_offset,
            params,
        });
    }

    let mut roots = Vec::with_capacity(root_count);
    for _ in 0..root_count {
        let root = read_u32(bytes, &mut offset)? as usize;
        if root >= node_count {
            return Err(());
        }
        roots.push(root);
    }
    Ok(Program { nodes, roots })
}

fn validate_node(
    opcode: u32,
    input_a: i32,
    input_b: i32,
    input_c: i32,
    params: &[f32; 6],
    index: usize,
) -> Result<(), ()> {
    let valid_input = |input: i32| input >= 0 && (input as usize) < index;
    let valid_code = |value: f32, min: i32, max: i32| {
        value == value.trunc() && value >= min as f32 && value <= max as f32
    };
    match opcode {
        OP_CONSTANT | OP_COORD_X | OP_COORD_Z => Ok(()),
        OP_PERLIN_FBM | OP_VALUE_FBM | OP_SIMPLEX_FBM | OP_CELLULAR_FBM | OP_PERLIN_BILLOW
        | OP_PERLIN_RIDGED | OP_SIMPLEX_RIDGED => {
            let coordinates_are_valid =
                (input_b == -1 && input_c == -1) || (valid_input(input_b) && valid_input(input_c));
            if (1..=32).contains(&input_a) && coordinates_are_valid {
                Ok(())
            } else {
                Err(())
            }
        }
        OP_RTF_PERLIN | OP_RTF_PERLIN2 | OP_RTF_PERLIN_RIDGE | OP_RTF_BILLOW
        | OP_RTF_PERLIN_FIXED | OP_RTF_PERLIN2_FIXED => {
            if (1..=32).contains(&input_a)
                && valid_input(input_b)
                && valid_input(input_c)
                && valid_code(params[5], 0, 2)
            {
                Ok(())
            } else {
                Err(())
            }
        }
        OP_RTF_SIMPLEX | OP_RTF_SIMPLEX2 | OP_RTF_SIMPLEX_RIDGE | OP_RTF_CUBIC => {
            if (1..=32).contains(&input_a) && valid_input(input_b) && valid_input(input_c) {
                Ok(())
            } else {
                Err(())
            }
        }
        OP_RTF_WHITE => {
            if input_a == 1 && valid_input(input_b) && valid_input(input_c) {
                Ok(())
            } else {
                Err(())
            }
        }
        OP_RTF_WORLEY => {
            if input_a == 1
                && valid_input(input_b)
                && valid_input(input_c)
                && matches!(params[2] as i32, 0 | 2)
                && valid_code(params[2], 0, 2)
                && valid_code(params[3], 0, 2)
            {
                Ok(())
            } else {
                Err(())
            }
        }
        OP_RTF_WORLEY_EDGE => {
            if input_a == 1
                && valid_input(input_b)
                && valid_input(input_c)
                && valid_code(params[2], 0, 4)
                && valid_code(params[3], 0, 2)
            {
                Ok(())
            } else {
                Err(())
            }
        }
        OP_ADD | OP_MULTIPLY | OP_MIN | OP_MAX | OP_GREATER | OP_DIVIDE | OP_POW_DYNAMIC
        | OP_GREATER_EQUAL | OP_SUBTRACT | OP_ALPHA => {
            if valid_input(input_a) && valid_input(input_b) {
                Ok(())
            } else {
                Err(())
            }
        }
        OP_ABS | OP_CLAMP | OP_MAP | OP_INVERT | OP_CURVE3 | OP_CURVE5 | OP_POW | OP_SIGNED_POW
        | OP_BOOST | OP_STEPS | OP_SIN | OP_COS | OP_ROUND | OP_RTF_SIN | OP_RTF_COS
        | OP_SIGNED_INT_POW => {
            if valid_input(input_a) {
                Ok(())
            } else {
                Err(())
            }
        }
        OP_LERP | OP_SELECT => {
            if valid_input(input_a) && valid_input(input_b) && valid_input(input_c) {
                Ok(())
            } else {
                Err(())
            }
        }
        _ => Err(()),
    }
}

fn fill_program_2d(
    program: &Program,
    seed: i64,
    origin_x: i32,
    origin_z: i32,
    width: usize,
    height: usize,
    output: &mut [f32],
) -> Result<(), ()> {
    if width == 0 || height == 0 || width > 8192 || height > 8192 {
        return Err(());
    }
    let sample_count = width.checked_mul(height).ok_or(())?;
    let output_count = sample_count.checked_mul(program.roots.len()).ok_or(())?;
    if output.len() != output_count {
        return Err(());
    }
    let grid = Grid::<2>::new(width, height).sample_position(origin_x, origin_z);
    PROGRAM_SCRATCH.with_borrow_mut(|scratch| {
        scratch.resize(program.nodes.len(), sample_count);
        for (index, node) in program.nodes.iter().enumerate() {
            let (inputs, current) = scratch.buffers.split_at_mut(index);
            let target = &mut current[0];
            match node.opcode {
                OP_CONSTANT => target.fill(node.params[0]),
                OP_COORD_X => fill_coordinate_x(origin_x, width, target),
                OP_COORD_Z => fill_coordinate_z(origin_z, width, target),
                OP_PERLIN_FBM => fill_fbm_perlin(&grid, inputs, seed, node, target),
                OP_VALUE_FBM => fill_fbm_value(&grid, inputs, seed, node, target),
                OP_SIMPLEX_FBM => fill_fbm_simplex(&grid, inputs, seed, node, target),
                OP_CELLULAR_FBM => fill_fbm_cellular(&grid, inputs, seed, node, target),
                OP_PERLIN_BILLOW => fill_billow_perlin(&grid, inputs, seed, node, target),
                OP_PERLIN_RIDGED => fill_ridged_perlin(&grid, inputs, seed, node, target),
                OP_SIMPLEX_RIDGED => fill_ridged_simplex(&grid, inputs, seed, node, target),
                OP_RTF_PERLIN => rtf_noise::fill_perlin(inputs, seed, node, target, false),
                OP_RTF_PERLIN2 => rtf_noise::fill_perlin(inputs, seed, node, target, true),
                OP_RTF_PERLIN_FIXED => rtf_noise::fill_perlin(inputs, 0, node, target, false),
                OP_RTF_PERLIN2_FIXED => rtf_noise::fill_perlin(inputs, 0, node, target, true),
                OP_RTF_SIMPLEX => rtf_noise::fill_simplex(inputs, seed, node, target, false),
                OP_RTF_SIMPLEX2 => rtf_noise::fill_simplex(inputs, seed, node, target, true),
                OP_RTF_PERLIN_RIDGE => {
                    rtf_noise::fill_perlin_ridge(inputs, seed, node, target, false)
                }
                OP_RTF_SIMPLEX_RIDGE => rtf_noise::fill_simplex_ridge(inputs, seed, node, target),
                OP_RTF_BILLOW => rtf_noise::fill_perlin_ridge(inputs, seed, node, target, true),
                OP_RTF_CUBIC => rtf_noise::fill_cubic(inputs, seed, node, target),
                OP_RTF_WHITE => rtf_noise::fill_white(inputs, seed, node, target),
                OP_RTF_WORLEY => rtf_noise::fill_worley(inputs, seed, node, target),
                OP_RTF_WORLEY_EDGE => rtf_noise::fill_worley_edge(inputs, seed, node, target),
                OP_ADD => apply_binary(inputs, node, target, |a, b| a + b),
                OP_SUBTRACT => apply_binary(inputs, node, target, |a, b| a - b),
                OP_MULTIPLY => apply_binary(inputs, node, target, |a, b| a * b),
                OP_ALPHA => apply_binary(inputs, node, target, |input, alpha| {
                    input * alpha + (1.0 - alpha)
                }),
                OP_MIN => apply_binary(inputs, node, target, f32::min),
                OP_MAX => apply_binary(inputs, node, target, f32::max),
                OP_ABS => apply_unary(inputs, node, target, f32::abs),
                OP_CLAMP => {
                    let min = node.params[0];
                    let max = node.params[1];
                    apply_unary(inputs, node, target, |value| value.clamp(min, max));
                }
                OP_MAP => {
                    let in_min = node.params[0];
                    let in_max = node.params[1];
                    let out_min = node.params[2];
                    let out_max = node.params[3];
                    apply_unary(inputs, node, target, |value| {
                        let alpha = (value - in_min) / (in_max - in_min);
                        out_min + alpha * (out_max - out_min)
                    });
                }
                OP_INVERT => apply_unary(inputs, node, target, |value| 1.0 - value),
                OP_CURVE3 => apply_unary(inputs, node, target, |value| {
                    value * value * (3.0 - 2.0 * value)
                }),
                OP_CURVE5 => apply_unary(inputs, node, target, |value| {
                    value * value * value * (value * (value * 6.0 - 15.0) + 10.0)
                }),
                OP_LERP => apply_ternary(inputs, node, target, |alpha, lower, upper| {
                    lower + alpha * (upper - lower)
                }),
                OP_SELECT => apply_ternary(inputs, node, target, |selector, lower, upper| {
                    if selector != 0.0 { upper } else { lower }
                }),
                OP_POW => {
                    let power = node.params[0];
                    apply_unary(inputs, node, target, |value| java_pow(value, power));
                }
                OP_GREATER => {
                    apply_binary(inputs, node, target, |a, b| if a > b { 1.0 } else { 0.0 })
                }
                OP_DIVIDE => apply_binary(
                    inputs,
                    node,
                    target,
                    |a, b| {
                        if b == 0.0 { 0.0 } else { a / b }
                    },
                ),
                OP_POW_DYNAMIC => apply_binary(inputs, node, target, java_pow),
                OP_ROUND => apply_unary(inputs, node, target, java_round),
                OP_GREATER_EQUAL => {
                    apply_binary(inputs, node, target, |a, b| if a >= b { 1.0 } else { 0.0 })
                }
                OP_SIGNED_POW => {
                    let power = node.params[0];
                    apply_unary(inputs, node, target, |value| {
                        java_pow(value.abs(), power).copysign(value)
                    });
                }
                OP_SIGNED_INT_POW => {
                    let power = node.params[0] as i32;
                    apply_unary(inputs, node, target, |value| {
                        java_int_pow(value, power).copysign(value)
                    });
                }
                OP_BOOST => {
                    let iterations = node.params[0].clamp(1.0, 32.0) as usize;
                    apply_unary(inputs, node, target, |mut value| {
                        for _ in 0..iterations {
                            value = java_pow(value, 1.0 - value);
                        }
                        value
                    });
                }
                OP_STEPS => {
                    let step_count = node.params[0];
                    let slope_min = node.params[1];
                    let slope_max = node.params[2];
                    let curve = node.params[3] as i32;
                    apply_unary(inputs, node, target, |mut value| {
                        let range = slope_max - slope_min;
                        if range <= 0.0 {
                            return (value * step_count).trunc() / step_count;
                        }
                        value = 1.0 - value;
                        let stepped = (value * step_count).trunc() / step_count;
                        let delta = value - stepped;
                        let alpha = ((delta * step_count - slope_min) / range).clamp(0.0, 1.0);
                        let alpha = match curve {
                            1 => alpha * alpha * (3.0 - 2.0 * alpha),
                            2 => alpha * alpha * alpha * (alpha * (alpha * 6.0 - 15.0) + 10.0),
                            _ => alpha,
                        };
                        1.0 - (stepped + alpha * (value - stepped))
                    });
                }
                OP_SIN => apply_unary(inputs, node, target, f32::sin),
                OP_COS => apply_unary(inputs, node, target, f32::cos),
                OP_RTF_SIN => apply_unary(inputs, node, target, rtf_noise::legacy_sin),
                OP_RTF_COS => apply_unary(inputs, node, target, rtf_noise::legacy_cos),
                _ => return Err(()),
            }
        }

        for (root_index, root) in program.roots.iter().copied().enumerate() {
            let source = &scratch.buffers[root];
            let target = &mut output[root_index * sample_count..(root_index + 1) * sample_count];
            for (target, source) in target.iter_mut().zip(source) {
                *target = *source;
            }
        }
        Ok(())
    })
}

fn fill_fbm_perlin(
    grid: &Grid<2>,
    inputs: &[Vec<f32>],
    seed: i64,
    node: &ProgramNode,
    output: &mut [f32],
) {
    if let Some((x, z)) = node_coordinates(inputs, node) {
        BatchNoise::<2, Fbm, Perlin>::builder(x.simd_iter(), z.simd_iter())
            .seed_with_grid(seed, node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    } else if GRID_BACKEND_AVAILABLE && node.params[0].abs() < 1.0 {
        grid.seed(seed)
            .builder::<Fbm, Perlin>()
            .seed(node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    } else {
        BatchNoise::<2, Fbm, Perlin>::builder(grid.x_iter(), grid.y_iter())
            .seed_with_grid(seed, node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    }
}

fn fill_fbm_value(
    grid: &Grid<2>,
    inputs: &[Vec<f32>],
    seed: i64,
    node: &ProgramNode,
    output: &mut [f32],
) {
    if let Some((x, z)) = node_coordinates(inputs, node) {
        BatchNoise::<2, Fbm, Value>::builder(x.simd_iter(), z.simd_iter())
            .seed_with_grid(seed, node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    } else if GRID_BACKEND_AVAILABLE && node.params[0].abs() < 1.0 {
        grid.seed(seed)
            .builder::<Fbm, Value>()
            .seed(node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    } else {
        BatchNoise::<2, Fbm, Value>::builder(grid.x_iter(), grid.y_iter())
            .seed_with_grid(seed, node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    }
}

fn fill_fbm_simplex(
    grid: &Grid<2>,
    inputs: &[Vec<f32>],
    seed: i64,
    node: &ProgramNode,
    output: &mut [f32],
) {
    if let Some((x, z)) = node_coordinates(inputs, node) {
        BatchNoise::<2, Fbm, Simplex>::builder(x.simd_iter(), z.simd_iter())
            .seed_with_grid(seed, node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
        return;
    }
    BatchNoise::<2, Fbm, Simplex>::builder(grid.x_iter(), grid.y_iter())
        .seed_with_grid(seed, node.seed_offset)
        .octaves(node.input_a as usize)
        .frequency(node.params[0])
        .lacunarity(node.params[1])
        .persistence(node.params[2])
        .amplitude(node.params[3])
        .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
        .fill(output);
}

fn fill_fbm_cellular(
    grid: &Grid<2>,
    inputs: &[Vec<f32>],
    seed: i64,
    node: &ProgramNode,
    output: &mut [f32],
) {
    if let Some((x, z)) = node_coordinates(inputs, node) {
        BatchNoise::<2, Fbm, Cellular>::builder(x.simd_iter(), z.simd_iter())
            .seed_with_grid(seed, node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
        return;
    }
    BatchNoise::<2, Fbm, Cellular>::builder(grid.x_iter(), grid.y_iter())
        .seed_with_grid(seed, node.seed_offset)
        .octaves(node.input_a as usize)
        .frequency(node.params[0])
        .lacunarity(node.params[1])
        .persistence(node.params[2])
        .amplitude(node.params[3])
        .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
        .fill(output);
}

fn fill_billow_perlin(
    grid: &Grid<2>,
    inputs: &[Vec<f32>],
    seed: i64,
    node: &ProgramNode,
    output: &mut [f32],
) {
    if let Some((x, z)) = node_coordinates(inputs, node) {
        BatchNoise::<2, Billow, Perlin>::builder(x.simd_iter(), z.simd_iter())
            .seed_with_grid(seed, node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    } else if GRID_BACKEND_AVAILABLE && node.params[0].abs() < 1.0 {
        grid.seed(seed)
            .builder::<Billow, Perlin>()
            .seed(node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    } else {
        BatchNoise::<2, Billow, Perlin>::builder(grid.x_iter(), grid.y_iter())
            .seed_with_grid(seed, node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    }
}

fn fill_ridged_perlin(
    grid: &Grid<2>,
    inputs: &[Vec<f32>],
    seed: i64,
    node: &ProgramNode,
    output: &mut [f32],
) {
    if let Some((x, z)) = node_coordinates(inputs, node) {
        BatchNoise::<2, Ridged, Perlin>::builder(x.simd_iter(), z.simd_iter())
            .seed_with_grid(seed, node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    } else if GRID_BACKEND_AVAILABLE && node.params[0].abs() < 1.0 {
        grid.seed(seed)
            .builder::<Ridged, Perlin>()
            .seed(node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    } else {
        BatchNoise::<2, Ridged, Perlin>::builder(grid.x_iter(), grid.y_iter())
            .seed_with_grid(seed, node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    }
}

fn fill_ridged_simplex(
    grid: &Grid<2>,
    inputs: &[Vec<f32>],
    seed: i64,
    node: &ProgramNode,
    output: &mut [f32],
) {
    if let Some((x, z)) = node_coordinates(inputs, node) {
        BatchNoise::<2, Ridged, Simplex>::builder(x.simd_iter(), z.simd_iter())
            .seed_with_grid(seed, node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    } else {
        BatchNoise::<2, Ridged, Simplex>::builder(grid.x_iter(), grid.y_iter())
            .seed_with_grid(seed, node.seed_offset)
            .octaves(node.input_a as usize)
            .frequency(node.params[0])
            .lacunarity(node.params[1])
            .persistence(node.params[2])
            .amplitude(node.params[3])
            .scaling(axis_scale(node.params[4]), axis_scale(node.params[5]))
            .fill(output);
    }
}

fn node_coordinates<'a>(
    inputs: &'a [Vec<f32>],
    node: &ProgramNode,
) -> Option<(&'a [f32], &'a [f32])> {
    if node.input_b < 0 || node.input_c < 0 {
        return None;
    }
    Some((
        &inputs[node.input_b as usize],
        &inputs[node.input_c as usize],
    ))
}

fn fill_coordinate_x(origin_x: i32, width: usize, output: &mut [f32]) {
    for (index, value) in output.iter_mut().enumerate() {
        *value = (origin_x + (index % width) as i32) as f32;
    }
}

fn fill_coordinate_z(origin_z: i32, width: usize, output: &mut [f32]) {
    for (index, value) in output.iter_mut().enumerate() {
        *value = (origin_z + (index / width) as i32) as f32;
    }
}

fn axis_scale(value: f32) -> f32 {
    value
}

fn apply_unary(
    inputs: &[Vec<f32>],
    node: &ProgramNode,
    output: &mut [f32],
    operation: impl Fn(f32) -> f32,
) {
    let input = &inputs[node.input_a as usize];
    for (output, input) in output.iter_mut().zip(input) {
        *output = operation(*input);
    }
}

fn apply_binary(
    inputs: &[Vec<f32>],
    node: &ProgramNode,
    output: &mut [f32],
    operation: impl Fn(f32, f32) -> f32,
) {
    let input_a = &inputs[node.input_a as usize];
    let input_b = &inputs[node.input_b as usize];
    for ((output, input_a), input_b) in output.iter_mut().zip(input_a).zip(input_b) {
        *output = operation(*input_a, *input_b);
    }
}

fn apply_ternary(
    inputs: &[Vec<f32>],
    node: &ProgramNode,
    output: &mut [f32],
    operation: impl Fn(f32, f32, f32) -> f32,
) {
    let input_a = &inputs[node.input_a as usize];
    let input_b = &inputs[node.input_b as usize];
    let input_c = &inputs[node.input_c as usize];
    for (((output, input_a), input_b), input_c) in
        output.iter_mut().zip(input_a).zip(input_b).zip(input_c)
    {
        *output = operation(*input_a, *input_b, *input_c);
    }
}

fn read_u32(bytes: &[u8], offset: &mut usize) -> Result<u32, ()> {
    let value = u32::from_le_bytes(read_array(bytes, offset)?);
    Ok(value)
}

fn read_i32(bytes: &[u8], offset: &mut usize) -> Result<i32, ()> {
    let value = i32::from_le_bytes(read_array(bytes, offset)?);
    Ok(value)
}

fn read_i64(bytes: &[u8], offset: &mut usize) -> Result<i64, ()> {
    let value = i64::from_le_bytes(read_array(bytes, offset)?);
    Ok(value)
}

fn read_f32(bytes: &[u8], offset: &mut usize) -> Result<f32, ()> {
    let value = f32::from_le_bytes(read_array(bytes, offset)?);
    Ok(value)
}

fn read_array<const N: usize>(bytes: &[u8], offset: &mut usize) -> Result<[u8; N], ()> {
    let end = offset.checked_add(N).ok_or(())?;
    let source = bytes.get(*offset..end).ok_or(())?;
    let mut value = [0; N];
    value.copy_from_slice(source);
    *offset = end;
    Ok(value)
}

fn quantize(value: f32) -> f32 {
    (value * QUANTIZATION).round() / QUANTIZATION
}

fn java_round(value: f32) -> f32 {
    if value >= 0.0 {
        (value + 0.5) as i32 as f32
    } else {
        (value - 0.5) as i32 as f32
    }
}

fn java_pow(value: f32, power: f32) -> f32 {
    (value as f64).powf(power as f64) as f32
}

fn java_int_pow(value: f32, power: i32) -> f32 {
    match power {
        0 => 1.0,
        1 => value,
        2 => value * value,
        3 => value * value * value,
        4 => value * value * value * value,
        _ if power > 0 => {
            let mut result = 1.0;
            for _ in 0..power {
                result *= value;
            }
            result
        }
        _ => java_pow(value, power as f32),
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn rtf_quick_noise_abi_version() -> u32 {
    ABI_VERSION
}

#[unsafe(no_mangle)]
pub extern "C" fn rtf_quick_noise_tile_samples() -> usize {
    TILE_SAMPLES
}

/// Compiles one validated QUICK_V2 program and returns an opaque handle.
///
/// # Safety
/// `program_bytes` must point to `program_length` readable bytes and
/// `output_handle` must point to one writable `u64`.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rtf_quick_noise_compile_program_v2(
    program_bytes: *const u8,
    program_length: usize,
    output_handle: *mut u64,
) -> i32 {
    if program_bytes.is_null() || output_handle.is_null() || program_length == 0 {
        return INVALID_ARGUMENT;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let bytes = unsafe { slice::from_raw_parts(program_bytes, program_length) };
        let program = parse_program(bytes).map_err(|_| INVALID_PROGRAM)?;
        let handle = NEXT_PROGRAM_HANDLE.fetch_add(1, Ordering::Relaxed);
        if handle == 0 {
            return Err(INVALID_PROGRAM);
        }
        programs()
            .write()
            .map_err(|_| PANIC)?
            .insert(handle, Arc::new(program));
        unsafe { output_handle.write(handle) };
        Ok(())
    }));
    match result {
        Ok(Ok(())) => SUCCESS,
        Ok(Err(status)) => status,
        Err(_) => PANIC,
    }
}

#[unsafe(no_mangle)]
pub extern "C" fn rtf_quick_noise_program_outputs_v2(handle: u64) -> usize {
    programs()
        .read()
        .ok()
        .and_then(|programs| programs.get(&handle).map(|program| program.roots.len()))
        .unwrap_or(0)
}

#[unsafe(no_mangle)]
pub extern "C" fn rtf_quick_noise_free_program_v2(handle: u64) -> i32 {
    if handle == 0 {
        return INVALID_ARGUMENT;
    }
    match programs().write() {
        Ok(mut programs) => {
            if programs.remove(&handle).is_some() {
                SUCCESS
            } else {
                INVALID_PROGRAM
            }
        }
        Err(_) => PANIC,
    }
}

/// Fills all QUICK_V2 root fields in root-major, x-fastest order.
///
/// # Safety
/// `output` must point to `output_length` writable `f32` values.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn rtf_quick_noise_fill_program_2d_v2(
    handle: u64,
    seed: i64,
    origin_x: i32,
    origin_z: i32,
    width: usize,
    height: usize,
    output: *mut f32,
    output_length: usize,
) -> i32 {
    if handle == 0 || output.is_null() || output_length == 0 {
        return INVALID_ARGUMENT;
    }
    let result = catch_unwind(AssertUnwindSafe(|| {
        let program = programs()
            .read()
            .map_err(|_| PANIC)?
            .get(&handle)
            .cloned()
            .ok_or(INVALID_PROGRAM)?;
        let output = unsafe { slice::from_raw_parts_mut(output, output_length) };
        fill_program_2d(&program, seed, origin_x, origin_z, width, height, output)
            .map_err(|_| INVALID_ARGUMENT)
    }));
    match result {
        Ok(Ok(())) => SUCCESS,
        Ok(Err(status)) => status,
        Err(_) => PANIC,
    }
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

    fn encode_program(nodes: &[ProgramNode], roots: &[u32]) -> Vec<u8> {
        let mut bytes = Vec::with_capacity(
            PROGRAM_HEADER_BYTES + nodes.len() * PROGRAM_NODE_BYTES + roots.len() * 4,
        );
        bytes.extend_from_slice(&PROGRAM_MAGIC.to_le_bytes());
        bytes.extend_from_slice(&PROGRAM_VERSION.to_le_bytes());
        bytes.extend_from_slice(&(nodes.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&(roots.len() as u32).to_le_bytes());
        bytes.extend_from_slice(&(PROGRAM_NODE_BYTES as u32).to_le_bytes());
        for node in nodes {
            bytes.extend_from_slice(&node.opcode.to_le_bytes());
            bytes.extend_from_slice(&node.input_a.to_le_bytes());
            bytes.extend_from_slice(&node.input_b.to_le_bytes());
            bytes.extend_from_slice(&node.input_c.to_le_bytes());
            bytes.extend_from_slice(&node.seed_offset.to_le_bytes());
            for value in node.params {
                bytes.extend_from_slice(&value.to_le_bytes());
            }
        }
        for root in roots {
            bytes.extend_from_slice(&root.to_le_bytes());
        }
        bytes
    }

    fn test_program_bytes() -> Vec<u8> {
        encode_program(
            &[
                ProgramNode {
                    opcode: OP_PERLIN_FBM,
                    input_a: 4,
                    input_b: -1,
                    input_c: -1,
                    seed_offset: 0x514E_5632,
                    params: [1.0 / 64.0, 2.0, 0.5, 1.0, 1.0, 1.0],
                },
                ProgramNode {
                    opcode: OP_CONSTANT,
                    input_a: -1,
                    input_b: -1,
                    input_c: -1,
                    seed_offset: 0,
                    params: [0.25, 0.0, 0.0, 0.0, 0.0, 0.0],
                },
                ProgramNode {
                    opcode: OP_ADD,
                    input_a: 0,
                    input_b: 1,
                    input_c: -1,
                    seed_offset: 0,
                    params: [0.0; 6],
                },
            ],
            &[2],
        )
    }

    #[test]
    fn java_round_preserves_rtf_half_step_boundaries() {
        let below_positive_half = f32::from_bits(0x3eff_ffff);
        let above_negative_half = f32::from_bits(0xbeff_ffff);

        assert_eq!(java_round(below_positive_half), 1.0);
        assert_eq!(java_round(above_negative_half), -1.0);
        assert_eq!(java_round(0.49), 0.0);
        assert_eq!(java_round(-0.49), 0.0);
    }

    #[test]
    fn quick_v2_program_lifecycle_is_deterministic() {
        let bytes = test_program_bytes();
        let mut handle = 0;
        assert_eq!(
            unsafe { rtf_quick_noise_compile_program_v2(bytes.as_ptr(), bytes.len(), &mut handle) },
            SUCCESS
        );
        assert_ne!(handle, 0);
        assert_eq!(rtf_quick_noise_program_outputs_v2(handle), 1);

        let mut first = vec![0.0; 32 * 32];
        let mut repeated = vec![0.0; 32 * 32];
        assert_eq!(
            unsafe {
                rtf_quick_noise_fill_program_2d_v2(
                    handle,
                    991,
                    -64,
                    96,
                    32,
                    32,
                    first.as_mut_ptr(),
                    first.len(),
                )
            },
            SUCCESS
        );
        assert_eq!(
            unsafe {
                rtf_quick_noise_fill_program_2d_v2(
                    handle,
                    991,
                    -64,
                    96,
                    32,
                    32,
                    repeated.as_mut_ptr(),
                    repeated.len(),
                )
            },
            SUCCESS
        );
        assert_eq!(first, repeated);
        assert!(first.iter().all(|value| value.is_finite()));
        assert!(first.iter().any(|value| *value != 0.25));

        assert_eq!(rtf_quick_noise_free_program_v2(handle), SUCCESS);
        assert_eq!(rtf_quick_noise_program_outputs_v2(handle), 0);
        assert_eq!(rtf_quick_noise_free_program_v2(handle), INVALID_PROGRAM);
    }

    #[test]
    fn quick_v2_rejects_invalid_programs() {
        let mut bytes = test_program_bytes();
        bytes[PROGRAM_HEADER_BYTES] = 0xFF;
        let mut handle = 0;
        assert_eq!(
            unsafe { rtf_quick_noise_compile_program_v2(bytes.as_ptr(), bytes.len(), &mut handle) },
            INVALID_PROGRAM
        );
        assert_eq!(handle, 0);
    }

    #[test]
    fn quick_v2_fixed_grid_overlap_matches() {
        let program = parse_program(&test_program_bytes()).unwrap();
        let mut left = vec![0.0; 32 * 32];
        let mut right = vec![0.0; 32 * 32];
        fill_program_2d(&program, 72, 0, 0, 32, 32, &mut left).unwrap();
        fill_program_2d(&program, 72, 16, 0, 32, 32, &mut right).unwrap();
        for z in 0..32 {
            for x in 16..32 {
                assert_eq!(left[z * 32 + x], right[z * 32 + x - 16]);
            }
        }
    }

    #[test]
    fn quick_v2_steps_clamps_slope_alpha_like_legacy_map() {
        for (input, expected) in [(0.99, 1.0), (0.76, 0.76)] {
            let program = parse_program(&encode_program(
                &[
                    ProgramNode {
                        opcode: OP_CONSTANT,
                        input_a: -1,
                        input_b: -1,
                        input_c: -1,
                        seed_offset: 0,
                        params: [input, 0.0, 0.0, 0.0, 0.0, 0.0],
                    },
                    ProgramNode {
                        opcode: OP_STEPS,
                        input_a: 0,
                        input_b: -1,
                        input_c: -1,
                        seed_offset: 0,
                        params: [4.0, 0.2, 0.8, 0.0, 0.0, 0.0],
                    },
                ],
                &[1],
            ))
            .unwrap();
            let mut output = [0.0];
            fill_program_2d(&program, 0, 0, 0, 1, 1, &mut output).unwrap();
            assert_eq!(output[0], expected);
        }
    }

    #[test]
    fn quick_v2_program_fills_are_thread_safe() {
        let program = Arc::new(parse_program(&test_program_bytes()).unwrap());
        let expected_program = program.clone();
        let expected = thread::spawn(move || {
            let mut output = vec![0.0; 48 * 48];
            fill_program_2d(&expected_program, 123, -24, 48, 48, 48, &mut output).unwrap();
            output
        })
        .join()
        .unwrap();
        let workers: Vec<_> = (0..8)
            .map(|_| {
                let program = program.clone();
                thread::spawn(move || {
                    let mut output = vec![0.0; 48 * 48];
                    fill_program_2d(&program, 123, -24, 48, 48, 48, &mut output).unwrap();
                    output
                })
            })
            .collect();
        for worker in workers {
            assert_eq!(expected, worker.join().unwrap());
        }
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
