package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.world.worldgen.noise.function.CellFunction;
import raccoonman.reterraforged.world.worldgen.noise.function.DistanceFunction;
import raccoonman.reterraforged.world.worldgen.noise.function.EdgeFunction;
import raccoonman.reterraforged.world.worldgen.noise.module.Erosion.BlendMode;

class QuickNoiseSemanticParityTest {
	private static final int[][] ORIGINS = {
		{-4096, 3072},
		{-16, -16},
		{3584, -4608}
	};
	private static final int[] EVALUATION_SEEDS = {
		0,
		1,
		-1,
		0x5EED_2171,
		Integer.MIN_VALUE,
		Integer.MAX_VALUE
	};
	private static final CoordinateTransform[] COORDINATE_TRANSFORMS = {
		new CoordinateTransform(1.0F, 0.0F, 1.0F, 0.0F),
		new CoordinateTransform(0.125F, 0.375F, 0.25F, -0.625F),
		new CoordinateTransform(0.37F, 11.125F, 1.41F, -7.875F),
		new CoordinateTransform(-0.5F, 3.25F, -0.75F, -5.5F)
	};

	@Test
	void primitiveMappingsPreserveLegacySemantics() {
		List<Case> cases = List.of(
			new Case("perlin", Noises.perlin(17, 480, 4, 2.35F, 0.61F)),
			new Case("perlin2", Noises.perlin2(29, 360, 3, 1.85F, 0.47F)),
			new Case("perlin_ridge", Noises.perlinRidge(41, 410, 4, 2.35F, 1.15F)),
			new Case("simplex", Noises.simplex(53, 520, 4, 2.1F, 0.55F)),
			new Case("simplex2", Noises.simplex2(67, 280, 3, 1.9F, 0.48F)),
			new Case("simplex_ridge", Noises.simplexRidge(79, 390, 4, 2.2F, 1.05F)),
			new Case("billow", Noises.billow(83, 330, 4, 2.05F, 0.92F)),
			new Case("cubic", Noises.cubic(97, 260, 3, 2.0F, 0.5F)),
			new Case("white", Noises.white(101, 48)),
			new Case("worley_value", Noises.worley(107, 240)),
			new Case("worley_distance", Noises.worley(109, 210, CellFunction.DISTANCE, DistanceFunction.NATURAL, Noises.zero())),
			new Case("worley_edge", Noises.worleyEdge(113, 1000, EdgeFunction.DISTANCE_2_ADD, DistanceFunction.EUCLIDEAN)),
			new Case("worley_edge_div", Noises.worleyEdge(127, 320, EdgeFunction.DISTANCE_2_DIV, DistanceFunction.MANHATTAN))
		);

		StringBuilder failures = new StringBuilder();
		try(QuickNoiseRuntime.Engine engine = new QuickNoiseRuntime.Engine(); QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
			for(Case testCase : cases) {
				Stats stats = compare(testCase.noise());
				if(stats.bitMismatches() != 0) {
					failures.append(System.lineSeparator()).append(testCase.name()).append(": ").append(stats);
				}
			}
		}
		assertTrue(failures.isEmpty(), () -> "Non-equivalent QUICK_V2 primitive mappings:" + failures);
	}

