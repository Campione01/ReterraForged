package raccoonman.reterraforged.mixin;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.NoiseSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;

class LavaLevelWorldShapeGateTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void normalPresetHeightIncludesTheConfiguredWorldDepth() {
		WorldSettings.Properties properties = Presets.makeRTFDefault().world().properties;
		NoiseSettings matching = NoiseSettings.create(
			-properties.worldDepth,
			properties.worldDepth + properties.worldHeight,
			1,
			2
		);
		NoiseSettings oldHeightOnlyShape = NoiseSettings.create(
			-properties.worldDepth,
			properties.worldHeight,
			1,
			2
		);

		assertTrue(MixinNoiseChunk.matchesPresetHeight(matching, properties));
		assertFalse(MixinNoiseChunk.matchesPresetHeight(oldHeightOnlyShape, properties));
	}

	@Test
	void zeroDepthPresetKeepsTheIdentityHeightShape() {
		WorldSettings.Properties properties = Presets.makeRTFDefault().world().properties;
		properties.worldDepth = 0;
		NoiseSettings matching = NoiseSettings.create(0, properties.worldHeight, 1, 2);

		assertTrue(MixinNoiseChunk.matchesPresetHeight(matching, properties));
	}
}
