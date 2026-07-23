# RTF Terrain Parity Method

`RtfSemanticParityTest` is the executable terrain-restoration loop. It compares
the current `LEGACY` and `QUICK_V2` engines with the same preset, seed, center,
coordinates, zoom and RTF pipeline.

## Equivalence Contract

The target is mathematical and semantic equivalence, not visual similarity:

- the same RTF graph, preset, seed and caller coordinates must produce identical
  raw `float` bits for every continuous `Cell` field;
- every terrain, river/lake/wetland, biome, fake-water and ownership decision
  must be identical;
- Java branch selection, integer overflow, hash/seed rules, affine coordinate
  order and floating-point operation order are part of the formula;
- a root whose complete mapping is not proven must fall back atomically to the
  LEGACY root. Mixing a native child into an unproven Java root is forbidden.

Finite samples are regression evidence, not a proof over infinitely many
coordinates. The proof obligation is discharged structurally by an exact opcode
mapping plus whole-root fallback; the corpus exercises every active branch to
catch mistakes in that mapping.

## Compared Stages

1. `Heightmap.applyTerrain`
2. `Heightmap.applyRivers`
3. `Heightmap.applyClimate`
4. the required preview filters: steepness and beach detection
5. native-scale hydraulic erosion
6. native-scale smoothing
7. native-scale steepness and beach detection
8. native-scale noise correction

The test uses the create-world preview geometry: a 256 x 256 tile and affine
zoomed coordinates. It also uses production-border native windows at zoom 1.
The stratified locator must actually hit deep ocean, shallow ocean, coast/beach,
river, lake, wetland, flatland, lowland, highland, island beach, island and island
mountains. Four deterministic far-coordinate windows exercise seeds `0`, `-1`,
`0x13579BDF` and `Integer.MIN_VALUE`.

## Compared Data

- Every continuous `Cell` field, using raw float-bit mismatch counts plus maximum,
  mean, RMSE, p50, p95 and p99 absolute error.
- Continent coordinates, erosion mask, terrain id/name/category, biome type and
  fake-water target as exact discrete values.
- The UI `BIOME_TYPE`, `TERRAIN_REGION` and `TRANSITION_POINTS` colors.
- Height, erosion, sediment, gradient, continent edge, terrain-region edge,
  river mask, temperature and moisture scalar images.

Each PNG is a three-panel image:

```text
LEGACY | QUICK_V2 | DIFFERENCE
```

The JSON report records the first mismatching sample and world coordinate for
each field. The test fails on any raw float-bit or discrete mismatch; this is an
investigation gate, not the old distribution tolerance.

When `reterraforged.quickNoise.verifyLegacy=true`, each admitted native root is
also shadow-evaluated once against the complete Java root at its real caller
coordinate. The report records the first mismatch per root. The property is
enabled by this test only and has no normal runtime cost.

## Optional Personal Preset

Set `RTF_PARITY_PRESET` to a preset JSON path. The path is deliberately not
stored in source control. When present, the ocean/coast/hydrology/land
stratification is repeated with that preset's own control points and continent
scale. Enabled archipelagos are located outside the preset's continent as well.

```powershell
$env:RTF_PARITY_PRESET = 'E:\Zero\1.21.1\config\reterraforged\presets\个人自用.json'
```

## Windows Test Command

The Gradle daemon runs on JDK 21 because Gradle 8.8/Groovy does not support JDK
25 as its host. The existing init script launches Java compilation and tests on
JDK 25 while retaining Java 21 bytecode compatibility. On paths containing CJK
characters, use the verified ASCII junction so the Java 25 argfile is decoded
correctly.

```powershell
$env:JAVA_HOME = 'C:\Program Files\Microsoft\jdk-21.0.11.10-hotspot'
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
Set-Location 'C:\Users\MSI\AppData\Local\Temp\rtf-semantic-parity'
& 'E:\构建\.toolchains\gradle-8.8\bin\gradle.bat' `
  --init-script '.planning\rtf-semantic-restoration-2026-07-22\local-repos.init.gradle' `
  --init-script 'E:\构建\.codex-plans\rtf-opencl\java25-build.init.gradle' `
  :neoforge:test `
  --tests raccoonman.reterraforged.world.worldgen.noise.module.RtfSemanticParityTest `
  --no-daemon --console=plain