	@Test
	void compositeMappingsPreserveLegacySemantics() {
		Noise source = Noises.perlin2(211, 420, 4, 2.15F, 0.57F);
		Noise alternate = Noises.simplex(223, 310, 3, 1.9F, 0.48F);
		Noise mapped = Noises.map(source, -0.35F, 1.65F);
		Noise halfStepTerrace = Noises.advancedTerrace(
			Noises.clamp(Noises.constant(Float.intBitsToFloat(0x3daa_aaaa)), 0.0F, 1.0F),
			Noises.clamp(Noises.constant(0.04904006F), -0.25F, 0.25F),
			Noises.clamp(Noises.constant(0.7023268F), 0.0F, 1.0F),
			Noises.clamp(Noises.constant(0.5F), 0.0F, 1.0F),
			0.0F,
			0.3F,
			6,
			1
		);
		List<Case> cases = List.of(
			new Case("shift_seed", Noises.shiftSeed(source, 73)),
			new Case("frequency_constant", Noises.frequency(source, 0.37F, 1.41F)),
			new Case("frequency_dynamic", Noises.frequency(source, Noises.map(alternate, 0.65F, 1.35F), Noises.map(source, 0.8F, 1.2F))),
			new Case("add", Noises.add(source, alternate)),
			new Case("multiply", Noises.mul(source, alternate)),
			new Case("min", Noises.min(source, alternate)),
			new Case("max", Noises.max(source, alternate)),
			new Case("abs", Noises.abs(Noises.add(mapped, -0.8F))),
			new Case("power", Noises.pow(source, 2.35F)),
			new Case("power_curve", Noises.powCurve(source, 2.2F)),
			new Case("gradient", Noises.gradient(source, 0.18F, 0.82F, 0.63F)),
			new Case("terrace", Noises.terrace(source, alternate, Noises.invert(alternate), Noises.constant(0.72F), 0.38F, 7)),
			new Case("advanced_terrace", Noises.advancedTerrace(source, Noises.mul(alternate, 0.08F), alternate, Noises.constant(0.42F), 0.17F, 0.84F, 11, 3)),
			new Case("advanced_terrace_java_round_boundary", halfStepTerrace),
			new Case("clamp", Noises.clamp(mapped, -0.1F, 1.2F)),
			new Case("map", Noises.map(source, Noises.add(alternate, -0.4F), Noises.add(alternate, 1.3F))),
			new Case("invert_unit", Noises.invert(source)),
			new Case("invert_nonzero_range", Noises.invert(Noises.map(source, 2.0F, 4.0F))),
			new Case("blend", Noises.blend(source, alternate, Noises.invert(alternate), 0.47F, 0.36F)),
			new Case("blend_lower_boundary", Noises.blend(clampedConstant(0.35F), source, alternate, 0.5F, 0.3F)),
			new Case("blend_upper_boundary", Noises.blend(clampedConstant(0.65F), source, alternate, 0.5F, 0.3F)),
			new Case("blend_below_lower_boundary", Noises.blend(clampedConstant(Math.nextDown(0.35F)), source, alternate, 0.5F, 0.3F)),
			new Case("blend_above_upper_boundary", Noises.blend(clampedConstant(Math.nextUp(0.65F)), source, alternate, 0.5F, 0.3F)),
			new Case("alpha", Noises.alpha(mapped, alternate)),
			new Case("boost", Noises.boost(source, 3)),
			new Case("signed_integer_power", Noises.pow(Noises.constant(-0.75F), 5.0F)),
			new Case("threshold", Noises.threshold(source, alternate, Noises.invert(alternate), Noises.constant(0.53F))),
			new Case("threshold_equal_selects_lower", Noises.threshold(clampedConstant(0.5F), source, alternate, Noises.constant(0.5F))),
			new Case("legacy_moisture", new LegacyMoisture(source, 3)),
			new Case("legacy_temperature", new LegacyTemperature(0.0037F, 3)),
			new Case("linear_spline", LinearSpline.builder(source).addPoint(0.0F, Noises.mul(alternate, 0.2F)).addPoint(0.31F, alternate).addPoint(0.72F, Noises.invert(alternate)).addPoint(1.0F, Noises.add(alternate, 0.4F)).build()),
			new Case("line", Noises.line(-30.0F, -12.0F, 34.0F, 18.0F, Noises.constant(625.0F), Noises.constant(0.12F), Noises.constant(0.18F), 0.24F)),
			new Case("sin", Noises.sin(0, 0.017F, alternate))
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
			assertEquals(0, engine.fallbackGraphCount(), "Admitted composite graphs must not fall back at runtime");
		}
		assertTrue(failures.isEmpty(), () -> "Non-equivalent QUICK_V2 composite mappings:" + failures);
	}

