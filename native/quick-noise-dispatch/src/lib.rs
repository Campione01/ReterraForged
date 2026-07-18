use std::path::Path;
use std::ptr;
use std::sync::OnceLock;

use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass, JString};
use jni::sys::{jint, jlong, jstring};
use libloading::{Library, Symbol};

const ABI_VERSION: u32 = 2;
const TILE_SAMPLES: usize = 32 * 32 * 32;
const TILE_BYTES: usize = TILE_SAMPLES * size_of::<f32>();

type AbiVersionFn = unsafe extern "C" fn() -> u32;
type TileSamplesFn = unsafe extern "C" fn() -> usize;
type FillTileFn = unsafe extern "C" fn(i64, i32, i32, i32, f32, f32, f32, *mut f32) -> i32;
type CompileProgramFn = unsafe extern "C" fn(*const u8, usize, *mut u64) -> i32;
type ProgramOutputsFn = unsafe extern "C" fn(u64) -> usize;
type FreeProgramFn = unsafe extern "C" fn(u64) -> i32;
type FillProgram2dFn =
    unsafe extern "C" fn(u64, i64, i32, i32, usize, usize, *mut f32, usize) -> i32;

static BACKEND: OnceLock<Result<Backend, String>> = OnceLock::new();

struct Backend {
    cave: LoadedBackend,
    program: Option<LoadedBackend>,
    name: String,
}

