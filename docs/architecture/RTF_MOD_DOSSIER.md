# ReTerraForged Mod Dossier

This document is the ownership and data-flow reference for terrain work in this
repository. It exists to prevent a backend optimization from being treated as a
replacement for ReTerraForged's world-generation model.

## Semantic Baseline

The truth hierarchy for terrain restoration is:

1. Commit `a49817b` in this repository: the exact Minecraft 1.21.1 code before
   QUICK_V1/QUICK_V2 was integrated.
2. The `LEGACY` engine in the current tree: the executable form of that same RTF
   noise semantics after unrelated port fixes.
3. ReTerraForged 1.20.2 and TerraForged 0.3.x: architectural lineage and module
   ownership references, not numeric baselines for 1.21.1.
4. Screenshots and generated worlds: runtime evidence, not a substitute for a
   same-input field comparison.

`QUICK_V2` is accepted only when it preserves the current `LEGACY` Cell contract.
Performance, compilation success, and visually similar continents are not proof
of semantic equivalence.

## End-to-End Generation Path

```text
Preset codecs and registry patch
  -> GeneratorContext(seed, levels, preset, noise lookup)
  -> Heightmap.make
       continent field and continent-owned Rivermap
       terrain RegionModule and RegionSelector
       terrain populators/blenders and ocean/coast/island lerpers
       Climate and FakeWaterBiomeResolver
  -> TileGenerator
       Heightmap.applyTerrain
       Heightmap.applyRivers
       Heightmap.applyClimate
  -> WorldFilters
       optional erosion and smoothing
       required steepness and beach detection
       optional noise correction
  -> TileCache / WorldLookup / CellSampler
  -> Minecraft NoiseRouter and Climate.Sampler
  -> biome routing, surface rules, aquifers, structures and final block state
```

The create-world preview uses the same `GeneratorContext`, `TileGenerator`,
`generateZoomed`, required filters and `RenderMode` colors. Its normal image is
256 x 256 samples (`FACTOR = 4`) and its default zoom resolves to 50 blocks per
pixel.

## Cell Contract

`Cell` is the shared semantic boundary. Downstream RTF and Minecraft code consume
its fields without knowing whether a noise root was evaluated by Java or a native
backend.

| Field group | Representative fields | Primary owner |
| --- | --- | --- |
| Continent | `continentId`, `continentEdge`, `continentDistance`, `continentX/Z` | `Continent` implementations |
| Terrain regions | `terrainRegionId`, `terrainRegionEdge`, region center, `terrain` | `RegionModule`, `RegionSelector`, terrain populators |
| Height | `height`, `heightErosion`, `sediment`, `gradient` | terrain populators and `WorldFilters` |
| Rivers | `riverMask`, river/lake/wetland terrain tags, carved `height` | continent-owned `Rivermap`, `RiverCarver` |
| Climate | region temperature/moisture, biome region, macro biome, final climate fields | `Climate` and biome type selection |
| Minecraft routing | `erosion`, `weirdness`, `biome`, `fakeWaterBiome` | `Heightmap.applyClimate`, climate and fake-water resolver |

Continuous drift is not automatically harmless. Region selection, ocean bands,
terrain categories, river fades, climate buckets and beach detection contain hard
thresholds. A small field error can therefore change a discrete owner or remove a
river at its mouth.

## Module Ownership And QUICK Boundary

| Module | Inputs | Outputs | QUICK_V2 policy |
| --- | --- | --- | --- |
| Preset/settings | JSON, UI values, registry codecs | immutable generation snapshot | Never replaced |
| RTF noise graph | seed, coordinates, built-in `Noise` nodes | scalar root values | Eligible only with proven node and graph parity |
| Continent | RTF roots, continent settings | continent identity/edge/distance and Rivermap lookup | Roots may be accelerated; selection semantics stay RTF-owned |
| Terrain regions | warped region coordinates and providers | region identity, edge, center and terrain choice | Roots may be accelerated; nearest-region and provider logic stay Java-owned |
| Terrain populators | region choice, levels, settings | terrain tag and height | Java-owned |
| Rivers/lakes/wetlands | continent Rivermap, height, terrain category/edge | `riverMask`, carved height and water terrain tag | Never compiled into QUICK_V2 |
| Climate/biome type | climate roots, continent and terrain state | climate fields and `BiomeType` | Roots may be accelerated; classification stays Java-owned |
| Erosion/smoothing/beach | complete Tile neighborhood | filtered height, gradient and beach tags | Never compiled into QUICK_V2 |
| Minecraft integration | Cell fields, registries and other mods | NoiseRouter, biomes, surfaces, aquifers, structures, blocks | Never compiled into QUICK_V2 |
| OpenCL caves | admitted final 3D cave-density subgraphs | density samples only | Independent from QUICK_V2 terrain and excludes mod-owned routing |