	@Test
	void unsupportedStructuralRootFallsBackAtomically() {
		Noise source = Noises.perlin(131, 180, 4, 2.15F, 0.58F);
		Noise erosion = Noises.erosion(source, 137, 2, 0.5F, 48.0F, 0.5F, 2.0F, 0.5F, BlendMode.CONSTANT);
		float expected = erosion.compute(384.0F, -640.0F, 0);

		try(QuickNoiseRuntime.Engine engine = new QuickNoiseRuntime.Engine(); QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
			QuickNoiseRuntime.prepareSample(384, -640);
			assertEquals(expected, erosion.computeRoot(384.0F, -640.0F, 0));
			assertEquals(0, engine.compiledGraphCount(), "An unsupported root must not compile nested child graphs during fallback");
		}
	}

	@Test
	void preparedCoordinatesRequireRawFloatIdentity() {
		Noise noise = Noises.perlin2(149, 240, 3, 2.1F, 0.54F);
		float preparedX = 876.0F;
		float actualX = Math.nextUp(preparedX);
		float z = -1432.0F;
		float expected = computeLegacy(noise, actualX, z, 17);

		try(QuickNoiseRuntime.Engine engine = new QuickNoiseRuntime.Engine(); QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
			QuickNoiseRuntime.prepareSample((int)preparedX, (int)z);
			assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(noise.computeRoot(actualX, z, 17)), "A nearby dynamic coordinate must fall back instead of reusing the prepared lattice sample");
			assertEquals(0, engine.compiledGraphCount(), "An irregular first coordinate must not compile an unused tile graph");
			assertEquals(Float.floatToRawIntBits(computeLegacy(noise, preparedX, z, 17)), Float.floatToRawIntBits(noise.computeRoot(preparedX, z, 17)));
			assertEquals(1, engine.compiledGraphCount(), "The first matching lattice coordinate must admit the graph");
		}
	}

	private static Stats compare(Noise noise) {
		float maxError = 0.0F;
		double squaredError = 0.0D;
		double legacySum = 0.0D;
		double quickSum = 0.0D;
		int bitMismatches = 0;
		int samples = 0;
		for(CoordinateTransform transform : COORDINATE_TRANSFORMS) {
			for(int seed : EVALUATION_SEEDS) {
				for(int[] origin : ORIGINS) {
					for(int dz = 0; dz < 32; dz++) {
						for(int dx = 0; dx < 32; dx++) {
							int sampleX = origin[0] + dx;
							int sampleZ = origin[1] + dz;
							float x = transform.x(sampleX);
							float z = transform.z(sampleZ);
							float legacy = computeLegacy(noise, x, z, seed);
							QuickNoiseRuntime.prepareSample(sampleX, sampleZ, transform.xScale(), transform.xOffset(), transform.zScale(), transform.zOffset());
							float quick = noise.computeRoot(x, z, seed);
							if(Float.floatToRawIntBits(legacy) != Float.floatToRawIntBits(quick)) {
								bitMismatches++;
							}
							float error = Math.abs(legacy - quick);
							maxError = Math.max(maxError, error);
							squaredError += error * error;
							legacySum += legacy;
							quickSum += quick;
							samples++;
						}
					}
				}
			}
		}
		return new Stats(bitMismatches, maxError, (float)Math.sqrt(squaredError / samples), (float)(legacySum / samples), (float)(quickSum / samples));
	}

	private static Noise clampedConstant(float value) {
		return Noises.clamp(Noises.constant(value), Noises.zero(), Noises.one());
	}

	private static float computeLegacy(Noise noise, float x, float z, int seed) {
		try(QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(null)) {
			return noise.compute(x, z, seed);
		}
	}

	private record CoordinateTransform(float xScale, float xOffset, float zScale, float zOffset) {
		private float x(int sampleX) {
			return sampleX * this.xScale + this.xOffset;
		}

		private float z(int sampleZ) {
			return sampleZ * this.zScale + this.zOffset;
		}
	}

	private record Case(String name, Noise noise) {
	}

	private record Stats(int bitMismatches, float maxError, float rootMeanSquareError, float legacyMean, float quickMean) {
	}
}
