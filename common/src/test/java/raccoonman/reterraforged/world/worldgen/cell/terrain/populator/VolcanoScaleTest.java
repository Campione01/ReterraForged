package raccoonman.reterraforged.world.worldgen.cell.terrain.populator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.MapCodec;

import raccoonman.reterraforged.data.worldgen.preset.settings.TerrainSettings;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;

class VolcanoScaleTest {
	private static final Levels LEVELS = new Levels(384, 63);
	private static final Noise CONE = noise((x, z) -> 0.35F + x * 0.0001F + z * 0.0002F);
	private static final Noise HEIGHT = noise((x, z) -> 0.6F);
	private static final Noise LOWLANDS = noise((x, z) -> 0.04F + (x - z) * 0.00001F);

	@Test
	void explicitIdentityScalesUseTheLegacyPathBitForBit() {
		VolcanoPopulator legacy = new VolcanoPopulator(CONE, HEIGHT, LOWLANDS, LEVELS.ground, 5.0F);
		VolcanoPopulator explicitIdentity = volcano(1.0F, 1.0F, 1.0F);

		for(int z = -500; z <= 500; z += 31) {
			for(int x = -500; x <= 500; x += 31) {
				assertCellsEqual(sample(legacy, x, z), sample(explicitIdentity, x, z));
			}
		}
	}

	@Test
	void baseAndVerticalScalesApplyToBiasAndShapedVarianceRespectively() {
		float x = 160.0F;
		float z = -80.0F;
		Cell shapeOnly = sample(volcano(0.0F, 1.0F, 1.0F), x, z);
		Cell baseScaled = sample(volcano(1.5F, 1.0F, 1.0F), x, z);
		Cell verticalScaled = sample(volcano(1.0F, 2.0F, 1.0F), x, z);

		assertEquals(LEVELS.ground * 1.5F + shapeOnly.height, baseScaled.height);
		assertEquals(LEVELS.ground + shapeOnly.height * 2.0F, verticalScaled.height);
		assertSame(shapeOnly.terrain, baseScaled.terrain);
		assertSame(shapeOnly.terrain, verticalScaled.terrain);
	}

	@Test
	void horizontalScaleStretchesTheWholeVolcanoShape() {
		VolcanoPopulator identity = volcano(1.0F, 1.0F, 1.0F);
		VolcanoPopulator stretched = volcano(1.0F, 1.0F, 2.0F);
		float x = 123.0F;
		float z = -77.0F;

		Cell original = sample(identity, x, z);
		Cell correspondingStretched = sample(stretched, x * 2.0F, z * 2.0F);

		assertCellsEqual(original, correspondingStretched);
		assertNotEquals(
			Float.floatToRawIntBits(original.height),
			Float.floatToRawIntBits(sample(stretched, x, z).height)
		);
	}

	@Test
	void invalidOrUnderflowingHorizontalScalesNeverFeedNonFiniteNoiseCoordinates() {
		for(float horizontalScale : new float[] { 0.0F, -1.0F, Float.NaN, Float.MIN_VALUE }) {
			AtomicBoolean finiteCoordinates = new AtomicBoolean(true);
			Noise cone = finiteNoise(finiteCoordinates, 0.35F);
			Noise height = finiteNoise(finiteCoordinates, 0.6F);
			Noise lowlands = finiteNoise(finiteCoordinates, 0.04F);
			TerrainSettings.Terrain settings = new TerrainSettings.Terrain(5.0F, 1.0F, 1.0F, horizontalScale);
			VolcanoPopulator volcano = new VolcanoPopulator(cone, height, lowlands, LEVELS.ground, settings);

			volcano.apply(new Cell(), Float.MAX_VALUE, Float.NEGATIVE_INFINITY);

			assertTrue(finiteCoordinates.get(), () -> "non-finite coordinate for scale " + horizontalScale);
		}
	}

	private static VolcanoPopulator volcano(float baseScale, float verticalScale, float horizontalScale) {
		TerrainSettings.Terrain settings = new TerrainSettings.Terrain(5.0F, baseScale, verticalScale, horizontalScale);
		return new VolcanoPopulator(CONE, HEIGHT, LOWLANDS, LEVELS.ground, settings);
	}

	private static Cell sample(VolcanoPopulator volcano, float x, float z) {
		Cell cell = new Cell();
		volcano.apply(cell, x, z);
		return cell;
	}

	private static void assertCellsEqual(Cell expected, Cell actual) {
		assertEquals(Float.floatToRawIntBits(expected.height), Float.floatToRawIntBits(actual.height));
		assertEquals(Float.floatToRawIntBits(expected.erosion), Float.floatToRawIntBits(actual.erosion));
		assertEquals(Float.floatToRawIntBits(expected.weirdness), Float.floatToRawIntBits(actual.weirdness));
		assertSame(expected.terrain, actual.terrain);
	}

	private static Noise noise(Sampler sampler) {
		return new TestNoise(sampler);
	}

	private static Noise finiteNoise(AtomicBoolean finiteCoordinates, float value) {
		return noise((x, z) -> {
			finiteCoordinates.compareAndSet(true, Float.isFinite(x) && Float.isFinite(z));
			return value;
		});
	}

	@FunctionalInterface
	private interface Sampler {
		float compute(float x, float z);
	}

	private record TestNoise(Sampler sampler) implements Noise {
		@Override
		public float compute(float x, float z, int seed) {
			return this.sampler.compute(x, z);
		}

		@Override
		public float minValue() {
			return -1.0F;
		}

		@Override
		public float maxValue() {
			return 1.0F;
		}

		@Override
		public Noise mapAll(Visitor visitor) {
			return visitor.apply(this);
		}

		@Override
		public MapCodec<? extends Noise> codec() {
			throw new UnsupportedOperationException();
		}
	}
}