struct LoadedBackend {
    _library: Library,
    fill_tile: FillTileFn,
    compile_program: CompileProgramFn,
    program_outputs: ProgramOutputsFn,
    free_program: FreeProgramFn,
    fill_program_2d: FillProgram2dFn,
    name: &'static str,
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_raccoonman_reterraforged_world_worldgen_quicknoise_QuickNoiseNative_nativeInitialize<
    'local,
>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    directory: JString<'local>,
) -> jstring {
    let directory: String = match env.get_string(&directory) {
        Ok(value) => value.into(),
        Err(error) => {
            return java_string(
                &mut env,
                &format!("ERROR:invalid native directory: {error}"),
            );
        }
    };
    let result = BACKEND.get_or_init(|| load_backend(Path::new(&directory)));
    let message = match result {
        Ok(backend) => backend.name.clone(),
        Err(error) => format!("ERROR:{error}"),
    };
    java_string(&mut env, &message)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_raccoonman_reterraforged_world_worldgen_quicknoise_QuickNoiseNative_nativeFillTile<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    seed: jlong,
    tile_x: jint,
    tile_y: jint,
    tile_z: jint,
    chamber_bias: f32,
    spaghetti_width: f32,
    noodle_width: f32,
    output: JByteBuffer<'local>,
) -> jint {
    let Some(Ok(backend)) = BACKEND.get() else {
        return 3;
    };
    let Ok(capacity) = env.get_direct_buffer_capacity(&output) else {
        return 1;
    };
    if capacity < TILE_BYTES {
        return 1;
    }
    let Ok(address) = env.get_direct_buffer_address(&output) else {
        return 1;
    };
    unsafe {
        (backend.cave.fill_tile)(
            seed,
            tile_x,
            tile_y,
            tile_z,
            chamber_bias,
            spaghetti_width,
            noodle_width,
            address.cast::<f32>(),
        )
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_raccoonman_reterraforged_world_worldgen_quicknoise_QuickNoiseNative_nativeCompileProgram<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    program: JByteBuffer<'local>,
    program_length: jint,
) -> jlong {
    let Some(Ok(backend)) = BACKEND.get() else {
        return -3;
    };
    let Some(program_backend) = backend.program.as_ref() else {
        return -3;
    };
    if program_length <= 0 {
        return -1;
    }
    let Ok(capacity) = env.get_direct_buffer_capacity(&program) else {
        return -1;
    };
    let program_length = program_length as usize;
    if capacity < program_length {
        return -1;
    }
    let Ok(address) = env.get_direct_buffer_address(&program) else {
        return -1;
    };
    let mut handle = 0_u64;
    let status = unsafe {
        (program_backend.compile_program)(address.cast_const(), program_length, &mut handle)
    };
    if status != 0 {
        return -(status as jlong);
    }
    if handle == 0 || handle > jlong::MAX as u64 {
        return -3;
    }
    handle as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_raccoonman_reterraforged_world_worldgen_quicknoise_QuickNoiseNative_nativeProgramOutputs(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jint {
    let Some(Ok(backend)) = BACKEND.get() else {
        return 0;
    };
    let Some(program_backend) = backend.program.as_ref() else {
        return 0;
    };
    if handle <= 0 {
        return 0;
    }
    unsafe { (program_backend.program_outputs)(handle as u64) as jint }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_raccoonman_reterraforged_world_worldgen_quicknoise_QuickNoiseNative_nativeFreeProgram(
    _env: JNIEnv<'_>,
    _class: JClass<'_>,
    handle: jlong,
) -> jint {
    let Some(Ok(backend)) = BACKEND.get() else {
        return 3;
    };
    let Some(program_backend) = backend.program.as_ref() else {
        return 3;
    };
    if handle <= 0 {
        return 1;
    }
    unsafe { (program_backend.free_program)(handle as u64) }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_raccoonman_reterraforged_world_worldgen_quicknoise_QuickNoiseNative_nativeFillProgram2d<
    'local,
>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    handle: jlong,
    seed: jlong,
    origin_x: jint,
    origin_z: jint,
    width: jint,
    height: jint,
    output: JByteBuffer<'local>,
) -> jint {
    let Some(Ok(backend)) = BACKEND.get() else {
        return 3;
    };
    let Some(program_backend) = backend.program.as_ref() else {
        return 3;
    };
    if handle <= 0 || width <= 0 || height <= 0 {
        return 1;
    }
    let output_count = match (width as usize)
        .checked_mul(height as usize)
        .and_then(|count| {
            count.checked_mul(unsafe { (program_backend.program_outputs)(handle as u64) })
        }) {
        Some(count) if count > 0 => count,
        _ => return 1,
    };
    let required_bytes = match output_count.checked_mul(size_of::<f32>()) {
        Some(bytes) => bytes,
        None => return 1,
    };
    let Ok(capacity) = env.get_direct_buffer_capacity(&output) else {
        return 1;
    };
    if capacity < required_bytes {
        return 1;
    }
    let Ok(address) = env.get_direct_buffer_address(&output) else {
        return 1;
    };
    unsafe {
        (program_backend.fill_program_2d)(
            handle as u64,
            seed,
            origin_x,
            origin_z,
            width as usize,
            height as usize,
            address.cast::<f32>(),
            output_count,
        )
    }
}

fn load_backend(directory: &Path) -> Result<Backend, String> {
    let cave = load_first(directory, cave_backend_candidates())?;
    let program = load_first(directory, program_backend_candidates()).ok();
    let program_name = program
        .as_ref()
        .map(|backend| backend.name)
        .unwrap_or("unavailable");
    let name = format!("cave={},quick_v2={program_name}", cave.name);
    Ok(Backend {
        cave,
        program,
        name,
    })
}

fn load_first(directory: &Path, candidates: Vec<&'static str>) -> Result<LoadedBackend, String> {
    let mut errors = Vec::new();
    for name in candidates {
        let path = directory.join(library_filename(name));
        match unsafe { load_candidate(&path, name) } {
            Ok(backend) => return Ok(backend),
            Err(error) => errors.push(format!("{name}: {error}")),
        }
    }
    Err(format!(
        "no usable quick-noise backend ({})",
        errors.join("; ")
    ))
}

unsafe fn load_candidate(path: &Path, name: &'static str) -> Result<LoadedBackend, String> {
    let library =
        unsafe { Library::new(path) }.map_err(|error| format!("{}: {error}", path.display()))?;
    let abi_version: Symbol<AbiVersionFn> =
        unsafe { library.get(b"rtf_quick_noise_abi_version\0") }
            .map_err(|error| format!("missing ABI symbol: {error}"))?;
    let tile_samples: Symbol<TileSamplesFn> =
        unsafe { library.get(b"rtf_quick_noise_tile_samples\0") }
            .map_err(|error| format!("missing tile-size symbol: {error}"))?;
    let fill_tile: Symbol<FillTileFn> =
        unsafe { library.get(b"rtf_quick_noise_fill_cave_tile_v1\0") }
            .map_err(|error| format!("missing fill symbol: {error}"))?;
    let compile_program: Symbol<CompileProgramFn> =
        unsafe { library.get(b"rtf_quick_noise_compile_program_v2\0") }
            .map_err(|error| format!("missing program compiler symbol: {error}"))?;
    let program_outputs: Symbol<ProgramOutputsFn> =
        unsafe { library.get(b"rtf_quick_noise_program_outputs_v2\0") }
            .map_err(|error| format!("missing program outputs symbol: {error}"))?;
    let free_program: Symbol<FreeProgramFn> =
        unsafe { library.get(b"rtf_quick_noise_free_program_v2\0") }
            .map_err(|error| format!("missing program free symbol: {error}"))?;
    let fill_program_2d: Symbol<FillProgram2dFn> =
        unsafe { library.get(b"rtf_quick_noise_fill_program_2d_v2\0") }
            .map_err(|error| format!("missing program fill symbol: {error}"))?;
    if unsafe { abi_version() } != ABI_VERSION {
        return Err("ABI version mismatch".to_owned());
    }
    if unsafe { tile_samples() } != TILE_SAMPLES {
        return Err("tile size mismatch".to_owned());
    }
    let fill_tile = *fill_tile;
    let compile_program = *compile_program;
    let program_outputs = *program_outputs;
    let free_program = *free_program;
    let fill_program_2d = *fill_program_2d;
    Ok(LoadedBackend {
        _library: library,
        fill_tile,
        compile_program,
        program_outputs,
        free_program,
        fill_program_2d,
        name,
    })
}

fn cave_backend_candidates() -> Vec<&'static str> {
    let mut candidates = Vec::with_capacity(3);
    #[cfg(target_arch = "x86_64")]
    {
        if std::arch::is_x86_feature_detected!("avx512f")
            && std::arch::is_x86_feature_detected!("fma")
        {
            candidates.push("avx512");
        }
        if std::arch::is_x86_feature_detected!("avx2") && std::arch::is_x86_feature_detected!("fma")
        {
            candidates.push("avx2");
        }
    }
    candidates.push("scalar");
    candidates
}

fn program_backend_candidates() -> Vec<&'static str> {
    let mut candidates = Vec::with_capacity(3);
    #[cfg(target_arch = "x86_64")]
    {
        if std::arch::is_x86_feature_detected!("avx512f")
            && std::arch::is_x86_feature_detected!("fma")
        {
            candidates.push("avx512");
        }
        if std::arch::is_x86_feature_detected!("avx2") && std::arch::is_x86_feature_detected!("fma")
        {
            candidates.push("avx2");
        }
        if std::arch::is_x86_feature_detected!("sse4.2") {
            candidates.push("sse42");
        }
    }
    candidates
}

fn library_filename(variant: &str) -> String {
    format!(
        "{}reterraforged_quick_noise_{}{}",
        std::env::consts::DLL_PREFIX,
        variant,
        std::env::consts::DLL_SUFFIX
    )
}

fn java_string(env: &mut JNIEnv<'_>, value: &str) -> jstring {
    env.new_string(value)
        .map(|string| string.into_raw())
        .unwrap_or(ptr::null_mut())
}
