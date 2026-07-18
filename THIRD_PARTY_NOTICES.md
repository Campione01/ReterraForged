# Third-Party Notices

ReTerraForged itself is distributed under the MIT license in `LICENSE`. This file covers code and binary dependencies redistributed by the QUICK_V1 native/OpenCL backend, followed by research acknowledgments that are not bundled code.

## Alysara/quick-noise

The native QUICK_V1 and QUICK_V2 CPU backends directly use Alysara/quick-noise at revision `baae360ff02626b58ff56c7bd46087d024fa8407`.

Copyright (c) 2026 Alysara.

License: MIT OR Apache-2.0. Complete unmodified license texts and source identity are in `native/licenses/quick-noise` and are packaged under `META-INF/reterraforged/licenses/quick-noise`.

Source: https://github.com/Alysara/quick-noise/tree/baae360ff02626b58ff56c7bd46087d024fa8407

## LWJGL OpenCL 3.3.3

The loader artifacts contain `org.lwjgl:lwjgl-opencl:3.3.3` as a nested, deduplicatable OpenCL Java binding.

Copyright (c) 2012-present Lightweight Java Game Library. All rights reserved.

License: BSD-3-Clause. The complete required copyright notice, redistribution conditions, and disclaimer are in `native/licenses/lwjgl-opencl/LICENSE-BSD-3-CLAUSE` and are packaged with the mod.

Source: https://github.com/LWJGL/lwjgl3/tree/3.3.3

## Rust Binary Dependencies

The packaged Windows native libraries statically link Rust dependencies resolved by `native/Cargo.lock`. Their package/version/license inventory and complete distributed license files are under `native/licenses/rust-dependencies` and are packaged with the mod. This includes the ISC notice for `libloading` and the Unicode-3.0 terms required by `unicode-ident`.

## Research and compatibility acknowledgments

The following sources informed architecture or compatibility decisions. Their source code, kernels, paper implementations, and binaries are not copied into ReTerraForged:

- Aryamaan Jain, Bernhard Kerbl, James Gain, Brandon Finley, and Guillaume Cordonnier, "FastFlow: GPU Acceleration of Flow and Depression Routing for Landscape Simulation," Computer Graphics Forum 43(7), Pacific Graphics 2024. https://www-sop.inria.fr/reves/Basilic/2024/JKGFC24/FastFlowPG2024_Author_Version.pdf
- RelativityMC/C2ME, audited at revision `d06faf730725ac2e5874d694ccb28412eb655328`, MIT, Copyright (c) 2021-2024 ishland. https://github.com/RelativityMC/C2ME-fabric/tree/d06faf730725ac2e5874d694ccb28412eb655328
- sempitern0/Terrainy, audited at revision `d37d969360c4c8ddbbeb3892d1f9e6dfab74b03d`, MIT. Its configuration-to-heightfield layering was evaluated; no source or binary is redistributed. https://github.com/sempitern0/Terrainy/tree/d37d969360c4c8ddbbeb3892d1f9e6dfab74b03d
- Screaming Brain Studios, "Noise Texture Pack," CC0 1.0. It was evaluated for optional masks and visual fixtures; no texture is redistributed or sampled by world generation. https://screamingbrainstudios.itch.io/noise-texture-pack
- Khronos OpenCL Working Group, The OpenCL Specification, Version 1.2, Document Revision 19. https://registry.khronos.org/OpenCL/specs/opencl-1.2.pdf
- The locally supplied `VWG_C2ME_区块预生成性能根因分析.md` development report, used only to define non-blocking worker-ownership constraints and not redistributed here.

Names and project titles are used only to identify sources and do not imply endorsement.
