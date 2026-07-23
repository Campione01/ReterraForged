# Legacy V2 Architecture Contract

## Identity

Legacy V2 is the original RTF production implementation optimized in place.
It is not a second terrain engine trained, tuned, or fitted to resemble Legacy
output. Quick Noise is an optimization reference and may provide exact low-level
kernels, but it does not own Legacy V2 graph semantics.

This implementation-continuity rule is the primary acceptance gate. Raw-bit
parity is necessary evidence that the rule has not been violated; parity alone
does not make a replacement implementation acceptable.

## Preserved RTF Advantages

Legacy V2 must retain all of these original RTF properties:

- the existing `Noise` node classes, codecs, visitors, and preset graph builders;
- the original graph topology, seed propagation, coordinate transforms, float
  operation order, range mapping, and boundary behavior;
- branch short-circuiting in selectors such as `Blend` and `Threshold`;
- state and reuse semantics in nodes such as `Cache2d`;
- the existing continent, terrain, river, wetland, erosion, climate, coast,
  island, and tile-filter pipelines;
- existing `Cell`, `NoiseRouter`, biome, structure, surface, aquifer, and chunk
  generation integration points observed by other mods;
- scalar ownership for unknown or third-party `Noise` implementations unless
  they explicitly opt into a proven compatible batch contract.

## Allowed Optimizations

An optimization belongs in Legacy V2 only when the original RTF class or its
RTF-owned runtime remains authoritative. Examples include:

- hoisting immutable work without changing per-sample arithmetic;
- reusing temporary storage owned by the current worker;
- node-owned exact batch kernels;
- SIMD or native kernels that implement the original RTF formula, seed rules,
  and float operation order exactly;
- coordinate batching that preserves the raw float coordinates produced by the
  original caller;
- cache improvements that preserve the original key, lifetime, invalidation,
  and branch-evaluation behavior.

Each optimization must be independently removable. Unsupported nodes use the
canonical original scalar implementation, not an approximation.

## Forbidden Substitutions

Legacy V2 must not:

- compile the whole RTF graph into the Quick V2 semantic frontend;
- intercept every nested `Noise.compute` call through a global dispatcher;
- replace original nodes with visually similar Quick Noise primitives;
- reassociate float expressions, quantize fields, or tune output back toward a
  reference image;
- evaluate both sides of a branch that Legacy conditionally evaluates;
- bypass `Cache2d` or other stateful original behavior merely to admit a graph;
- move rivers, erosion, climate, biomes, structures, surfaces, aquifers, or
  third-party density functions into the optimized noise backend.

## Engine Isolation

The development UI exposes three independently selected semantic engines:

- `Legacy`: the original scalar RTF algorithm and the in-build semantic oracle;
- `Quick V2`: the frozen whole-root compiled Quick Noise route;
- `Legacy V2`: the in-place original-RTF optimization route defined here.

New and field-missing presets select `Legacy V2` by default. Explicit selections
of any of the three engines remain stable when presets are copied or decoded.

Raw-bit-safe low-level improvements to the original RTF implementation may be
shared by Legacy and Legacy V2. That does not merge their semantic routes, but it
does mean the Legacy option inside the modified jar is not the final performance
baseline. Formal release comparisons use the separately supplied original-only
RTF jar.

Selection happens once when `GeneratorContext` is constructed. Legacy and
Legacy V2 do not initialize or traverse Quick V2. Quick V2 enters only through
explicit RTF-owned root calls, so nested original nodes and third-party nodes do
not accidentally cross engine boundaries.

All three remain only while this isolation is useful for validation. The final
product may converge on Legacy V2 alone if it preserves RTF behavior and wins the
real workload performance and compatibility gates.

## Acceptance Gates

1. **Source continuity:** an audit must show that production presets still build
   and execute the original RTF nodes and terrain modules. No output-clone route
   can satisfy this gate.
2. **Mathematical identity:** default, archipelago, and personal presets must
   match Legacy at raw float bits and discrete `Cell` fields across every active
   terrain family, oceans, coasts, rivers, lakes, wetlands, climate, islands,
   erosion, smoothing, correction, negative/far coordinates, affine transforms,
   and seed boundaries.
3. **Behavior continuity:** short-circuits, stateful caches, fallback ownership,
   thread isolation, and failure behavior must remain equivalent.
4. **Mod compatibility:** the existing RTF-facing biome, structure, density,
   surface, aquifer, C2ME CPU, preview, and chunk-generation contracts must remain
   unchanged.
5. **Measured value:** optimizations must improve representative client preview
   and sustained server/chunk-generation workloads without unacceptable heap or
   latency regressions. Higher batch coverage is not itself a success metric.
