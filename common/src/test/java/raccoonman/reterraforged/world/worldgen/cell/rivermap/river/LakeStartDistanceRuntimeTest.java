package raccoonman.reterraforged.world.worldgen.cell.rivermap.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.RiverSettings;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.lake.LakeConfig;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil.Vec2f;
import raccoonman.reterraforged.world.worldgen.util.Variance;

class LakeStartDistanceRuntimeTest {
	private static final Levels LEVELS = new Levels(384, 63);

	@Test
	void configuredMinimumAndMaximumDefineTheSampledRiverFraction() {
		LakeConfig config = config(0.2F, 0.6F);
		Random expectedRandom = new Random(451L);
		float expected = 0.2F + expectedRandom.nextFloat() * 0.4F;

		assertEquals(expected, config.nextStartDistance(new Random(451L)));
	}

	@Test
	void branchLakeUsesTheConfiguredFractionAlongTheRiver() {
		River river = new River(100.0F, 200.0F, 1100.0F, 2200.0F);
		Vec2f center = BaseRiverGenerator.sampleBranchLakeCenter(river, config(0.25F, 0.25F), new Random(7L));

		assertEquals(350.0F, center.x());
		assertEquals(700.0F, center.y());
	}

	@Test
	void additionalLakeUsesTheConfiguredFractionAlongTheRiver() {
		River river = new River(100.0F, 200.0F, 1100.0F, 2200.0F);
		Vec2f center = BaseRiverGenerator.sampleAdditionalLakeCenter(500, 600, river, config(0.25F, 0.25F), new Random(7L));

		assertEquals(350.0F, center.x());
		assertEquals(700.0F, center.y());
	}

	@Test
	void defaultPresetBranchPlacementMatchesLegacyCoordinatesAndRandomState() {
		LakeConfig config = config(LakeConfig.DEFAULT_PRESET_DISTANCE_MIN, LakeConfig.DEFAULT_PRESET_DISTANCE_MAX);
		River river = new River(100.0F, 200.0F, 1100.0F, 2200.0F);
		Random actualRandom = new Random(981L);
		Random expectedRandom = new Random(981L);

		Vec2f center = BaseRiverGenerator.sampleBranchLakeCenter(river, config, actualRandom);

		assertTrue(config.usesDefaultPresetDistance());
		assertEquals(Float.floatToRawIntBits(river.x1), Float.floatToRawIntBits(center.x()));
		assertEquals(Float.floatToRawIntBits(river.z1), Float.floatToRawIntBits(center.y()));
		assertEquals(expectedRandom.nextLong(), actualRandom.nextLong());
	}

	@Test
	void defaultPresetAdditionalPlacementMatchesLegacyCoordinatesAndRandomState() {
		LakeConfig config = config(LakeConfig.DEFAULT_PRESET_DISTANCE_MIN, LakeConfig.DEFAULT_PRESET_DISTANCE_MAX);
		River river = new River(100.0F, 200.0F, 1100.0F, 2200.0F);
		Random actualRandom = new Random(476L);
		Random expectedRandom = new Random(476L);
		int x = 500;
		int z = 600;

		float angle = 0.0F;
		float dx = NoiseUtil.sin(angle);
		float dz = NoiseUtil.cos(angle);
		float distance = Variance.of(0.6000000238418579F, 0.30000001192092896F).next(expectedRandom);
		float expectedX = x + dx * river.length * distance;
		float expectedZ = z + dz * river.length * distance;
		Vec2f center = BaseRiverGenerator.sampleAdditionalLakeCenter(x, z, river, config, actualRandom);

		assertEquals(Float.floatToRawIntBits(expectedX), Float.floatToRawIntBits(center.x()));
		assertEquals(Float.floatToRawIntBits(expectedZ), Float.floatToRawIntBits(center.y()));
		assertEquals(expectedRandom.nextLong(), actualRandom.nextLong());
	}

	@Test
	void malformedDistanceBoundsAreOrderedAndClampedToTheRiver() {
		LakeConfig config = config(1.4F, -0.2F);

		assertEquals(0.0F, config.distanceMin);
		assertEquals(1.0F, config.distanceMax);
	}

	private static LakeConfig config(float minStartDistance, float maxStartDistance) {
		RiverSettings.Lake settings = new RiverSettings.Lake(
			1.0F,
			minStartDistance,
			maxStartDistance,
			10,
			75,
			150,
			2,
			10
		);
		return LakeConfig.of(settings, LEVELS);
	}
}
