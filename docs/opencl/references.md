# Reference Ledger

This ledger distinguishes code used directly from research that only informed design decisions. Complete redistribution notices are in the repository root and under `native/licenses`.

## Alysara/quick-noise

- URL: https://github.com/Alysara/quick-noise
- Pinned revision: `baae360ff02626b58ff56c7bd46087d024fa8407`
- Copyright: Copyright (c) 2026 Alysara.
- License: MIT or Apache-2.0. Both texts are copied verbatim under `native/licenses/quick-noise` and packaged under `META-INF/reterraforged/licenses/quick-noise`.
- Direct use: the native QUICK_V1 CPU implementation calls quick-noise's `BatchNoise<3, Fbm, Perlin>` API for every octave field.
- RTF-written structure around the dependency: scalar/AVX2 runtime dispatch, contiguous x-major batches, fixed operation order, five fused 3D cave fields, and allocation-free sampling after initialization.
- Rejected backend: quick-noise's SSE4.2 path differed from scalar by one `1/4096` quantum in 21 of 131,072 audited samples. It is not dispatched or packaged; AVX2 and scalar matched exactly across another 2,097,152 randomized samples.
- Deliberate deviation: `GridNoise` is not used. Its vector-width and call-shape-dependent rounding prevented bit-identical CPU/OpenCL fallback at tile boundaries.
- Scope decision: QUICK_V1 is a new world-generation algorithm. It does not preserve legacy world or seed output; `LEGACY_V2` is the default and `LEGACY` remains the unchanged reference option.

## FastFlow (Pacific Graphics 2024)

- Paper: Aryamaan Jain, Bernhard Kerbl, James Gain, Brandon Finley, and Guillaume Cordonnier, "FastFlow: GPU Acceleration of Flow and Depression Routing for Landscape Simulation," Computer Graphics Forum 43(7), Pacific Graphics 2024.
- Author-version URL: https://www-sop.inria.fr/reves/Basilic/2024/JKGFC24/FastFlowPG2024_Author_Version.pdf
- Adopted general principles: regular-grid batching, fused accelerator work, enough samples per dispatch to amortize transfers, and no host-side synchronization inside the computation.
- Not copied or implemented: the paper's flow routing, depression routing, CUDA/PyTorch/TensorFlow implementation, rivers, erosion, climate, and RTF's 2D `CellSampler` terrain model.
- Future CPU work may apply data-layout lessons to other systems, but it must remain independent of the OpenCL backend and receive separate validation.

## Khronos OpenCL 1.2

- Specification: https://registry.khronos.org/OpenCL/specs/opencl-1.2.pdf
- Use: the specification defines the context, command-queue, buffer, kernel, OpenCL C, and numerical interfaces targeted by the original RTF kernel.
- No Khronos sample source or implementation code is copied.

## LWJGL OpenCL 3.3.3

- Project: https://github.com/LWJGL/lwjgl3/tree/3.3.3
- Copyright: Copyright (c) 2012-present Lightweight Java Game Library. All rights reserved.
- License: BSD-3-Clause. The required copyright, conditions, and disclaimer are stored under `native/licenses/lwjgl-opencl` and packaged with the mod.
- Direct use: only the Java OpenCL binding is nested. Minecraft supplies LWJGL core; the RTF jar does not expand or export `org.lwjgl.opencl` classes.

## C2ME CPU And Density-Function Compiler

- Official audit source: https://github.com/RelativityMC/C2ME-fabric/tree/d06faf730725ac2e5874d694ccb28412eb655328, branch `ver/1.21.1`, revision `d06faf730725ac2e5874d694ccb28412eb655328` at the time of audit.
- License at the audited revision: MIT, Copyright (c) 2021-2024 ishland.
- NeoForge evidence: local `C2ME-neoforge-ver-1.21.1` patch set. Its density-function patch replaces generated interface calls with static invocation shims but preserves custom-node `sample` and bulk `fill` delegation.
- Compatibility fact: C2ME's `McToAst` turns unknown custom density functions into `DelegateNode`. QUICK_V1 remains opaque, and generated C2ME code calls its own `compute`/`fillArray` implementation.
- Initialization fact: RTF seeds QUICK_V1 during the vanilla `RandomState` router mapping. C2ME compiles the already-mapped router from its constructor-return injection.
- Ownership rule: the QUICK_V1/OpenCL packages import no C2ME classes and share no executor, future, queue, OpenCL context, or cache with C2ME. The surrounding RTF mixin retains its older optional C2ME presence check for CPU terrain-tile queueing.
- No C2ME source, generated code, or binary is copied into RTF; C2ME is a compatibility research source, not a dependency.

## C2ME OpenCL

- Studied only for lifecycle and compatibility boundaries; the intended coexistence target is CPU-accelerated C2ME, not C2ME OpenCL.
- No C2ME OpenCL source, kernel, emitter, layout, or runtime implementation is copied.
- RTF owns one independent OpenCL 1.2 context and non-blocking queue gate. A busy or rejected GPU path computes the exact same QUICK_V1 tile through native quick-noise SIMD.

## VWG/C2ME Pregen Report

- Development input: the locally supplied `VWG_C2ME_区块预生成性能根因分析.md` report; it is not redistributed by this repository.
- Retained finding: synchronous tile/future acquisition can park C2ME generation workers and amplify pre-generation stalls.
- Applied constraint: the QUICK_V1 cache has no executor and no future. Concurrent misses may compute the same tile independently, then use `putIfAbsent`; OpenCL uses `tryLock`, so a C2ME worker never waits behind GPU ownership.