6. **Independent release baseline:** final mathematical and performance claims
   compare `E:\Zero\1.21.1\mods\reterraforged-0.0.6.jar.disabled` with the final
   modified jar in separate JVMs and separate runtime directories. Both runs use
   the same Java, mod set, preset, seeds, coordinates, thread count, warmup, and
   chunk corpus; execution order is alternated across repeated trials. This gate
   includes repeatable server workloads and separate silent client sessions on the
   established other Windows desktop, using matched preview and world-traversal
   actions. Same-jar
   engine comparisons are development diagnostics only.

## Current Evidence

- Canonical `Noise.compute` ownership has been restored to the original RTF node
  classes. Quick V2 is restricted to explicit root entry points.
- The experimental Legacy V2 root batching path calls node-owned `Noise.fill`
  implementations and keeps unknown and stateful roots scalar. It is disabled by
  default because representative Tile tests have not shown a net win.
- Primitive, arithmetic, affine, Warp, and Domain batch paths pass raw-bit tests.
- Default and archipelago production-preview pipelines pass raw-bit and discrete
  parity through terrain, hydrology, climate, and filters.
- A branch-preserving indexed batch experiment for `Blend` and `Threshold`
  increased admitted roots but worsened the preliminary Tile median from about
  3 percent behind Legacy to about 15.5 percent behind. It was removed because
  it lost an original RTF performance advantage despite exact output.
- Exact native Perlin kernels were 2.4-5.2x faster in isolation but slowed the
  full Tile path because per-node native crossings dominated. They remain an
  explicit experiment and are disabled by default.
- Legacy V2 now precomputes the unchanged erosion-strength expression once per
  Cell for the erosion phase, using a concurrency-safe reusable array pool. The
  original dynamic height range calculation and all erosion operations remain in
  their original order. Default and archipelago pipelines remain raw-bit equal.
- Original RTF Perlin sampling now reuses corner coordinate hash products without
  changing the hash, gradient table, interpolation, or float expression. A
  600,000-case raw-bit replay passed; the 24-direction primitive candidate was
  about 1.11x faster in its isolated Java 25 microbenchmark.
- RTF-owned worker threads carry their selected root session directly, avoiding a
  per-root ThreadLocal map lookup. Other executors keep the ThreadLocal fallback, so
  the optimization does not couple RTF to C2ME or third-party thread ownership.
- Stable `Map` input bounds and `Blend` thresholds are derived once at original-node
  construction. Sampling order, short-circuit behavior, codecs, visitor rebuilding,
  and the original float expressions remain unchanged.
- `RiverCarver` now evaluates its original valley-distance rejection before pure
  mountain/fade calculations. A 250,000-case independent replay of the old carver
  formula and full production parity passed; no river bounds, warp, width, wetland,
  lake, or affected-sample mutation semantics changed.
- Erosion brush indices and normalized weights keep their original values and order
  but share one packed array per brush, with the float weight retained by raw bits.
  An independent replay of the old builder and full production parity passed; the
  change reduces paired hot-loop loads and halves brush-array object count.
- Legacy V2 further represents those same brush points as shared relative-offset
  templates. The 81 unique templates are concatenated into one `int[]` offset
  array and one `float[]` weight array; each Cell stores only a start and byte
  length. Every reconstructed absolute Cell index and normalized-weight raw bit
  matches the original builder at all tested boundary shapes. A 160-square filter
  map therefore replaces 25,600 point arrays with two contiguous arrays. The
  shared-template change improved alternating 32-Tile diagnostics by about
  5.17 percent, the split representation added about 1.92 percent, and flattening
  those split templates added about 1.34 percent across four paired JVM trials.
- Constant-bound `Map` nodes retain the original interpolation expression while
  avoiding two recursive bound queries and two temporary bulk arrays. A 500,000-case
  raw-bit replay, production parity, an isolated Java 25 kernel test, and alternating
  production-Tile trials all passed.
- Island climate selection reuses the raw temperature and moisture values already
  sampled at the same biome-region center. Legacy V2 also keeps a one-region
  worker-local climate cache for repeated biome and highland centers, including the
  pure continent-land value at that exact center. The cache is owned only by RTF
  worker tasks and is cleared in `finally`; Legacy, Quick V2, C2ME, and third-party
  threads do not use it. A dedicated WorkerThread regression now exercises first
  misses, repeated hits, cross-region invalidation, return visits, and highland
  overrides while comparing every affected float by raw bits.
- Climate's fixed 3x3 Voronoi scan advances the original integer coordinate-prime
  products by addition and calls the same hash with those products. A one-million
  case vector-identity replay and production parity passed; alternating Tile trials
  showed about a 0.62 percent aggregate diagnostic gain.
