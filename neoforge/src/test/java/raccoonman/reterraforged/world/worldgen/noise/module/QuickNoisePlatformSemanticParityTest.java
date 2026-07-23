package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domains;
import raccoonman.reterraforged.world.worldgen.noise.function.CurveFunctions;
import raccoonman.reterraforged.world.worldgen.noise.function.Interpolation;
import raccoonman.reterraforged.world.worldgen.quicknoise.QuickNoiseNative;

class QuickNoisePlatformSemanticParityTest {
	private static final int[] LEGACY_V2_SEEDS = {0, 1, -1, 0x5EED_2171, Integer.MIN_VALUE, Integer.MAX_VALUE};
	private static final int[][] ORIGINS = {
		{-4096, 3072},
		{-16, -16},
		{3584, -4608}
	};

	@Test
	void legacyV2WarpBatchingPreservesOriginalRtfBits() {
		Noise source = Noises.frequency(
			Noises.add(Noises.perlin(101, 420, 4), Noises.simplex2(103, 360, 3)),
			0.37F,
			1.41F
		);
		Domain perlinWarp = Domains.domainPerlin(113, 180, 3, 42.5F);
		Domain directionWarp = Domains.direction(
			Noises.perlin(127, 240, 3),
			Noises.map(Noises.simplex(131, 310, 2), 6.0F, 34.0F)
		);
		Domain addedWarp = Domains.add(perlinWarp, directionWarp);
		Domain compoundWarp = Domains.compound(
			addedWarp,
			Domains.domainSimplex(137, 150, 3, 18.75F)
		);
		List<Case> cases = List.of(
			new Case("warp_perlin", Noises.warpPerlin(source, 107, 210, 3, 31.5F)),
			new Case("warp_direction", Noises.warp(source, directionWarp)),
			new Case("warp_add", Noises.warp(source, addedWarp)),
			new Case("warp_compound", Noises.warp(source, compoundWarp)),
			new Case("warp_direct", Noises.warp(source, Domains.direct()))
		);
		float[][] transforms = {
			{1.0F, 0.0F, 1.0F, 0.0F},
			{0.125F, 0.375F, 0.25F, -0.625F},
			{0.37F, 11.125F, 1.41F, -7.875F},
			{-0.5F, 3.25F, -0.75F, -5.5F}
		};

		for(Case testCase : cases) {
			assertTrue(testCase.noise().supportsBulk(), testCase.name());
			for(float[] transform : transforms) {
				NoiseBatch batch = NoiseBatch.regular(-37, 19, 17, 11, transform[0], transform[1], transform[2], transform[3]);
				float[] actual = new float[batch.size()];
				for(int seed : LEGACY_V2_SEEDS) {
					testCase.noise().fill(batch, seed, actual);
					for(int index = 0; index < actual.length; index++) {
						float expected = testCase.noise().compute(batch.xAt(index), batch.zAt(index), seed);
						assertEquals(
							Float.floatToRawIntBits(expected),
							Float.floatToRawIntBits(actual[index]),
							testCase.name() + " seed=" + seed + " index=" + index
						);
					}
				}
			}
		}
	}

	@Test
	void curvesStepsAndWarpsPreserveLegacySemantics() {
		Noise source = Noises.perlin2(311, 420, 4, 2.15F, 0.57F);
		Noise alternate = Noises.simplex(323, 310, 3, 1.9F, 0.48F);
		Domain primary = Domains.domain(Noises.perlin(331, 560, 3), Noises.simplex2(337, 610, 3), Noises.constant(86.0F));
		Domain direction = Domains.direction(alternate, Noises.constant(53.0F));
		List<Case> cases = List.of(
			new Case("curve_linear", Noises.curve(source, Interpolation.LINEAR)),
			new Case("curve3", Noises.curve(source, Interpolation.CURVE3)),
			new Case("curve4", Noises.curve(source, Interpolation.CURVE4)),
			new Case("steps_linear", Noises.steps(source, 7, 0.2F, 0.8F, Interpolation.LINEAR)),
			new Case("steps_curve3", Noises.steps(source, 9, 0.15F, 0.72F, Interpolation.CURVE3)),
			new Case("steps_curve4", Noises.steps(source, 11, 0.08F, 0.65F, Interpolation.CURVE4)),
			new Case("warp_direct", Noises.warp(source, Domains.direct())),
			new Case("warp_domain", Noises.warp(source, primary)),
			new Case("warp_direction", Noises.warp(source, direction)),
			new Case("warp_add", Noises.warp(source, Domains.add(primary, direction))),
			new Case("warp_compound", Noises.warp(source, Domains.compound(primary, direction)))
		);

		StringBuilder failures = new StringBuilder();
		try(QuickNoiseRuntime.Engine engine = new QuickNoiseRuntime.Engine(); QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
			for(Case testCase : cases) {
				if(QuickNoiseGraphCompiler.compile(testCase.noise()).isEmpty()) {
					failures.append(System.lineSeparator()).append(testCase.name()).append(": graph is not admitted");
					continue;
				}
				Stats stats = compare(testCase.noise());
				if(stats.bitMismatches() != 0) {
					failures.append(System.lineSeparator()).append(testCase.name()).append(": ").append(stats);
				}
			}
			assertEquals(0, engine.fallbackGraphCount(), "Admitted platform graphs must not fall back at runtime");
		}
		assertTrue(failures.isEmpty(), () -> "Non-equivalent QUICK_V2 platform mappings:" + failures);
	}

