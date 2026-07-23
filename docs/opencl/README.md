# RTF OpenCL Knowledge Base

This directory is the source of truth for the QUICK_V1 CPU/OpenCL backend.

## Documents

- [references.md](references.md): pinned references, transferable ideas, and license boundaries.
- [compatibility-contract.md](compatibility-contract.md): systems the backend may and may not own.
- [architecture.md](architecture.md): backend lifecycle and data-flow decisions.
- [validation.md](validation.md): correctness, compatibility, and performance gates.

## Current Scope

The QUICK_V1 backend accelerates only its deterministic 3D cave field. The generic admitted-density path can also accelerate supported interpolated children of the legacy graph. RTF 2D terrain fields, biomes, structures, beardifier effects, aquifers, surface rules, block placement, rivers, erosion, climate, and third-party custom density functions remain on their existing CPU paths.

OpenCL is optional and defaults to `OFF`. `AUTO` and `ON` are explicit opt-in modes. A missing binding/device, busy queue, failed exact-parity gate, AUTO performance rejection, or runtime error uses the matching CPU path. `LEGACY_V2` is the default cave mode and remains raw-bit equivalent to `LEGACY`; QUICK_V1 is a new-world algorithm and does not promise legacy seed/output compatibility.
