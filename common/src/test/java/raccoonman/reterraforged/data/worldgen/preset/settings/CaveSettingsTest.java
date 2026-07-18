package raccoonman.reterraforged.data.worldgen.preset.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

class CaveSettingsTest {
	@Test
	void missingDensityAlgorithmDefaultsToQuickV1() {
		String json = """
			{
			  "entranceCaveProbability": 0.0,
			  "cheeseCaveDepthOffset": 1.5625,
			  "cheeseCaveProbability": 1.0,
			  "spaghettiCaveProbability": 1.0,
			  "noodleCaveProbability": 1.0,
			  "caveCarverProbability": 0.14285715,
			  "deepCaveCarverProbability": 0.07,
			  "ravineCarverProbability": 0.02,
			  "largeOreVeins": true,
			  "legacyCarverDistribution": false
			}
			""";
		CaveSettings settings = CaveSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();

		assertEquals(CaveSettings.DensityAlgorithm.QUICK_V1, settings.densityAlgorithm);
		assertEquals(CaveSettings.CompatibilityMode.AUTO, settings.compatibilityMode);
	}
}