An unsupported, dynamic or third-party `Noise` root must fall back atomically to
the Java root. A native child must not be mixed into a Java root unless the entire
coordinate and evaluation contract is proven equivalent.

## River Dependency Chain

Rivers are not an isolated post-process. `RiverCarver` combines the Rivermap
distance field with existing Cell state, including height, terrain type and
terrain-region edge. It then writes `riverMask`, lowers height and conditionally
assigns a river/lake/wetland terrain tag. `applyClimate` later maps those results
to erosion/weirdness values and biome-facing fields.

The July 22 parity run found discrete mismatches already after `applyTerrain`.
The river-stage mismatches are therefore downstream amplification until a later
comparison proves otherwise. Output-side river patches are prohibited while an
earlier continent/region/noise mismatch exists.

## Caches, Threads And Compatibility

- `GeneratorContext` owns one optional QUICK engine for its lifetime.
- `TileGenerator` evaluates RTF cells on the RTF world-generation executor and
  invokes QUICK synchronously; QUICK owns no chunk status, executor or future.
- `TileCache`, `WorldLookup` and `CellSampler` remain the public sampling path.
- C2ME CPU may schedule or request chunks differently, but QUICK must not import,
  mix into or reflect on C2ME classes.
- TerraBlender, Biolith and biome mods continue to see the existing registry,
  Climate sampler and biome-routing contracts.
- Structures, beardifiers, aquifers, surface rules and third-party density
  functions remain outside both QUICK_V2 terrain compilation and OpenCL caves.

## Restored QUICK_V2 Semantic Contract

The original QUICK_V2 design quantized every exported native terrain root to
`1/4096`. The first strict 256 x 256 comparison found hundreds of thousands of
float mismatches plus continent-owner, terrain-region-owner and terrain-category
changes. The resulting river failures were downstream consequences of those
earlier changes, not defects in `RiverCarver` itself.

The July 22 restoration removed four sources of semantic drift:

1. terrain-root output quantization;
2. algebraic rewrites that changed Java float operation order, `Math.pow`
   behavior or branch selection;
3. `Blend` endpoint evaluation through `lerp` instead of the original lower and
   upper short-circuits;
4. reuse of a native lattice sample for a caller coordinate that was merely
   within `0.001` of that lattice point.

The expanded multi-seed matrix then found a fifth source: the legacy `Perlin`
and `Perlin2` records own fixed seeds and ignore their runtime seed argument, but
the native implementation had combined the fixed and runtime seeds. QUICK_V2
program version 6 distinguishes fixed-seed Perlin opcodes from shifted/dynamic-
seed primitives and preserves both contracts exactly.

QUICK_V2 now accepts a cached native result only when the caller coordinates have
the same raw float bits as the prepared lattice coordinates. A different or
dynamic coordinate evaluates the complete root through LEGACY. Unsupported roots
also fall back atomically. This preserves the RTF graph, seeds, hashes, operation
order and downstream module ownership; quick-noise supplies the batch/SIMD
execution substrate, not a replacement terrain formula.

The strict matrix now includes stratified native-scale deep/shallow ocean,
coast/beach, river, lake, wetland, flatland, lowland, highland and island windows;
hydraulic erosion, smoothing and correction stages; four far-coordinate seed
windows; and the complete personal-preset repetition. It executes every active
non-composite terrain type, including volcano pipe, and finishes with zero raw
float-bit, discrete and root-shadow mismatches. See `RTF_PARITY_METHOD.md` for
the executable evidence and its remaining runtime-validation boundary.

## Change Rule

Every terrain change must:

1. identify the earliest divergent stage and field;
2. explain which module owns that field;
3. add or retain a fixed same-input regression view;
4. reduce the recorded difference without compensating downstream output;
5. pass the full parity matrix before runtime compatibility testing.