	@Test
	void unsupportedCurveAndDynamicStepsFallBackAtomically() {
		Noise source = Noises.perlin(347, 260, 3);
		List<Noise> unsupported = List.of(
			Noises.curve(source, CurveFunctions.scurve(0.2F, 0.8F)),
			Noises.steps(source, Noises.simplex(349, 180, 2), Noises.constant(0.2F), Noises.constant(0.8F), Interpolation.CURVE3)
		);

		try(QuickNoiseRuntime.Engine engine = new QuickNoiseRuntime.Engine(); QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
			for(Noise noise : unsupported) {
				assertTrue(QuickNoiseGraphCompiler.compile(noise).isEmpty());
				float expected = computeLegacy(noise, 384.0F, -640.0F);
				QuickNoiseRuntime.prepareSample(384, -640);
				assertEquals(expected, noise.computeRoot(384.0F, -640.0F, 0));
			}
			assertEquals(0, engine.compiledGraphCount());
			assertEquals(unsupported.size(), engine.fallbackGraphCount());
		}
	}

	@Test
	void optionalRootProfileMeasuresTileWorkWithoutChangingSamples() {
		String property = "reterraforged.quickNoise.profile";
		String previous = System.getProperty(property);
		System.setProperty(property, "true");
		try {
			Noise noise = Noises.perlin2(353, 240, 3);
			try(QuickNoiseRuntime.Engine engine = new QuickNoiseRuntime.Engine(); QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
				for(int z = 0; z < 64; z++) {
					for(int x = 0; x < 64; x++) {
						QuickNoiseRuntime.prepareSample(x, z);
						assertEquals(computeLegacy(noise, x, z), noise.computeRoot(x, z, 0));
					}
				}

				QuickNoiseRuntime.prepareSample(0, 0);
				assertEquals(computeLegacy(noise, 0.5F, 0.0F), noise.computeRoot(0.5F, 0.0F, 0));

				QuickNoiseRuntime.ProfileSnapshot profile = engine.profileSnapshot();
				assertTrue(profile.enabled());
				assertEquals(1L, profile.fallbackCalls());
				assertEquals(1, profile.entries().size());
				QuickNoiseRuntime.ProfileEntry entry = profile.entries().getFirst();
				assertEquals(4096L, entry.sampleCalls());
				assertEquals(4095L, entry.currentTileHits());
				assertEquals(0L, entry.retainedTileHits());
				assertEquals(1L, entry.tileFills());
				assertEquals(4096L, entry.computedSamples());
				assertEquals(1L, entry.coordinateFallbacks());
				assertEquals(1.0D, entry.utilization());
			}
		} finally {
			if(previous == null) {
				System.clearProperty(property);
			} else {
				System.setProperty(property, previous);
			}
		}
	}

	@Test
	void multiRootBatchSharesNodesAndPreservesEveryOutput() {
		Noise shared = Noises.perlin2(359, 300, 4, 2.1F, 0.52F);
		List<Noise> roots = List.of(
			Noises.add(shared, Noises.constant(0.125F)),
			Noises.mul(shared, Noises.constant(0.75F)),
			Noises.curve(shared, Interpolation.CURVE3)
		);
		QuickNoiseGraphCompiler.CompiledBatch batch = QuickNoiseGraphCompiler.compileBatch(roots).orElseThrow();
		int separateNodes = roots.stream().map(QuickNoiseGraphCompiler::compile).map(Optional::orElseThrow).mapToInt(compiled -> compiled.graph().nodeCount()).sum();
		assertTrue(batch.graph().nodeCount() < separateNodes, "The batch must reuse the shared RTF subgraph");

		int width = 32;
		int height = 32;
		int originX = -384;
		int originZ = 640;
		float[] output = new float[width * height * roots.size()];
		try(QuickNoiseNative.Program program = QuickNoiseNative.compileProgram(batch.graph().encode())) {
			assertEquals(roots.size(), program.outputs());
			program.fill(0, originX, originZ, width, height, output);
		}

		int samples = width * height;
		for(int rootIndex = 0; rootIndex < roots.size(); rootIndex++) {
			Noise noise = roots.get(rootIndex);
			for(int index = 0; index < samples; index++) {
				int x = originX + index % width;
				int z = originZ + index / width;
				float expected = computeLegacy(noise, x, z);
				float actual = output[rootIndex * samples + index];
				assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
			}
		}
	}

