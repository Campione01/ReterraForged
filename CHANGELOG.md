# Change Log

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
