# Validation Gates

## Algorithm Correctness

- Native deterministic output at positive and negative tile coordinates.
- Scalar/AVX2 cross-backend raw-bit parity and dispatch availability without machine-specific test assumptions.
- Point sampling and bulk `fillArray` equality across mixed coordinates and heights.
- World-seed sensitivity.
- Correct negative-coordinate tile indexing, bounded eviction, and eight-thread cache access.
- External density-function visitors map the terrain child while QUICK_V1 remains an opaque custom node.

## CPU/OpenCL Parity

- Compile the real OpenCL 1.2 kernel on the selected hardware.
- Compare three complete tiles, 98,304 values, by raw float bits against the native CPU backend.
- Reject the GPU for the session on the first mismatch; approximate tolerances never authorize use.
- Test unavailable, busy, rejected, and failed GPU paths through the native CPU fallback.

## Mod Compatibility

- Router graph audit proves exactly one QUICK_V1 custom node and no offload of biome, river, erosion, aquifer, structure, surface, ore, or block-placement logic.
- Fresh and field-missing presets select terrain `LEGACY_V2` plus caves `LEGACY_V2`; fresh and mode-missing OpenCL configuration selects `OFF`.
- The old generic graph compiler must reject QUICK_V1 rather than partially compiling around its custom node.
- C2ME DFC source audit confirms unknown-node single and bulk delegation.
- Static source audit requires zero C2ME imports/references and zero executor/future ownership in QUICK_V1/OpenCL code.
- Runtime smoke matrix: RTF alone, RTF plus CPU C2ME/DFC, and the user's biome/structure mod set.

## Build And Runtime

- Rebuild all native variants after every Rust source change.
- Run Rust tests and the full common and NeoForge JUnit suites under Java 25.
- Package the NeoForge jar and verify native binaries, the complete third-party notice/license set, translations, mixins, and density codec resources in the archive.
- The NeoForge packaging gate requires zero root `org.lwjgl.opencl` entries, exactly one `org.lwjgl:lwjgl-opencl:3.3.3` JarJar declaration, no nested LWJGL core, no explicit OpenCL module descriptor, and the automatic module name `org.lwjgl.opencl`.
- Launch the real client mod set with RoadWeaver and require Java module resolution to pass with one selected OpenCL library. Also launch a headless server without LWJGL core and require native CPU fallback rather than module-resolution failure.
- Launch a new dedicated test world under Java 25, generate a fixed chunk radius, stop cleanly, and inspect logs for mixin, native, OpenCL, C2ME, structure, and worldgen errors.

## Performance

- Native and OpenCL timings include JNI/seed upload/dispatch/readback and use complete canonical tiles.
- Final local hardware evidence over 64 complete 32-cubed tiles: native AVX2 `1.1208 ms/tile`; NVIDIA RTX 5090 Laptop GPU OpenCL `0.1150 ms/tile` (about `9.75x`). The same test also requires raw-bit equality.
- AUTO performs its own local comparison and does not assume those development numbers apply to another machine.
- Final acceptance requires a Java 25 new-world generation run showing higher total chunks/s without worse p95 tick behavior or C2ME worker stalls.
