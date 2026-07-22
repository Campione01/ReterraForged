# QUICK_V2 Implementation Plan

## Objective

QUICK_V2 is a native CPU terrain-noise execution engine backed directly by the pinned
`Alysara/quick-noise` revision `baae360ff02626b58ff56c7bd46087d024fa8407`.
It replaces the scalar, point-at-a-time evaluation of RTF-owned 2D noise graphs
with fixed-shape bulk evaluation while preserving legacy RTF node semantics and
Minecraft-facing worldgen contracts. Exported-field quantization means it is not
a byte-compatibility promise for already generated chunks at threshold boundaries.

The first release targets Windows x86-64 and packages scalar, SSE4.2,
AVX2+FMA, and AVX512+FMA native variants. QUICK_V2 requires SSE4.2; the scalar
variant remains available to QUICK_V1. OpenCL and the existing QUICK_V1 cave
field remain independent. Presets without an explicit engine field select
QUICK_V2.

## Non-Negotiable Boundaries

- quick-noise is the production SIMD and batch-execution substrate. Generic
  quick-noise primitives remain available where their contracts are exact.
- RTF owns graph compilation, exact RTF opcodes, tile layout, and conversion from
  field buffers to existing `Cell` values. A dedicated native RTF opcode is used
  whenever a generic quick-noise primitive cannot preserve the Java contract.
- Biome selection, TerraBlender/Biolith routing, river networks, hydraulic
  erosion, aquifers, surface rules, structure placement, beardifier density,
  block placement, and third-party density functions never enter the native
  terrain program.
- QUICK_V2 imports, reflects on, or mixes into no C2ME class. It owns no
  executor, future, chunk status, or scheduler. Existing RTF tile workers call
  the native engine synchronously.
- Other mods continue to observe the existing `NoiseRouter`, `CellSampler`,
  `Climate.Sampler`, biome registry, structure placement, and chunk-status
  interfaces. Competing overworld generator replacements remain mutually
  exclusive by design.

## Engine Architecture

1. Java builds the normal preset-backed RTF `Noise` graph.
2. `QuickNoiseGraphCompiler` recognizes built-in nodes and emits a versioned,
   topologically sorted program with constants, seeds, root fields, and domain
   dependencies.
3. The native ABI validates the complete program before returning an opaque
   immutable handle. A partially valid program is never executed.
4. A fixed, globally aligned 2D grid is evaluated in one JNI call. Exact RTF
   primitives preserve Java floor, hash, seed, gradient/cell, interpolation,
   octave, and range semantics. The Perlin families use quick-noise `ArchSimd`;
   remaining exact opcodes share the native batch buffers.
5. Each supported root and affine coordinate transform owns a thread-local
   64x64 result tile with two cache slots. Normal generation, global terrain
   scaling, and zoomed previews all use the same globally aligned lattice while
   retaining the original 32x32-by-eight-slot float capacity.
6. Java continues materializing the existing `Cell[]` and runs river networks,
   structural erosion, biome routing, and tile filters unchanged.
7. Unknown, third-party, structural, and dynamic-coordinate roots use the
   existing Java evaluator. Fallback is atomic for the whole root: nested child
   calls cannot re-enter QUICK_V2 and mix two fields. Decisions are cached for
   the lifetime of the generator context.

The compiler covers primitive, arithmetic, transform, curve, warp, terrace,
gradient, spline, and line nodes used by the default, archipelago, and legacy
preset builders. The neighborhood-based `Erosion` node deliberately remains on
the CPU structural path.

## Public Configuration

- Add `world.noiseEngine` with `QUICK_V2` and `LEGACY` values.
- Missing values decode as `QUICK_V2`; copied and generated presets retain the
  selected value.
- Add the selector to the first world-settings UI page with English and Chinese
  labels and tooltips.
- Keep `caves.densityAlgorithm` independent. QUICK_V2 may be combined with
  QUICK_V1 or LEGACY caves.
- Backend tuning remains automatic and is not exposed as terrain-shaping
  preset data. The selected native variant is reported at initialization and
  compiled/fallback graph counts are reported when a generator context closes.

## Determinism and Failure Policy

- Every exported QUICK_V2 field is quantized to `1/4096` after native graph
  evaluation. Intermediates remain full `f32` precision.
- QUICK_V1 retains raw-bit parity across its admitted scalar, AVX2, and AVX512
  CPU variants.
- QUICK_V2 selects one SSE4.2, AVX2, or AVX512 backend at process startup and
  never changes it while running. Exact RTF programs are bit-identical across
  the packaged scalar/SSE4.2/AVX2/AVX512 variants after exported-field
  quantization; this is checked during every native build.
- Grid calls use one fixed shape and globally aligned coordinates. Affine and
  warped sampling uses Batch evaluation over the same fixed tile shape.
- Missing native support or native runtime failure fails QUICK_V2 world setup
  clearly. It must not silently switch an existing world to LEGACY output.
- Unsupported custom graph roots use the Java root fallback by design; this is
  distinct from a native failure and is stable for the lifetime of the world.

## Delivery Stages

1. Add stage timing and deterministic corpus harnesses for the current tile
   generator. Record terrain/climate, river, filter, allocation, and total tile
   baselines.
2. Introduce ABI v2 program lifecycle and 2D primitive/root fill operations.
   Add AVX512 packaging and dispatcher selection while retaining ABI v1 caves.
3. Add the Java compiler and cover arithmetic, transforms, curves, octave
   primitives, warps, cellular/Worley-derived fields, and preset holder/seed
   handling. Reject unsupported roots atomically.
4. Add bulk terrain and climate stages with temporary SoA buffers, then
   materialize existing Cells before rivers and filters. Keep scalar preview
   and lookup behavior as a verified fallback until their bulk equivalents are
   complete.
5. Add preset/UI migration, documentation, packaged notices, and compatibility
   diagnostics.
6. Validate standalone RTF and the active integration set: C2ME, Chunky,
   TerraBlender, Quark/Biolith, Regions Unexplored, BYG, Darker Depths,
   RoadWeaver, Voxy/VWG, and representative large structure packs.

## Acceptance Gates

- Native unit tests, graph validation, panic containment, repeated create/free,
  and concurrent fill tests pass under scalar, AVX2, and AVX512 builds.
- The same seed/preset coordinates produce byte-identical exported fields for
  1, 2, 8, 16, and 32 RTF workers when the resolved native variant is fixed.
- Adjacent tiles have no coordinate seam; cached, uncached, zoomed, and direct
  lookup samples agree at shared coordinates.
- QUICK_V2-eligible roots in built-in default and archipelago presets compile;
  structural erosion remains an explicit CPU root. A synthetic third-party
  implementation bypasses the engine without JNI callbacks or output mixing.
- The representative compiled graph is at least 1.5 times as fast as LEGACY on
  the reference Ryzen 9 9955HX3D; the completed seven-round median is 2.610x.
  End-to-end Chunky median
  throughput is at least 10 percent higher in both standalone RTF and RTF+C2ME
  runs, with no more than 10 percent peak-heap growth.
- Three repeated fixed-radius new-world runs complete without mixin failures,
  deadlocks, native leaks, missing modded biome palettes, invalid structure
  placement, or chunk/block hash variation.

## Evaluated References

- Terrainy revision `d37d969360c4c8ddbbeb3892d1f9e6dfab74b03d`
  (MIT) is a Godot/FastNoiseLite mesh tool. Its configuration-to-heightfield
  layering is a useful conceptual reference, but its point-sampled mesh path is
  not a runtime dependency or performance backend for QUICK_V2. No code is copied.
