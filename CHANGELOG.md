# Change Log

## 2026-07-18 - QUICK_V2 2D noise engine

### Added

- Added `world.noiseEngine` with `QUICK_V2` as the missing-field default and `LEGACY` as an explicit Java fallback.
- Added the first-page preset UI selector with English and Chinese labels, values, and tooltips.
- Added ABI v2 immutable native programs, 2D Grid/Batch execution, and automatic SSE4.2, AVX2+FMA, or AVX512+FMA dispatch on Windows x86-64.
- Compiled RTF primitive, arithmetic, curve, warp, gradient, terrace, spline, line, and climate noise graphs directly to the pinned Alysara/quick-noise backend.
- Added globally aligned 32x32 per-thread field tiles for normal generation, global terrain scaling, and zoomed previews.
- Added explicit generator-context cleanup, concurrent native fills, graph lifecycle tests, old-preset migration tests, affine-coordinate tests, and third-party bypass tests.

### Compatibility boundaries

- Kept river networks, neighborhood erosion, aquifers, biome routing, structures, surface rules, block placement, and external `Noise.compute` implementations outside the native graph.
- Added no executor, task, future, queue, cache hook, mixin, reflection, or compile-time dependency on C2ME; CPU-accelerated C2ME continues to own its scheduling.
- Kept QUICK_V2 independent from the QUICK_V1 cave algorithm and its optional OpenCL backend.
- QUICK_V2 is deterministic for the native SIMD backend selected once at startup; different SIMD widths are not promised to be byte-identical across machines.

## 2026-07-18 - QUICK_V1/OpenCL integration

This entry records the source synchronization from the local `rtf-opencl` development workspace into the published ReTerraForged repository.

### Added

- Versioned `QUICK_V1` cave-density algorithm for new worlds, with `LEGACY` retained as an explicit preset choice.
- Cave-settings UI and translations for density-algorithm selection and cave compatibility mode.
- Generated `opencl.conf` runtime settings with `AUTO` as the default OpenCL mode.
- Native Windows x86-64 scalar and AVX2 quick-noise backends behind a JNI dispatcher.
- Optional fused OpenCL 1.2 backend limited to QUICK_V1's pure 3D cave field.
- Bounded canonical tile cache, raw-bit CPU/GPU parity gates, AUTO speed qualification, and immediate CPU fallback.
- Unit tests for graph compilation, native parity, compatibility boundaries, cave settings, tile caching, and QUICK_V1 behavior.
- OpenCL architecture, compatibility, reference, and validation documentation.

### Compatibility and packaging

- Kept biomes, climate, rivers, erosion, aquifers, structures, surface rules, block placement, and third-party density functions outside the GPU boundary.
- Kept QUICK_V1 independent from C2ME executors, futures, queues, caches, density compiler internals, and OpenCL resources.
- Packaged `org.lwjgl:lwjgl-opencl:3.3.3` as one loader-deduplicatable nested dependency instead of expanding its package into the RTF module.
- Added a NeoForge packaging gate for JarJar coordinates, JPMS descriptors, automatic module naming, and root-package leakage.

### Attribution and redistribution

- Recorded direct quick-noise use, pinned revision, and dual-license texts.
- Recorded FastFlow, C2ME, Khronos OpenCL, and the VWG/C2ME report as design or compatibility references with explicit no-copy boundaries.
- Bundled LWJGL and Rust dependency license materials plus a root third-party notice in produced jars.

## Existing fork feature baseline

- Configurable archipelagos, island mountains, and volcano-shaped peaks.
- Adjustable offshore depth, island beach width and coverage, and coast transitions.
- Island-aware climate and beach-biome routing that avoids stone-shore dominance.
