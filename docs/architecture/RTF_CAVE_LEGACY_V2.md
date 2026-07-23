# RTF Cave Legacy V2

## Goal

`LEGACY_V2` is the default 3D cave-density route. It optimizes the original RTF
and vanilla density graph in place; it is not a quick-noise replacement and it
must remain mathematically identical to `LEGACY`.

## Current implementation

Legacy V2 exports the same `slopedCheese` references and the same final-density
graph as Legacy. It does not add an outer `cache_once` node.

An earlier implementation added `cache_once(sloped_cheese)` to both the
`rangeChoice` input and entrance branch. Scalar, direct `fillArray`, and an
additional 45-chunk, three-seed production `NoiseChunk` corpus did not find a
numeric counterexample across 17,879,040 samples. That does not make the
different graph and cache lifetime an in-place Legacy optimization, and no
separate end-to-end benefit was established. The marker was therefore removed
to restore the original graph and ownership contract.

No arithmetic expression, density bound, branch threshold, interpolation marker,
noise holder, cave probability, vertical slide, noodle minimum, structure input,
aquifer input, final squeeze operation, or `NoiseChunk` cache lifetime changes.

## Mode and compatibility boundaries

- `LEGACY` retains the unmodified original density graph and remains the semantic
  reference.
- `LEGACY_V2` exports the exact Legacy graph, so C2ME and other density visitors
  see the same nodes and cache markers.
- `QUICK_V1` remains an explicit alternate new-world cave algorithm.
- OpenCL is independent of the cave mode and now defaults to `OFF`. `AUTO` or
  `ON` must be selected explicitly in `opencl.conf`.
- Biomes, rivers, erosion, aquifers, structures, surface rules, ore veins,
  carvers, and block placement remain outside this optimization.

## Required validation

- Missing `densityAlgorithm` fields decode as `LEGACY_V2`.
- All three mode names round-trip through the preset codec and UI translations.
- Legacy and Legacy V2 export structurally equal final-density graphs and match
  by raw double bits through a real `NoiseChunk`, not only direct scalar and bulk
  calls.
- Client comparisons must build separate fresh datapacks for both modes. Swapping
  jars over a world whose generated `overworld.json` is already frozen does not
  test the selected graph.
- OpenCL defaults write `mode=OFF`, and the OFF path does not compile or initialize
  an OpenCL graph.
- Final performance claims compare independent original and modified jars, not
  modes inside one jar.

## Performance boundary

There is currently no separate cave-graph speed claim for Legacy V2. Cave
graph and lifecycle identity take precedence over an unneeded cache marker.
Release performance is measured for the complete modified jar against the
independently supplied original jar.
