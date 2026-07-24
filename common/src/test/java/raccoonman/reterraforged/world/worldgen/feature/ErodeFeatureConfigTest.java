package raccoonman.reterraforged.world.worldgen.feature;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.mojang.serialization.JsonOps;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

class ErodeFeatureConfigTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void legacyConfigDefaultsToExistingStrataBehaviorWithoutChangingItsEncoding() {
		ErodeFeature.Config legacy = legacyConfig();
		JsonObject encoded = ErodeFeature.Config.CODEC.encodeStart(JsonOps.INSTANCE, legacy).getOrThrow().getAsJsonObject();
		ErodeFeature.Config decoded = ErodeFeature.Config.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();

		assertFalse(encoded.has("plain_stone"));
		assertFalse(decoded.plainStone());
	}

	@Test
	void plainStoneConfigRoundTripsAndOverridesOnlyTheDiscoveredRock() {
		ErodeFeature.Config plain = config(true);
		JsonObject encoded = ErodeFeature.Config.CODEC.encodeStart(JsonOps.INSTANCE, plain).getOrThrow().getAsJsonObject();
		ErodeFeature.Config decoded = ErodeFeature.Config.CODEC.parse(JsonOps.INSTANCE, encoded).getOrThrow();
		BlockState discovered = Blocks.GRANITE.defaultBlockState();

		assertTrue(encoded.get("plain_stone").getAsBoolean());
		assertTrue(decoded.plainStone());
		assertSame(discovered, ErodeFeature.erodedRockMaterial(false, discovered));
		assertSame(Blocks.STONE.defaultBlockState(), ErodeFeature.erodedRockMaterial(true, discovered));
	}

	private static ErodeFeature.Config legacyConfig() {
		return new ErodeFeature.Config(30, 140, 40, 95, 0.65F, 0.475F, 0.4F, 6F / 255F, 3F / 255F, 256, 3F / 255F, 0.55F);
	}

	private static ErodeFeature.Config config(boolean plainStone) {
		return new ErodeFeature.Config(30, 140, 40, 95, 0.65F, 0.475F, 0.4F, 6F / 255F, 3F / 255F, 256, 3F / 255F, 0.55F, plainStone);
	}
}
