# RTF Cave Legacy V2

## Goal

`LEGACY_V2` is the default 3D cave-density route. It optimizes the original RTF
and vanilla density graph in place; it is not a quick-noise replacement and it
must remain mathematically identical to `LEGACY`.

## Current optimization

The legacy graph samples `slopedCheese` once to select a `rangeChoice` branch and
then samples the same function again in the selected entrance branch. Legacy V2
places vanilla's `cacheOnce` marker around that shared outer input. `NoiseChunk`
maps both references to one chunk-local cache, so the repeated value is reused at
the identical block position.

The exported density JSON contains two structurally equal `cache_once` references.
Vanilla `NoiseChunk` wraps density functions through its equality-keyed
`HashMap<DensityFunction, DensityFunction>`, so both references resolve to the
same runtime cache after datapack decoding. C2ME's CPU density compiler accepts
the same vanilla graph and remains the runtime owner when installed.

No arithmetic expression, density bound, branch threshold, interpolation marker,
noise holder, cave probability, vertical slide, noodle minimum, structure input,
aquifer input, or final squeeze operation changes.

## Mode and compatibility boundaries

- `LEGACY` retains the unmodified original density graph and remains the semantic
  reference.
- `LEGACY_V2` retains vanilla density-function node types, so C2ME and other
  density visitors continue to see the normal graph rather than an opaque custom
  cave node.
- `QUICK_V1` remains an explicit alternate new-world cave algorithm.
- OpenCL is independent of the cave mode and now defaults to `OFF`. `AUTO` or
  `ON` must be selected explicitly in `opencl.conf`.
- Biomes, rivers, erosion, aquifers, structures, surface rules, ore veins,
  carvers, and block placement remain outside this optimization.

## Required validation

- Missing `densityAlgorithm` fields decode as `LEGACY_V2`.
- All three mode names round-trip through the preset codec and UI translations.
- Legacy and Legacy V2 final density match by raw double bits for scalar and bulk
  sampling across cave probabilities, heights, and large coordinates.
- OpenCL defaults write `mode=OFF`, and the OFF path does not compile or initialize
  an OpenCL graph.
- Final performance claims compare independent original and modified jars, not
  modes inside one jar.

## Retained performance evidence

The same modified Jar was tested with terrain Legacy V2 fixed and only the cave
mode changed. A four-round AB/BA server matrix generated 1,024 forced chunks per
mode and round with C2ME's CPU density compiler active and no GPU dispatch.

- without JFR: Legacy median `53.023 s`, Legacy V2 median `51.580 s`,
  `2.80%` improvement;
- with JFR: Legacy median `51.039 s`, Legacy V2 median `47.335 s`,
  `7.83%` improvement.

Three of four non-JFR pairs and all four profiled pairs favored Legacy V2. The
non-JFR result is the accepted end-to-end signal; the JFR result supports the
repeated-sampling diagnosis rather than serving as the release speed claim.