	@Test
	void legacyCoordinateScopesNestAndIrregularFirstSamplesDoNotCompile() {
		Noise accelerated = Noises.perlin2(367, 280, 3);
		Noise irregular = Noises.simplex2(373, 310, 3);
		try(QuickNoiseRuntime.Engine engine = new QuickNoiseRuntime.Engine(); QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
			QuickNoiseRuntime.prepareSample(16, -24);
			try(QuickNoiseRuntime.LegacyScope outer = QuickNoiseRuntime.legacyCoordinates()) {
				assertEquals(computeLegacy(accelerated, 16.0F, -24.0F), accelerated.computeRoot(16.0F, -24.0F, 0));
				try(QuickNoiseRuntime.LegacyScope inner = QuickNoiseRuntime.legacyCoordinates()) {
					assertEquals(computeLegacy(accelerated, 16.0F, -24.0F), accelerated.computeRoot(16.0F, -24.0F, 0));
				}
				assertEquals(computeLegacy(accelerated, 16.0F, -24.0F), accelerated.computeRoot(16.0F, -24.0F, 0));
				assertEquals(0, engine.compiledGraphCount());
			}

			assertEquals(computeLegacy(accelerated, 16.0F, -24.0F), accelerated.computeRoot(16.0F, -24.0F, 0));
			assertEquals(1, engine.compiledGraphCount());

			QuickNoiseRuntime.prepareSample(32, 48);
			assertEquals(computeLegacy(irregular, 32.5F, 48.0F), irregular.computeRoot(32.5F, 48.0F, 0));
			assertEquals(1, engine.compiledGraphCount(), "An irregular first sample must not compile an unused tile graph");

			assertEquals(computeLegacy(irregular, 32.0F, 48.0F), irregular.computeRoot(32.0F, 48.0F, 0));
			assertEquals(2, engine.compiledGraphCount());
		}
	}

	@Test
	void runtimeBatchPromotionPreservesEveryRootBit() {
		String property = "reterraforged.quickNoise.batchPromotionTiles";
		String batchProperty = "reterraforged.quickNoise.batchRoots";
		String previous = System.getProperty(property);
		String previousBatch = System.getProperty(batchProperty);
		System.setProperty(property, "0");
		System.setProperty(batchProperty, "true");
		try {
			Noise shared = Noises.perlin2(379, 340, 4, 2.2F, 0.51F);
			List<Noise> roots = List.of(
				Noises.add(shared, Noises.constant(0.125F)),
				Noises.mul(shared, Noises.constant(0.75F)),
				Noises.curve(shared, Interpolation.CURVE3)
			);
			try(QuickNoiseRuntime.Engine engine = QuickNoiseRuntime.Engine.forBatch(32)) {
				try(QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
					for(Noise root : roots) {
						QuickNoiseRuntime.prepareSample(-384, 640);
						assertEquals(computeLegacy(root, -384.0F, 640.0F), root.computeRoot(-384.0F, 640.0F, 0));
					}
				}

				QuickNoiseRuntime.BatchOptimization optimization = engine.optimizeDiscoveredRoots();
				assertEquals(1, optimization.groups());
				assertEquals(roots.size(), optimization.roots());
				assertTrue(optimization.batchNodes() < optimization.separateNodes());
				assertEquals(optimization, engine.batchOptimizationSnapshot());
				assertEquals(roots.size(), engine.compiledGraphCount());

				try(QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
					for(int z = 640; z < 672; z++) {
						for(int x = -384; x < -352; x++) {
							for(Noise root : roots) {
								QuickNoiseRuntime.prepareSample(x, z);
								float expected = computeLegacy(root, x, z);
								float actual = root.computeRoot(x, z, 0);
								assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
							}
						}
					}
				}
			}
		} finally {
			if(previous == null) {
				System.clearProperty(property);
			} else {
				System.setProperty(property, previous);
			}
			if(previousBatch == null) {
				System.clearProperty(batchProperty);
			} else {
				System.setProperty(batchProperty, previousBatch);
			}
		}
	}

	private static Stats compare(Noise noise) {
		float maxError = 0.0F;
		double squaredError = 0.0D;
		int bitMismatches = 0;
		int samples = 0;
		for(int[] origin : ORIGINS) {
			for(int dz = 0; dz < 32; dz++) {
				for(int dx = 0; dx < 32; dx++) {
					int x = origin[0] + dx;
					int z = origin[1] + dz;
					float legacy = computeLegacy(noise, x, z);
					QuickNoiseRuntime.prepareSample(x, z);
					float quick = noise.computeRoot(x, z, 0);
					if(Float.floatToRawIntBits(legacy) != Float.floatToRawIntBits(quick)) {
						bitMismatches++;
					}
					float error = Math.abs(legacy - quick);
					maxError = Math.max(maxError, error);
					squaredError += error * error;
					samples++;
				}
			}
		}
		return new Stats(bitMismatches, maxError, (float)Math.sqrt(squaredError / samples));
	}

	private static float computeLegacy(Noise noise, float x, float z) {
		try(QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(null)) {
			return noise.compute(x, z, 0);
		}
	}

	private record Case(String name, Noise noise) {
	}

	private record Stats(int bitMismatches, float maxError, float rootMeanSquareError) {
	}
}
