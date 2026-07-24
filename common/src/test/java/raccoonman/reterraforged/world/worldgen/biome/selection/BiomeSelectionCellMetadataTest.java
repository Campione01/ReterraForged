package raccoonman.reterraforged.world.worldgen.biome.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;

class BiomeSelectionCellMetadataTest {

	@Test
	void cellSamplerExposesTerrainAndOriginalThresholdNoiseWithoutClimateRemapping() {
		Cell mountain = new Cell();
		mountain.terrain = TerrainType.MOUNTAINS_1;
		mountain.macroBiomeId = 0.37F;
		mountain.terrainRegionId = 0.81F;
		mountain.height = 0.62F;

		assertEquals(0.62F, CellSampler.Field.HEIGHT.read(mountain, null));
		assertEquals(1.0F, CellSampler.Field.MOUNTAIN.read(mountain, null));
		assertEquals(0.0F, CellSampler.Field.VOLCANO.read(mountain, null));
		assertEquals(0.37F, CellSampler.Field.MACRO_BIOME.read(mountain, null));
		assertEquals(0.81F, CellSampler.Field.TERRAIN_REGION.read(mountain, null));

		Cell volcano = new Cell();
		volcano.terrain = TerrainType.VOLCANO;

		assertEquals(1.0F, CellSampler.Field.MOUNTAIN.read(volcano, null));
		assertEquals(1.0F, CellSampler.Field.VOLCANO.read(volcano, null));
	}
}
