package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.MapCodec;

import raccoonman.reterraforged.world.worldgen.noise.module.Erosion.BlendMode;

class QuickNoiseGraphCompilerTest {
	@Test
	void compilesRepresentativeTerrainGraphModules() {
		Noise source = Noises.perlin(17, 480, 4);

		assertTrue(QuickNoiseGraphCompiler.compile(Noises.gradient(source, 0.0F, 0.6F, 0.45F)).isPresent());
		assertTrue(QuickNoiseGraphCompiler.compile(Noises.terrace(source, 0.9F, 0.15F, 0.35F, 0.4F, 6)).isPresent());
		assertTrue(QuickNoiseGraphCompiler.compile(Noises.advancedTerrace(source, 0.04F, 0.8F, 0.6F, 0.1F, 0.45F, 12, 2)).isPresent());
		assertTrue(QuickNoiseGraphCompiler.compile(Noises.powCurve(source, 2.0F)).isPresent());
		assertTrue(QuickNoiseGraphCompiler.compile(Noises.line(-200.0F, 40.0F, 300.0F, -90.0F, Noises.constant(2500.0F), Noises.constant(0.1F), Noises.constant(0.15F), 0.25F)).isPresent());
		assertTrue(QuickNoiseGraphCompiler.compile(LinearSpline.builder(source).addPoint(0.0F, 0.1F).addPoint(0.5F, 0.8F).addPoint(1.0F, 0.2F).build()).isPresent());
	}

	@Test
	void leavesStructuralErosionOnTheCpu() {
		Noise erosion = Noises.erosion(Noises.perlin(3, 120, 3), 9, 2, 0.5F, 48.0F, 0.5F, 2.0F, 0.5F, BlendMode.CONSTANT);

		assertFalse(QuickNoiseGraphCompiler.compile(erosion).isPresent());
	}

	@Test
	void runtimeUsesAffineTilesButRejectsUnrelatedCoordinates() {
		Noise noise = Noises.perlin(33, 256, 4);
		float unrelatedLegacy = noise.compute(19.75F, -42.5F, 7);

		try(QuickNoiseRuntime.Engine engine = new QuickNoiseRuntime.Engine(); QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
			QuickNoiseRuntime.prepareSample(39, -17, 0.25F, 10.0F, 0.5F, -3.0F);
			float first = noise.computeRoot(19.75F, -11.5F, 7);
			float repeated = noise.computeRoot(19.75F, -11.5F, 7);
			assertEquals(first, repeated);
			assertEquals(1, engine.compiledGraphCount());

			assertEquals(unrelatedLegacy, noise.computeRoot(19.75F, -42.5F, 7));
			try(QuickNoiseRuntime.CoordinateScope scaled = QuickNoiseRuntime.scaleCoordinates(0.2F)) {
				float scaledX = 39 * (0.25F * 0.2F) + (10.0F * 0.2F);
				float scaledZ = -17 * (0.5F * 0.2F) + (-3.0F * 0.2F);
				float scaledFirst = noise.computeRoot(scaledX, scaledZ, 7);
				float scaledRepeated = noise.computeRoot(scaledX, scaledZ, 7);
				assertEquals(scaledFirst, scaledRepeated);
			}
			assertEquals(2, engine.compiledGraphCount());
		}
	}

	@Test
	void thirdPartyComputeOverrideBypassesTheNewEngine() {
		Noise external = new Noise() {
			@Override
			public float compute(float x, float z, int seed) {
				return 42.0F;
			}

			@Override
			public float minValue() {
				return 42.0F;
			}

			@Override
			public float maxValue() {
				return 42.0F;
			}

			@Override
			public Noise mapAll(Visitor visitor) {
				return this;
			}

			@Override
			public MapCodec<? extends Noise> codec() {
				return null;
			}
		};

		try(QuickNoiseRuntime.Engine engine = new QuickNoiseRuntime.Engine(); QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
			QuickNoiseRuntime.prepareSample(4, 8);
			assertEquals(42.0F, external.computeRoot(4.0F, 8.0F, 0));
			assertEquals(0, engine.compiledGraphCount());
			assertEquals(0, engine.fallbackGraphCount());
		}
	}
}