```

Artifacts are written to:

```text
neoforge/build/reports/rtf-parity/report.json
neoforge/build/reports/rtf-parity/<scenario>/<stage>/*.png
neoforge/build/reports/rtf-parity/production-batch-optimization.json
neoforge/build/reports/rtf-parity/production-root-batch-selected-performance.json
```

## Correction Loop

1. Run the matrix and identify the earliest divergent stage.
2. Select the earliest field within that stage and inspect its first coordinate.
3. Trace that field to its owning RTF module and compiled root.
4. Correct the native opcode/coordinate/seed/evaluation order, or atomically
   return the whole root to LEGACY if exactness cannot be established.
5. Re-run and retain the new report.
6. Move to the next stage only when the earlier stage is exact.
7. After the matrix is exact, validate the real client preview and newly
   generated chunks with standalone RTF and RTF plus CPU C2ME.

No river, biome, beach or structure output patch is allowed to hide an earlier
continent, region or height mismatch.

## July 22 Restoration Result

The first strict run exposed the failure hidden by the former distribution tests:

| Scenario/stage | Initial float-bit mismatches | Initial discrete mismatches |
| --- | ---: | ---: |
| Default terrain | 330,756 | 18 |
| Default required filters | 491,475 | 49 |
| River-detail terrain | 370,203 | 2 |
| Archipelago terrain | 337,212 | 27 |

Removing the `1/4096` terrain-root quantization reduced all discrete mismatches
to zero. Preserving Java arithmetic, power and selection order reduced the three
terrain-stage float counts to 300, 82 and 300. Exact `Blend` branches and exact
coordinate admission removed the remaining differences.

Expanding the primitive matrix from seed zero/integer coordinates to six runtime
seeds and four affine transforms exposed another contract error: legacy `Perlin`
and `Perlin2` store a fixed seed and ignore the runtime seed argument, while the
native program had added both. Program version 6 adds dedicated fixed-seed Perlin
opcodes; shifted and dynamic-seed families keep their original rules.

The default matrix now contains 21 scenarios and 1,600,512 stage-level Cell
samples. With the optional personal preset it contains 37 scenarios and
2,590,720 stage-level Cell samples. The corpus executes every active non-composite
terrain name, including all mountain variants, mountain chain, volcano, volcano
pipe and the three generated island variants. Every compared stage reports:

```text
float-bit mismatches = 0
discrete mismatches  = 0
root mismatches      = 0
```

This is strong executable evidence for the covered RTF preview/Cell pipeline. It
does not alone prove all infinite coordinates, a modpack runtime, an existing
process or already-generated chunks. Exact opcode definitions, raw-coordinate
admission and atomic fallback therefore remain mandatory even when the corpus is
green. A fresh client preview and new-chunk test with the deployed jar are separate
runtime validation.

The optional promoted-root gate separately completes a discovery tile and then
shadow-checks later production tiles after shared programs are active. Its
default, archipelago, personal and personal-archipelago corpora cover ocean,
coast, hydrology, land and island families with zero admitted-root mismatches.
Performance changed from a 3.29 percent gain to a 2.22 percent loss when the
same balanced internal method was constrained to eight processors. Promotion
is therefore off by default and is not part of the client, server or C2ME speed
claim. Explicit opt-in uses a 32-root cap and delayed observation after four
completed tiles.

## July 23 Production Verification

The final NeoForge jar used for this verification has SHA-256
`AC9C76D2DD2A44EBE9EC7AF141EEDA306880531D8B4DFC886550F8EA21E77C4F`.
The complete Java 25 test run executed 52 tests with zero failures or errors and
two intentional skips. The multi-loader build and NeoForge OpenCL packaging gate
also passed.

Two independent Java 25 server runs enabled
`reterraforged.quickNoise.verifyLegacy`. Every accelerated production sample was
shadow-evaluated through the Java RTF graph and compared by raw float bits. Both
runs reported:

```text
RTF QUICK_V2 server parity summary: exact, compiledGraphs=45, fallbackGraphs=2, mismatches=0
```

The two fallback roots remain complete Java roots; QUICK does not mix compiled
children into an unsupported parent.

An eight-processor, below-normal-priority, C2ME-enabled server matrix used the
same final jar and changed only `noiseEngine`. Two LEGACY samples had a 58.518150
second median. Four QUICK_V2 samples had a 54.244740 second median, a 1.078780x
speedup or 7.878 percent lower generation time. This is a constrained dedicated
server throughput result, not a client preview, world-creation, frame-time or
foreground all-core claim.

The separate A/B/C matrix used the user-supplied original jar, the frozen pure
QUICK replacement and the final RTF-first optimizer. Their generation medians
were 57.088749, 61.946447 and 55.734546 seconds respectively. In this two-round
background matrix the final optimizer was 2.430 percent faster than the supplied
original and 11.146 percent faster than the pure replacement. Jar-wide changes
outside the engine mean this matrix measures complete variants, while the
same-jar matrix above isolates the engine.

Full generated chunk NBT is not a raw-bit oracle in this C2ME-enabled setup.
LEGACY-versus-LEGACY repeated generation differed in 1,315 of 7,897 terrain
projections. QUICK-versus-QUICK repeats differed in 1,372 to 1,406. The
LEGACY-versus-QUICK count was 1,397, within the QUICK repeat range. Biome and
structure projections had zero mismatches in every comparison. These stored
block and heightmap differences are therefore concurrent full-pipeline
nondeterminism, while the in-process shadow verifier establishes the admitted
RTF noise result contract.

Retained evidence:

```text
.planning/rtf-server-benchmark-2026-07-22/variant-forceload-performance-20260723-023754.json
.planning/rtf-server-benchmark-2026-07-22/variant-forceload-performance-20260723-024731.json
.planning/rtf-server-benchmark-2026-07-22/variant-forceload-performance-20260723-030113.json
.planning/rtf-server-benchmark-2026-07-22/production-parity-quick-1-20260723-030113.log
.planning/rtf-server-benchmark-2026-07-22/production-parity-quick-2-20260723-030113.log
.planning/rtf-server-benchmark-2026-07-22/region-compare-current-C-legacy-vs-quick-20260723-024731.json
.planning/rtf-server-benchmark-2026-07-22/region-compare-current-C-legacy-repeat-20260723-024731.json
.planning/rtf-server-benchmark-2026-07-22/region-compare-current-C-quick-same-round-20260723-024731.json
.planning/rtf-server-benchmark-2026-07-22/region-compare-current-C-quick-repeat-pure-20260723-024731.json
.planning/rtf-server-benchmark-2026-07-22/region-compare-current-C-quick-repeat-optimized-20260723-024731.json
```
