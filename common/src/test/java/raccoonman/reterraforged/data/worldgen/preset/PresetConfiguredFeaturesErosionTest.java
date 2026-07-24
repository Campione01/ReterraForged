package raccoonman.reterraforged.data.worldgen.preset;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;

class PresetConfiguredFeaturesErosionTest {
	@Test
	void plainStoneSettingIsThreadedIntoTheConfiguredErosionFeature() {
		Preset preset = Presets.makeRTFDefault();

		assertFalse(PresetConfiguredFeatures.createErodeConfig(preset).plainStone());

		preset.miscellaneous().plainStoneErosion = true;

		assertTrue(PresetConfiguredFeatures.createErodeConfig(preset).plainStone());
	}
}
