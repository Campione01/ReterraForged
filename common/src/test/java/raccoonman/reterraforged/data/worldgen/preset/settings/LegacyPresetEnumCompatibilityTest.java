package raccoonman.reterraforged.data.worldgen.preset.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

class LegacyPresetEnumCompatibilityTest {

	@Test
	void userSelectedSpawnMigratesToContinentCenter() {
		SpawnType decoded = SpawnType.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive("USER_SELECTED")).result().orElseThrow();
		assertEquals(SpawnType.CONTINENT_CENTER, decoded);
		assertEquals(new JsonPrimitive("CONTINENT_CENTER"), SpawnType.CODEC.encodeStart(JsonOps.INSTANCE, decoded).result().orElseThrow());
	}

	@Test
	void upliftContinentMigratesToImprovedMultiContinent() {
		ContinentType decoded = ContinentType.CODEC.parse(JsonOps.INSTANCE, new JsonPrimitive("UPLIFT")).result().orElseThrow();
		assertEquals(ContinentType.MULTI_IMPROVED, decoded);
		assertEquals(new JsonPrimitive("MULTI_IMPROVED"), ContinentType.CODEC.encodeStart(JsonOps.INSTANCE, decoded).result().orElseThrow());
	}

	@Test
	void legacyAliasesDecodeInsideACompletePreset() {
		JsonObject encoded = Preset.DIRECT_CODEC.encodeStart(JsonOps.INSTANCE, Presets.makeLegacyDefault())
			.result()
			.orElseThrow()
			.getAsJsonObject();
		JsonObject world = encoded.getAsJsonObject("world");
		world.getAsJsonObject("properties").addProperty("spawnType", "USER_SELECTED");
		world.getAsJsonObject("continent").addProperty("continentType", "UPLIFT");

		Preset decoded = Preset.DIRECT_CODEC.parse(JsonOps.INSTANCE, encoded).result().orElseThrow();
		assertEquals(SpawnType.CONTINENT_CENTER, decoded.world().properties.spawnType);
		assertEquals(ContinentType.MULTI_IMPROVED, decoded.world().continent.continentType);
	}
}
