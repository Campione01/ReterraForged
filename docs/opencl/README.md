# RTF OpenCL Knowledge Base

This directory is the source of truth for the QUICK_V1 CPU/OpenCL backend.

## Documents

- [references.md](references.md): pinned references, transferable ideas, and license boundaries.
- [compatibility-contract.md](compatibility-contract.md): systems the backend may and may not own.
- [architecture.md](architecture.md): backend lifecycle and data-flow decisions.
- [validation.md](validation.md): correctness, compatibility, and performance gates.

## Current Scope

The backend accelerates only QUICK_V1's deterministic 3D cave field. RTF 2D terrain fields, biomes, structures, beardifier effects, aquifers, surface rules, block placement, rivers, erosion, climate, and third-party custom density functions remain on their existing CPU paths.

OpenCL is optional. A missing binding/device, busy queue, failed exact-parity gate, AUTO performance rejection, or runtime error computes the same tile through pinned quick-noise native SIMD. QUICK_V1 is a new-world algorithm and does not promise legacy seed/output compatibility.
