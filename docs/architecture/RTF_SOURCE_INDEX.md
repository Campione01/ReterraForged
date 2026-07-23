# ReTerraForged Source Index

## Primary Sources

| Source | Revision used | Role in this project |
| --- | --- | --- |
| User-supplied original-only 1.21.1 jar | SHA-256 `A75ED34A2FA36F3222C75F193143A711589DF2E0078B14152FA582AF53E9D1E9` | Authoritative executable and numeric terrain baseline |
| This repository before QUICK | `a49817b` | Historical source reference; not byte-identical to the authoritative jar |
| [ReTerraForged](https://github.com/racoonman2/ReTerraForged) | branch `1.20.2`, `98fd3a28f2ebd638319e797a9138a3418484150f` | Modern upstream architecture, preview and port lineage |
| [TerraForged](https://github.com/TerraForged/TerraForged) | branch `0.3.x`, `6dd607ebfd41a3b7274e090b1f2cf565d53d1d44` | Original terrain, continent, river and filter design lineage |
| [quick-noise](https://github.com/Alysara/quick-noise) | production pin `baae360ff02626b58ff56c7bd46087d024fa8407`; research checkout `ece6ec70178e6de1457c418b81bb73a6d4307c98` | SIMD/batch execution substrate, not an RTF semantic specification |

The quick-noise dependency is licensed `MIT OR Apache-2.0`; the pinned license
texts and source coordinate are packaged under `native/licenses/quick-noise`.

## Local Semantic Entry Points

| Concern | Files/classes to read first |
| --- | --- |
| Preset schema and migration | `Preset`, `WorldSettings`, terrain/river/climate/filter settings |
| Registry-backed graph construction | `PresetNoiseData`, `PresetTerrainTypeNoise`, `PresetClimateNoise`, `PresetNoiseRouterData` |
| Context and lifetime | `GeneratorContext`, `RTFRandomState` |
| Full 2D composition | `Heightmap.make`, `Heightmap.applyTerrain/applyRivers/applyClimate` |
| Continents | `cell/continent`, especially continent implementations and `Continent` contract |
| Terrain ownership | `RegionModule`, `RegionSelector`, `TerrainProvider`, `Populators`, blenders/lerpers |
| Rivers | `cell/rivermap`, especially `Rivermap`, river generators and `RiverCarver` |
| Climate | `cell/climate/Climate` and biome type lookup |
| Tile execution | `TileGenerator`, `Tile`, `WorldFilters`, filters under `densityfunction/tile/filter` |
| Minecraft bridge | `WorldLookup`, `CellSampler`, `PresetNoiseRouterData`, mixins around `RandomState`/`NoiseChunk` |
| QUICK compiler/runtime | `QuickNoiseGraphCompiler`, `QuickNoiseRuntime`, native `quick-noise-backend` |
| OpenCL boundary | `worldgen/opencl`, `docs/opencl/compatibility-contract.md` |
| Create-world preview | `PresetEditorPage.Preview`, `RenderMode` |

## Evidence Discipline

- Independent output from the authoritative original-only jar settles numeric
  behavior. `LEGACY` inside the modified jar is a development oracle, not the
  release baseline.
- Upstream code explains intent and ownership but cannot override a 1.21.1
  same-input result.
- quick-noise documents primitive execution and SIMD layout; it does not define
  RTF hashes, seeds, coordinate scopes, region selection, rivers or Cell fields.
- A paper or another terrain project may motivate an optimization only after its
  behavior is mapped to an existing RTF-owned contract and covered by parity
  tests.
