# ReTerraForged

![ReTerraForged](neoforge/src/main/resources/logo.png)

Terrain generation for Minecraft 1.21.1 on NeoForge and Fabric, with configurable archipelagos, a native quick-noise CPU engine for 2D terrain fields, and an optional OpenCL backend limited to QUICK_V1 cave density.

## Island generation

- Optional archipelagos with configurable density, size, height, and terrain scale.
- Dedicated island mountains and volcano-shaped peaks.
- Adjustable offshore depth, beach width, beach coverage, and coast transitions.
- Island-aware climate routing and beach-biome selection that avoids stone-shore dominance.

## QUICK_V1 and OpenCL

- QUICK_V1 is a new-world cave-density algorithm with a native scalar/AVX2 CPU backend and an optional fused OpenCL 1.2 backend.
- Only QUICK_V1's pure 3D cave field may run on the GPU. Terrain, biomes, rivers, erosion, aquifers, structures, surface rules, block placement, and third-party density functions stay on their normal CPU paths.
- `AUTO` requires raw-bit CPU/GPU parity and a successful speed gate. Any unsupported, busy, rejected, or failed OpenCL path immediately uses the native CPU backend for that tile.
- RTF owns no C2ME executor, task, future, queue, cache, or OpenCL resource. The backend remains opaque to C2ME's density-function compiler and is designed for coexistence with CPU-accelerated C2ME builds.

QUICK_V1 intentionally changes cave output and does not preserve legacy world or seed output. Select `LEGACY` in the cave settings for the older algorithm.

## QUICK_V2 2D noise engine

- QUICK_V2 is the default for presets that do not specify `world.noiseEngine`. It compiles supported RTF-owned terrain and climate noise graphs directly to the pinned quick-noise Grid/Batch backend.
- Windows x86-64 dispatch selects SSE4.2, AVX2+FMA, or AVX512+FMA once per process. Fixed 32x32 tiles support normal generation, global terrain scaling, and zoomed previews without creating executors or changing chunk scheduling.
- River networks, neighborhood erosion, biome selection, aquifers, structures, surface rules, block placement, and third-party `Noise.compute` implementations remain on their existing CPU/Minecraft paths.
- QUICK_V2 and `LEGACY` are new-world algorithms with different terrain output. Cave `QUICK_V1`/`LEGACY` selection remains independent.
- CPU-accelerated C2ME can coexist with QUICK_V2 because RTF owns no C2ME task, queue, future, density compiler, or cache integration.

## Sources and attribution

- [Alysara/quick-noise](https://github.com/Alysara/quick-noise) revision `baae360ff02626b58ff56c7bd46087d024fa8407` is used directly by the native QUICK_V1 and QUICK_V2 CPU backends under MIT or Apache-2.0.
- [FastFlow: GPU Acceleration of Flow and Depression Routing for Landscape Simulation](https://www-sop.inria.fr/reves/Basilic/2024/JKGFC24/FastFlowPG2024_Author_Version.pdf) informed regular-grid batching, fused accelerator work, and transfer/synchronization boundaries. Its flow-routing algorithms and code are not included.
- [C2ME](https://github.com/RelativityMC/C2ME-fabric/tree/d06faf730725ac2e5874d694ccb28412eb655328) informed density-function and worker-scheduling compatibility boundaries. No C2ME source, kernel, runtime, executor, or cache implementation is copied.
- [Terrainy](https://github.com/sempitern0/Terrainy/tree/d37d969360c4c8ddbbeb3892d1f9e6dfab74b03d) informed the configuration-to-heightfield layering review. It is an MIT-licensed Godot mesh tool; no Terrainy code or runtime dependency is included.
- [Screaming Brain Studios' Noise Texture Pack](https://screamingbrainstudios.itch.io/noise-texture-pack) was evaluated as a CC0 source for optional masks and visual fixtures. No texture from the pack is bundled or sampled by world generation.
- The [Khronos OpenCL 1.2 specification](https://registry.khronos.org/OpenCL/specs/opencl-1.2.pdf) defines the compute API and language targeted by the original RTF kernel.
- LWJGL OpenCL 3.3.3 is distributed as a loader-deduplicatable nested dependency under BSD-3-Clause.

Full notices and dependency licenses are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and `native/licenses`. Detailed design records are in [docs/opencl](docs/opencl/README.md) and [docs/quick-v2](docs/quick-v2/implementation-plan.md), and the synchronized change record is in [CHANGELOG.md](CHANGELOG.md).
