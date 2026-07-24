# Change Log

## 2026-07-24 - Legacy V2 semantic restoration

### Fixed

- Restored the original RTF river-tree construction and unconditional recursive
  traversal for Legacy V2. The removed child-bound optimization could skip valid
  warped tributaries, river banks, lakes, and wetlands, producing missing rivers
  and axis-aligned terrain truncation.
- Restored the exact Legacy final-density graph for the Legacy V2 cave option.
  It no longer adds an outer `cache_once(sloped_cheese)` marker or changes
  production `NoiseChunk` cache ownership.
- Restored the original scalar bound-query and branch-evaluation behavior for
  `Map` and `Blend`. Exact batch work remains isolated to explicit Legacy V2
  batch fills.
- Removed the speculative `CellSampler` and `Tile` border changes used during
  diagnosis; those classes were already byte-identical in the original and
  affected jars.
- Added a narrowly scoped runtime repair for preset packs written by the
  affected Quickterraforged builds. It removes only holder-backed
  `cache_once` markers whose mapped subtree contains an RTF `CellSampler`;
  normal A75/current graphs and unrelated cache markers remain unchanged.

### Validation

- Compared the user-supplied original jar
  `A75ED34A2FA36F3222C75F193143A711589DF2E0078B14152FA582AF53E9D1E9`
  with Legacy V2 using independently generated Legacy and Legacy V2 datapacks.
- Added a real production `NoiseChunk` regression covering 688,128 density and
  material samples across center and tile-seam chunks with real RTF tiles and
  cross-chunk fallback. A separate 45-chunk, three-seed experiment replayed
  17,879,040 samples while auditing the removed `cache_once` graph.
- Added a deterministic counterexample showing why child river bounds cannot
  replace the original traversal.
- Replayed all three erosion representations across 36,864 cells and verified
  raw-bit equality for height, erosion height, and sediment.
- Reproduced the historical failure through the real `MixinRandomState` and
  `NoiseChunk` lifecycle, then verified all 114,688 raw density values in the
  affected chunk after repair with zero bit differences. The guard repaired
  exactly the two vulnerable historical edges and left ordinary and inline
  third-party cache graphs intact.
- Generated independent worlds with Java 25 on a silent separate desktop and
  verified exact terrain-bearing chunk data at river/wetland `(505,583)`,
  inland `(729,159)`, and archipelago/ocean `(1833,-1377)`: block states,
  biomes, heightmaps, structures, post-processing, status, and terrain geometry
  all match the original-only jar. Full stable NBT also matched at the river
  and ocean samples; the inland sample differed only by three shutdown-timed
  `fluid_ticks`, with identical generated blocks and heightmaps.
- Loaded the actual stale preset ZIP in the full client pack and generated the
  reported region successfully. Its remaining derived-height and base-occupancy
  mismatch rates against A75 were both below an independent A75-versus-A75
  repeat run, while the deterministic density comparison remained bit exact.
- Repeated the river workload in `original/final/final/original` order with
  C2ME and OpenCL disabled. Mean start-region-to-player time fell from
  `15.586 s` to `12.819 s` (17.76% less time, 21.59% higher effective speed).
  Whole-process startup was excluded because one original-jar trial stalled
  before integrated-server startup.

## 2026-07-23 - Preset editor and density correctness

### Fixed

- Restored loading for presets that still encode the legacy `USER_SELECTED` spawn type or `UPLIFT` continent type, while keeping current names on save.
- Cached preset decode results by exact modification time and size so an unchanged invalid file is reported once instead of being reparsed on every page rebuild.
- Bounded preview generation to four cancellable jobs, debounced slider regeneration, discarded superseded work, and closed failed preview tiles.
- Limited the structure page to verified overworld random-spread sets, bounded long labels, and completed preview click/drag/release forwarding so the preview cannot capture unrelated slider drags.
- Preserved the real source path of presets loaded from the legacy config directory and de-duplicated same-name legacy/current entries, fixing edits and deletes targeting the wrong file.
- Replaced OpenCL density-cycle reuse based on mutable provider identity with exact XYZ and raw CPU-input batch matching. Changed batches now execute afresh or fall back to CPU.
- Withdrew the generic `LEGACY`/`LEGACY_V2` final-density OpenCL path after full-world client tests proved that first-batch kernel verification was insufficient to guarantee world parity. These cave modes now use the CPU backend even when OpenCL is enabled; the independently verified complete-tile `QUICK_V1` GPU backend remains available.

### Validation

- Added full-preset legacy decode coverage and mutable `NoiseChunk` batch regression tests.
- Verified Quick V2 against Legacy at the reported coordinates and seed across adjacent production tiles and the complete cached `CellSampler` path, including tile seams.
- Verified the unchanged strata resources and rules against the baseline; the reported layer distortion was a downstream exposure of incorrect solid/air geometry rather than a separate strata algorithm change.
- Repeated fixed-seed, fixed-spawn client generation in `OFF/ON/ON/OFF` order with C2ME: all terrain-core, heightmap, underground, strata, biome, and structure comparisons stayed within the independent repeat-run floor after the safe Legacy V2 CPU fallback.

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
