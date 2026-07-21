package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domains;
import raccoonman.reterraforged.world.worldgen.noise.function.CurveFunctions;
import raccoonman.reterraforged.world.worldgen.noise.function.Interpolation;

class QuickNoisePlatformSemanticParityTest {
	private static final float QUANTIZATION = 4096.0F;
	private static final int[][] ORIGINS = {
		{-4096, 3072},
		{-16, -16},
		{3584, -4608}
	};

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
				if(stats.maxError() > 1.0F / QUANTIZATION) {
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
				assertEquals(expected, noise.compute(384.0F, -640.0F, 0));
			}
			assertEquals(0, engine.compiledGraphCount());
			assertEquals(unsupported.size(), engine.fallbackGraphCount());
		}
	}

	private static Stats compare(Noise noise) {
		float maxError = 0.0F;
		double squaredError = 0.0D;
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
					samples++;
				}
			}
		}
		return new Stats(maxError, (float)Math.sqrt(squaredError / samples));
	}

	private static float computeLegacy(Noise noise, float x, float z) {
		try(QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(null)) {
			return noise.computeLegacy(x, z, 0);
		}
	}

	private static float quantize(float value) {
		return Math.round(value * QUANTIZATION) / QUANTIZATION;
	}

	private record Case(String name, Noise noise) {
	}

	private record Stats(float maxError, float rootMeanSquareError) {
	}
}
