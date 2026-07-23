package raccoonman.reterraforged.client.data;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings;

class CaveDensityLanguageTest {

	@Test
	void everyCaveDensityAlgorithmHasEnglishAndChineseLabels() throws IOException {
		for(String language : new String[] { "en_us", "zh_cn" }) {
			JsonObject translations = readLanguage(language);
			for(CaveSettings.DensityAlgorithm algorithm : CaveSettings.DensityAlgorithm.values()) {
				String key = RTFTranslationKeys.GUI_VALUE_CAVE_DENSITY_ALGORITHM + "."
					+ algorithm.getSerializedName().toLowerCase(Locale.ROOT);
				assertTrue(translations.has(key), () -> language + " is missing " + key);
			}
		}
	}

	private static JsonObject readLanguage(String language) throws IOException {
		String resource = "/assets/reterraforged/lang/" + language + ".json";
		try(var input = CaveDensityLanguageTest.class.getResourceAsStream(resource)) {
			if(input == null) {
				throw new IOException("Missing test resource " + resource);
			}
			try(var reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
				return JsonParser.parseReader(reader).getAsJsonObject();
			}
		}
	}
}
