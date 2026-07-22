package raccoonman.reterraforged.data.worldgen.preset.settings;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.Lifecycle;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import raccoonman.reterraforged.data.worldgen.preset.PresetClimateNoise;
import raccoonman.reterraforged.data.worldgen.preset.PresetTerrainTypeNoise;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainCategory;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;

class SpawnTypeIntegrationTest {
	private static final int WORLD_SEED = 0x5EED_2171;

	@Test
	void islandSpawnCenterSelectsGeneratedIslandTerrain() {
		Preset preset = legacyPreset();
		preset.island().enableArchipelago = true;

		try(GeneratorContext context = context(preset)) {
			BlockPos center = SpawnType.ISLANDS.getSearchCenter(context);
			Heightmap heightmap = context.generator.getHeightmap();
			Cell cell = new Cell();
			heightmap.applyTerrain(cell, center.getX(), center.getZ());

			assertEquals(TerrainCategory.ISLAND, cell.terrain.getCategory());
			assertTrue(cell.height > heightmap.levels().water);
		}
	}

	@Test
	void islandSpawnFallsBackToContinentCenterWhenArchipelagoIsDisabled() {
		Preset preset = legacyPreset();
		preset.island().enableArchipelago = false;

		try(GeneratorContext context = context(preset)) {
			assertEquals(SpawnType.CONTINENT_CENTER.getSearchCenter(context), SpawnType.ISLANDS.getSearchCenter(context));
		}
	}

	@Test
	void worldOriginIsExact() {
		Preset preset = legacyPreset();

		try(GeneratorContext context = context(preset)) {
			assertEquals(BlockPos.ZERO, SpawnType.WORLD_ORIGIN.getSearchCenter(context));
		}
	}

	private static Preset legacyPreset() {
		Preset preset = Presets.makeRTFDefault();
		preset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		return preset;
	}

	private static GeneratorContext context(Preset preset) {
		return GeneratorContext.makeUncached(preset, noiseLookup(preset), WORLD_SEED, 1, 1, 1);
	}

	private static HolderGetter<Noise> noiseLookup(Preset preset) {
		MappedRegistry<Noise> registry = new MappedRegistry<>(RTFRegistries.NOISE, Lifecycle.stable());
		float ground = preset.world().properties.seaLevel / (float)preset.world().properties.terrainScaler();
		registry.register(PresetTerrainTypeNoise.GROUND, Noises.constant(ground), RegistrationInfo.BUILT_IN);
		registry.register(PresetClimateNoise.BIOME_EDGE_SHAPE, preset.climate().biomeEdgeShape.build(0), RegistrationInfo.BUILT_IN);
		return registry.asLookup();
	}
}
