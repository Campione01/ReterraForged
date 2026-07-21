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
	private static final float QUANTIZATION = 4096.0F;
	private static final int[][] ORIGINS = {
		{-4096, 3072},
		{-16, -16},
		{3584, -4608}
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
				if(stats.maxError() > 1.0F / QUANTIZATION) {
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
			new Case("clamp", Noises.clamp(mapped, -0.1F, 1.2F)),
			new Case("map", Noises.map(source, Noises.add(alternate, -0.4F), Noises.add(alternate, 1.3F))),
			new Case("invert_unit", Noises.invert(source)),
			new Case("invert_nonzero_range", Noises.invert(Noises.map(source, 2.0F, 4.0F))),
			new Case("blend", Noises.blend(source, alternate, Noises.invert(alternate), 0.47F, 0.36F)),
			new Case("alpha", Noises.alpha(mapped, alternate)),
			new Case("boost", Noises.boost(source, 3)),
			new Case("threshold", Noises.threshold(source, alternate, Noises.invert(alternate), Noises.constant(0.53F))),
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
				if(stats.maxError() > 1.0F / QUANTIZATION) {
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
		float expected = erosion.computeLegacy(384.0F, -640.0F, 0);

		try(QuickNoiseRuntime.Engine engine = new QuickNoiseRuntime.Engine(); QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
			QuickNoiseRuntime.prepareSample(384, -640);
			assertEquals(expected, erosion.compute(384.0F, -640.0F, 0));
			assertEquals(0, engine.compiledGraphCount(), "An unsupported root must not compile nested child graphs during fallback");
		}
	}

	private static Stats compare(Noise noise) {
		float maxError = 0.0F;
		double squaredError = 0.0D;
		double legacySum = 0.0D;
		double quickSum = 0.0D;
		int samples = 0;
		for(int[] origin : ORIGINS) {
			for(int dz = 0; dz < 32; dz++) {
				for(int dx = 0; dx < 32; dx++) {
					int x = origin[0] + dx;
					int z = origin[1] + dz;
					float legacy = quantize(computeLegacy(noise, x, z));
					QuickNoiseRuntime.prepareSample(x, z);
					float quick = noise.compute(x, z, 0);
					float error = Math.abs(legacy - quick);
					maxError = Math.max(maxError, error);
					squaredError += error * error;
					legacySum += legacy;
					quickSum += quick;
					samples++;
				}
			}
		}
		return new Stats(maxError, (float)Math.sqrt(squaredError / samples), (float)(legacySum / samples), (float)(quickSum / samples));
	}

	private static float quantize(float value) {
		return Math.round(value * QUANTIZATION) / QUANTIZATION;
	}

	private static float computeLegacy(Noise noise, float x, float z) {
		try(QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(null)) {
			return noise.computeLegacy(x, z, 0);
		}
	}

	private record Case(String name, Noise noise) {
	}

	private record Stats(float maxError, float rootMeanSquareError, float legacyMean, float quickMean) {
	}
}
