package raccoonman.reterraforged.data.worldgen.preset.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

class CaveSettingsTest {
	@Test
	void missingDensityAlgorithmDefaultsToLegacyV2() {
		CaveSettings settings = parse(baseJson());

		assertEquals(CaveSettings.DensityAlgorithm.LEGACY_V2, settings.densityAlgorithm);
		assertEquals(CaveSettings.CompatibilityMode.AUTO, settings.compatibilityMode);
	}

	@Test
	void everyDensityAlgorithmRoundTrips() {
		for(CaveSettings.DensityAlgorithm algorithm : CaveSettings.DensityAlgorithm.values()) {
			CaveSettings settings = parse(baseJson());
			settings.densityAlgorithm = algorithm;

			var encoded = CaveSettings.CODEC.encodeStart(JsonOps.INSTANCE, settings).getOrThrow();
			CaveSettings decoded = CaveSettings.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

			assertEquals(algorithm, decoded.densityAlgorithm);
		}
	}

	private static CaveSettings parse(String json) {
		return CaveSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
	}

	private static String baseJson() {
		return """
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
	}
}
