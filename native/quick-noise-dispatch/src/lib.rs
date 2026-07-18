use std::path::Path;
use std::ptr;
use std::sync::OnceLock;

use jni::JNIEnv;
use jni::objects::{JByteBuffer, JClass, JString};
use jni::sys::{jint, jlong, jstring};
use libloading::{Library, Symbol};

const ABI_VERSION: u32 = 1;
const TILE_SAMPLES: usize = 32 * 32 * 32;
const TILE_BYTES: usize = TILE_SAMPLES * size_of::<f32>();

type AbiVersionFn = unsafe extern "C" fn() -> u32;
type TileSamplesFn = unsafe extern "C" fn() -> usize;
type FillTileFn = unsafe extern "C" fn(i64, i32, i32, i32, f32, f32, f32, *mut f32) -> i32;

static BACKEND: OnceLock<Result<Backend, String>> = OnceLock::new();

struct Backend {
    _library: Library,
    fill_tile: FillTileFn,
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
        Ok(backend) => backend.name.to_owned(),
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
        (backend.fill_tile)(
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

fn load_backend(directory: &Path) -> Result<Backend, String> {
    let mut errors = Vec::new();
    for name in backend_candidates() {
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

unsafe fn load_candidate(path: &Path, name: &'static str) -> Result<Backend, String> {
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
    if unsafe { abi_version() } != ABI_VERSION {
        return Err("ABI version mismatch".to_owned());
    }
    if unsafe { tile_samples() } != TILE_SAMPLES {
        return Err("tile size mismatch".to_owned());
    }
    let fill_tile = *fill_tile;
    Ok(Backend {
        _library: library,
        fill_tile,
        name,
    })
}

fn backend_candidates() -> Vec<&'static str> {
    let mut candidates = Vec::with_capacity(3);
    #[cfg(target_arch = "x86_64")]
    {
        if std::arch::is_x86_feature_detected!("avx2") && std::arch::is_x86_feature_detected!("fma")
        {
            candidates.push("avx2");
        }
    }
    #[cfg(target_arch = "aarch64")]
    candidates.push("neon");
    candidates.push("scalar");
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
