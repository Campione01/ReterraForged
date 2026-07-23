package raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

class ModifierTest {
	private static final float[] REGION_EDGES = {
		Float.NEGATIVE_INFINITY, -1.0F, -0.0F, 0.0F, 0.075F, 0.15F, 0.5F, 1.0F, Float.POSITIVE_INFINITY
	};
	private static final float[] RIVER_MASKS = {
		Float.NEGATIVE_INFINITY, -1.0F, -0.0F, 0.0F, 0.002F, 0.05F, 0.1F, 0.5F, 1.0F,
		Float.POSITIVE_INFINITY
	};

	@Test
	void suppliedTerrainModifierPreservesOriginalStrengthBits() {
		Modifier modifier = Modifier.range(0.2F, 0.8F);
		Cell cell = new Cell();
		for(Terrain terrain : new Terrain[] { TerrainType.FLATS, TerrainType.BADLANDS, TerrainType.MOUNTAINS_1, TerrainType.ISLAND_MOUNTAINS }) {
			cell.terrain = terrain;
			float terrainModifier = terrain.erosionModifier();
			for(float regionEdge : REGION_EDGES) {
				cell.terrainRegionEdge = regionEdge;
				for(float riverMask : RIVER_MASKS) {
					cell.riverMask = riverMask;
					assertBitsEqual(legacyStrengthModifier(cell), modifier.getStrengthModifier(cell, terrainModifier));
				}
			}
		}
	}

	private static float legacyStrengthModifier(Cell cell) {
		float strengthModifier = 1.0F;
		float erosionModifier = cell.terrain.erosionModifier();
		if(erosionModifier != 1.0F) {
			float alpha = NoiseUtil.map(cell.terrainRegionEdge, 0.0F, 0.15F, 0.15F);
			strengthModifier = NoiseUtil.lerp(1.0F, erosionModifier, alpha);
		}
		if(cell.riverMask < 0.1F) {
			strengthModifier *= NoiseUtil.map(cell.riverMask, 0.002F, 0.1F, 0.098F);
		}
		return strengthModifier;
	}

	private static void assertBitsEqual(float expected, float actual) {
		assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
	}
}
