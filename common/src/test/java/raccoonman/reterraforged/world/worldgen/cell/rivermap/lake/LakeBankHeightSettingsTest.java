package raccoonman.reterraforged.world.worldgen.cell.rivermap.lake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.RiverSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil.Vec2f;

class LakeBankHeightSettingsTest {
	private static final Levels LEVELS = new Levels(384, 63);

	@Test
	void defaultBankSettingsPreserveLegacyHeightMathBitForBit() {
		RiverSettings.Lake settings = Presets.makeRTFDefault().rivers().lakes;
		LakeConfig config = LakeConfig.of(settings, LEVELS);
		Lake lake = new Lake(new Vec2f(0.0F, 0.0F), 100.0F, 1.0F, config);

		assertBitsEqual(LEVELS.water(settings.minBankHeight), config.bankMin);
		assertBitsEqual(LEVELS.water(settings.maxBankHeight), config.bankMax);

		for(int sample = 0; sample <= 512; sample++) {
			Cell cell = new Cell();
			cell.height = -0.25F + sample / 512.0F * 1.5F;

			float alphaMin = LEVELS.water(settings.minBankHeight);
			float alphaMax = Math.min(1.0F, alphaMin + 0.275F);
			float alphaRange = alphaMax - alphaMin;
			float alpha = NoiseUtil.map(cell.height, alphaMin, alphaMax, alphaRange);
			float expected = NoiseUtil.lerp(
				LEVELS.water(settings.minBankHeight),
				LEVELS.water(settings.maxBankHeight),
				alpha
			);

			assertBitsEqual(expected, lake.getBankHeight(cell));
		}
	}

	@Test
	void changedBankSettingsChangeAppliedLakeBankHeight() {
		RiverSettings.Lake defaults = Presets.makeRTFDefault().rivers().lakes;
		RiverSettings.Lake raised = defaults.copy();
		raised.minBankHeight += 12;
		raised.maxBankHeight += 12;

		Cell defaultCell = bankCell();
		Cell raisedCell = bankCell();
		Lake defaultLake = new Lake(new Vec2f(0.0F, 0.0F), 100.0F, 1.0F, LakeConfig.of(defaults, LEVELS));
		Lake raisedLake = new Lake(new Vec2f(0.0F, 0.0F), 100.0F, 1.0F, LakeConfig.of(raised, LEVELS));

		defaultLake.apply(defaultCell, 150.0F, 0.0F);
		raisedLake.apply(raisedCell, 150.0F, 0.0F);

		assertNotEquals(Float.floatToRawIntBits(defaultCell.height), Float.floatToRawIntBits(raisedCell.height));
		assertEquals(defaultCell.riverMask, raisedCell.riverMask);
	}

	private static Cell bankCell() {
		Cell cell = new Cell();
		cell.height = 0.8F;
		cell.riverMask = 1.0F;
		cell.terrain = TerrainType.HILLS;
		return cell;
	}

	private static void assertBitsEqual(float expected, float actual) {
		assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
	}
}
