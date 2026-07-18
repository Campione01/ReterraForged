# ReTerraForged

![ReTerraForged](neoforge/src/main/resources/logo.png)

Terrain generation for Minecraft 1.21.1 on NeoForge and Fabric, with configurable archipelagos and an optional compatibility-focused native/OpenCL backend for the QUICK_V1 3D cave-density workload.

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

## Sources and attribution

- [Alysara/quick-noise](https://github.com/Alysara/quick-noise) revision `baae360ff02626b58ff56c7bd46087d024fa8407` is used directly by the native QUICK_V1 CPU backend under MIT or Apache-2.0.
- [FastFlow: GPU Acceleration of Flow and Depression Routing for Landscape Simulation](https://www-sop.inria.fr/reves/Basilic/2024/JKGFC24/FastFlowPG2024_Author_Version.pdf) informed regular-grid batching, fused accelerator work, and transfer/synchronization boundaries. Its flow-routing algorithms and code are not included.
- [C2ME](https://github.com/RelativityMC/C2ME-fabric/tree/d06faf730725ac2e5874d694ccb28412eb655328) informed density-function and worker-scheduling compatibility boundaries. No C2ME source, kernel, runtime, executor, or cache implementation is copied.
- The [Khronos OpenCL 1.2 specification](https://registry.khronos.org/OpenCL/specs/opencl-1.2.pdf) defines the compute API and language targeted by the original RTF kernel.
- LWJGL OpenCL 3.3.3 is distributed as a loader-deduplicatable nested dependency under BSD-3-Clause.

Full notices and dependency licenses are in [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) and `native/licenses`. Detailed design records are in [docs/opencl](docs/opencl/README.md), and the synchronized change record is in [CHANGELOG.md](CHANGELOG.md).