- `RegionModule` reuses the exact `Vec2f` reference already obtained for its winning
  Voronoi candidate when calculating the center. The old lookup remains as the
  pathological no-winner fallback. Three alternating Tile pairs showed about a
  0.77 percent aggregate diagnostic gain with essentially flat medians.
- Wetlands now reject samples outside their line radius before calculating pure
  height and mountain fades. A wetland-covered production corpus remained exact
  and showed a small positive Tile signal.
- Legacy V2 builds the same RTF river tree with conservative bounds attached to
  each child subtree and traverses it through a dedicated `LegacyV2Rivermap`.
  Legacy and Quick V2 retain the author's original `Rivermap` and unconditional
  child traversal. A 300,000-point multi-level warped river corpus, including
  lakes and wetlands, matched height, river mask, erosion mask, and terrain by raw
  bits while forcing more than 100,000 child-pruning opportunities. Default and
  archipelago production pipelines also remained exact. Eight alternating JVM
  trials improved totals from 2694.739 to 2652.563 ms, about 1.57 percent, and
  combined medians by about 1.79 percent.
- Gradient table replacement and final-octave loop splitting were measured and
  rejected. Legacy V2 retains the original RTF gradient tables and compact octave
  loops because the alternatives were slower on the Java 25 production toolchain.
- A Perlin row-seed XOR common-subexpression candidate was also rejected after
  exact replay: it slowed the current 8-direction and 24-direction kernels to about
  0.933x and 0.892x, indicating that the current shape is friendlier to Java 25 JIT.
- Grouped constant-operand specializations, a mirrored erosion-height array, and a
  height-only second erosion sample all passed semantic checks but lost or failed
  to improve representative Tile timings. None remains in production.
- Caching biome-region IDs or the nine Voronoi cell-vector references did not clear
  the workload gate. Region-ID reuse was effectively neutral and cell-vector reuse
  regressed about 3.8 percent, so neither remains.
- Precomputing the final Perlin-family mapping range added object state and regressed
  the alternating aggregate by about 1 percent. The original compact expression was
  restored.
- Removing the positive-gain lower clamp from `PerlinRidge`, priming every other
  continent/region/Worley hash loop, and pairing Warp Domain X/Z evaluation all
  passed exact-output gates but failed the representative workload gate. The generic
  Domain pair was about 0.59 percent slower in aggregate; a no-pack DirectionWarp
  specialization had a slightly positive total but a roughly 1.73 percent worse
  median. None remains in production.
- A Legacy V2 erosion candidate bypassed the already-known non-null check around
  cached strength modifiers while preserving the original modifier call and every
  mutation. Default and archipelago parity passed, but two alternating trial pairs
  totalled 1364.149 ms for the candidate versus 1349.112 ms for the current path,
  about a 1.11 percent regression. Java 25 already optimizes the compact original
  shape effectively, so the duplicated hot loops were removed.
- Dedicated Legacy V2 execution classes for CURVE3 `Perlin` and `PerlinRidge`
  reproduced 750,000 random graph samples by raw float bits and passed the complete
  default and archipelago production pipeline. They nevertheless totalled
  2746.849 ms versus 2682.856 ms for the original classes across eight alternating
  JVM trials, about a 2.38 percent regression. Their combined medians were also
  about 0.54 percent slower, so the wrappers and their construction scope were
  removed in full.
- Packing each flat erosion-template start and length into one integer preserved
  every brush point and passed full production parity, but two independent JVM
  pairs totalled 1349.825 ms packed versus 1330.582 ms for separate arrays, about
  a 1.45 percent regression. The original flat start/byte-length metadata remains.
- The frozen final NeoForge Jar
  `CA237BFCA4D931F21C08A77B8949C8E7AD02E1D9E4E83D281D659039CFEFFD7F`
  was compared in separate JVMs against the user-supplied original-only Jar
  `A75ED34A2FA36F3222C75F193143A711589DF2E0078B14152FA582AF53E9D1E9`.
  With OpenCL forced off, two AB/BA server rounds improved median generation
  from `41.153 s` to `35.824 s`, or `14.88%`.
- The separate-desktop client AB/BA used the same independent Jars, C2ME CPU,
  the compatibility mod set, a null audio backend, and a fixed 32-second
  post-join window. Median saved chunks increased from `2540.5` to `2776`
  (`9.27%`), while median player-join time fell from `29.348 s` to `28.546 s`
  (`2.81%`). Same-Jar timings remain diagnostics and are not used for either
  release percentage.
