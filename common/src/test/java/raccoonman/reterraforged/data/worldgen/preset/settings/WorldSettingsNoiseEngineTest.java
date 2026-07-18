package raccoonman.reterraforged.data.worldgen.preset.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

class WorldSettingsNoiseEngineTest {
	private static final String BASE_JSON = """
		{
		  "continent": {
		    "continentType": "MULTI_IMPROVED",
		    "continentScale": 3000,
		    "continentJitter": 0.7
		  },
		  "controlPoints": {
		    "deepOcean": 0.1,
		    "shallowOcean": 0.25,
		    "beach": 0.327,
		    "coast": 0.448,
		    "inland": 0.502
		  },
		  "properties": {
		    "spawnType": "CONTINENT_CENTER",
		    "worldHeight": 384,
		    "worldDepth": 64,
		    "seaLevel": 63,
		    "oceanDepth": 64,
		    "lavaLevel": -54
		  }
		}
		""";

	@Test
	void oldPresetWithoutFieldDefaultsToQuickV2() {
		WorldSettings settings = decode(BASE_JSON);

		assertEquals(WorldSettings.NoiseEngine.QUICK_V2, settings.noiseEngine);
	}

	@Test
	void explicitLegacySelectionSurvivesDecoding() {
		String json = BASE_JSON.replaceFirst("\\{", "{\"noiseEngine\":\"legacy\",");

		assertEquals(WorldSettings.NoiseEngine.LEGACY, decode(json).noiseEngine);
	}

	private static WorldSettings decode(String json) {
		return WorldSettings.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(json)).getOrThrow();
	}
}
