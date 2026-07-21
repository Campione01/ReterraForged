# Change Log

## 2026-07-22 - QUICK_V2 legacy semantic parity

### Fixed

- Replaced approximate primitive substitutions with exact native RTF opcodes for Perlin, Perlin2, Simplex, Simplex2, Perlin ridge, Simplex ridge, Billow, Cubic, White, Worley, WorleyEdge, and the legacy sine/cosine tables.
- Preserved Java integer overflow, negative-coordinate floor behavior, seed shifts, gradient/cell tables, interpolation, octave weighting, range mapping, invert, and stepped-slope semantics.
- Made unsupported structural and dynamic-coordinate roots fall back atomically to LEGACY so nested child samples cannot mix QUICK_V2 and Java fields.
- Kept `Worley(NOISE_LOOKUP)` on the exact whole-root Java path instead of admitting an inexact topological substitution.

### Performance and validation

- Moved the exact Perlin, Perlin2, Perlin-ridge, and Billow families onto the pinned quick-noise `ArchSimd` substrate.
- Changed field tiles to 64x64 with two cache slots, preserving the original per-graph, per-thread float capacity.
- Recorded a 2.610x seven-round median speedup for the representative compiled graph against LEGACY while retaining the 1.5x regression gate.
- Added primitive/composite Java-oracle tests and production `Heightmap` distribution tests covering mountains, terrain categories, climate, archipelagos, and complete river generation.
- Verified exact QUICK_V2 output parity across scalar, SSE4.2, AVX2, and AVX512 Windows backends for 139,893 samples.

## 2026-07-18 - QUICK_V2 2D noise engine

### Added

- Added `world.noiseEngine` with `QUICK_V2` as the missing-field default and `LEGACY` as an explicit Java fallback.
- Added the first-page preset UI selector with English and Chinese labels, values, and tooltips.
- Added ABI v2 immutable native programs, 2D Grid/Batch execution, and automatic SSE4.2, AVX2+FMA, or AVX512+FMA dispatch on Windows x86-64.
- Compiled RTF primitive, arithmetic, curve, warp, gradient, terrace, spline, line, and climate noise graphs into a versioned native program powered by the pinned Alysara/quick-noise backend.
- Added globally aligned 32x32 per-thread field tiles for normal generation, global terrain scaling, and zoomed previews.
- Added explicit generator-context cleanup, concurrent native fills, graph lifecycle tests, old-preset migration tests, affine-coordinate tests, and third-party bypass tests.

### Compatibility boundaries

- Kept river networks, neighborhood erosion, aquifers, biome routing, structures, surface rules, block placement, and external `Noise.compute` implementations outside the native graph.
- Added no executor, task, future, queue, cache hook, mixin, reflection, or compile-time dependency on C2ME; CPU-accelerated C2ME continues to own its scheduling.
- Kept QUICK_V2 independent from the QUICK_V1 cave algorithm and its optional OpenCL backend.
- Exact QUICK_V2 RTF programs are deterministic across the packaged SIMD widths after exported-field quantization.

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
