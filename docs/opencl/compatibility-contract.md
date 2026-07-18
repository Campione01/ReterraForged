# Compatibility Contract

## Allowed Ownership

The QUICK_V1 backend may own only:

- the versioned QUICK_V1 cave density node and its five pure 3D noise fields.
- native quick-noise scalar/AVX2 dispatch.
- one bounded canonical tile cache with no task or future ownership.
- an optional RTF-owned OpenCL context, fused kernel, fixed buffers, exact parity state, and non-blocking queue gate.
- the final combination of CPU terrain density and QUICK_V1 cave density before vanilla interpolation/squeeze.

## Forbidden Ownership

The backend must not own or replace:

- biome source selection, climate parameter lookup, TerraBlender, or Biolith calls.
- structure lookup, beardifier construction, or structure influence.
- aquifer decisions or fluid placement.
- surface rules, carvers, feature placement, or final block-state writes.
- RTF rivers, lakes, wetlands, erosion, climate generation, terrain tiles, coasts, or fake-water resolution.
- C2ME executors, chunk statuses, density compiler, scheduling, caches, save/load behavior, or OpenCL resources.
- third-party density functions or router extensions.

This GPU boundary does not prohibit later CPU-side structural improvements to other RTF systems. Such work must remain separate, preserve each system's mod-facing contracts, and receive its own validation.

## C2ME Coexistence

- The QUICK_V1/OpenCL packages have no C2ME dependency and do not detect, import, mix into, or call C2ME classes.
- The surrounding RTF integration retains its pre-existing optional runtime presence check in `MixinNoiseChunk` for CPU terrain-tile queueing; QUICK_V1 neither owns nor calls that path.
- C2ME DFC may compile vanilla wrappers around QUICK_V1 but must retain QUICK_V1 as an opaque `DelegateNode`.
- Both C2ME single-sample and bulk generated code delegate back to QUICK_V1's own methods.
- Concurrent cache misses may duplicate local computation; no C2ME worker waits on an RTF future, executor, neighboring chunk, or GPU queue.
- OpenCL queue contention uses `tryLock`; the caller immediately executes native CPU on failure.

## Biome And Structure Mods

- Biome selection never enters the QUICK_V1 tile or kernel.
- Structure beardifier density is appended by Minecraft after the router's final density and therefore remains outside QUICK_V1.
- Aquifers, surface rules, ore veins, carvers, and block placement consume the combined density through their normal paths.
- Third-party modifications to those systems continue to observe the same Minecraft/RTF integration points.

## Output Invariants

- Native CPU is the mandatory QUICK_V1 backend.
- CPU and OpenCL canonical tile floats must match by raw bits before AUTO or ON can use the GPU.
- Mixed per-tile CPU/GPU fallback is therefore seam-safe.
- QUICK_V1 intentionally changes new-world cave output. Legacy seed/terrain continuity is out of scope; the preset UI retains `LEGACY` as an explicit alternate algorithm.
