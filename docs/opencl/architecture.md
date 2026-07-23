# QUICK_V1 Architecture

## World-Generation Boundary

QUICK_V1 replaces only the expensive cave contribution inside final 3D density. The normal CPU graph still owns:

- RTF's 2D continent, height, climate, river, lake, wetland, coast, erosion, and terrain fields.
- biome routing and third-party biome integration.
- initial density, aquifers and fluid decisions, ore veins, structure beardifier input, surface rules, and final block-state placement.
- every custom density function supplied by another mod.

The final-density graph combines the CPU terrain density with one opaque `QuickCaveDensity` node, then lets Minecraft's normal interpolation and squeeze wrappers continue.

## QUICK_V1 Field

- Five independent fields: cheese chamber, spaghetti A/B, and noodle A/B.
- Fourteen seeded Perlin FBM octave evaluations in total: `4 + 3 + 3 + 2 + 2`.
- Cave density is the minimum of the chamber, spaghetti-pair, and noodle-pair shapes.
- Output is quantized to `1/4096` so native CPU and OpenCL produce bit-identical cached floats.
- Existing cave preset controls map to chamber bias, spaghetti width, noodle width, entrance protection, and depth protection.

## Canonical Tile

- Fixed size: `32 x 32 x 32` density samples.
- Minecraft cell scale: `4 x 8 x 4` blocks, so one tile covers roughly `128 x 256 x 128` blocks.
- Index order: x-fast, `(z * 32 + y) * 32 + x`.
- Negative coordinates use `floorDiv`/`floorMod`.
- Cache limit: 128 tiles, approximately 16 MiB of float payload, with a thread-local last-tile fast path.

## Native CPU Backend

- Java loads a packaged Windows x86-64 dispatcher through JNI.
- The dispatcher selects AVX2+FMA or scalar code at runtime. SSE4.2 is intentionally excluded because its quick-noise output was not bit-identical to the other backends.
- Both packaged backends directly use the pinned quick-noise batch API and expose the same tile ABI.
- QUICK_V1 requires a native backend and is an explicit alternate mode. Missing native support fails world setup clearly instead of silently changing the selected terrain algorithm; `LEGACY_V2` is the default preset mode.

## OpenCL Backend

- One original fused OpenCL 1.2 kernel computes the same fourteen octaves and final cave value for all 32,768 samples.
- The RTF main jar never exports `org.lwjgl.opencl`. Fabric and NeoForge carry `org.lwjgl:lwjgl-opencl:3.3.3` as a loader-native nested dependency instead of expanding its classes into the mod module.
- NeoForge preserves the exact JarJar coordinate used by RoadWeaver, so the loader selects one OpenCL library when both mods are present. RTF's nested copy removes only the versioned JPMS descriptor and declares `Automatic-Module-Name: org.lwjgl.opencl`; this avoids a hard module-resolution dependency on LWJGL core while retaining all 158 binding classes.
- Client runtimes use Minecraft's LWJGL core and can enable OpenCL. Headless servers without that core finish module resolution and use the existing native CPU fallback.
- Java computes the octave seeds once per dispatch, matching quick-noise seed mixing.
- AUTO runs warmup plus three complete bitwise CPU/GPU parity tiles, then enables the GPU only when its measured tile time beats native CPU time.
- ON still requires exact parity but bypasses the speed gate.
- A busy queue, unavailable runtime, device failure, parity rejection, or AUTO speed rejection immediately uses native CPU for that tile.
- No FP64 extension is required for QUICK_V1 because its canonical field is f32. The retained LEGACY graph backend keeps its own FP64 requirement.

## C2ME Scheduling Contract

- The QUICK_V1 cache owns no executor, task, future, or blocking preload.
- Concurrent cache misses do not wait for one another; duplicate computation is allowed and the first completed tile wins insertion.
- OpenCL queue ownership uses non-blocking `tryLock` only in generation paths.
- C2ME's density-function compiler sees QUICK_V1 as an unknown custom node and delegates both single and bulk evaluation back to it.
- QUICK_V1 has no compile-time or runtime dependency on C2ME classes. The surrounding RTF mixin retains its pre-existing presence check for CPU terrain-tile queueing, while QUICK_V1 uses the same code and ownership model whether C2ME is absent or present.

## Resource Lifecycle

- Native libraries are extracted once to a content-addressed temporary directory and loaded before QUICK_V1 generation starts.
- OpenCL owns only its platform, device, context, command queue, programs, and fixed buffers.
- Server shutdown closes OpenCL resources. Closing may take the queue lock after generation has stopped; the chunk-generation path itself never blocks on that lock.
