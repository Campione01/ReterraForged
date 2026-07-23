package raccoonman.reterraforged.world.worldgen.cell.rivermap.fade;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

class RiverTerrainFadeTest {
	private static final float[] HEIGHTS = {
		Float.NEGATIVE_INFINITY, -1.0F, -0.0F, 0.0F, 0.25F, 0.5F, 1.0F, Float.POSITIVE_INFINITY
	};
	private static final float[] EDGES = {
		Float.NEGATIVE_INFINITY, -1.0F, -0.0F, 0.0F, Float.MIN_VALUE, 0.1F, 0.35F, 0.5F, 1.0F,
		Float.POSITIVE_INFINITY
	};

	@Test
	void sharedMountainFadePreservesOriginalRiverFadeBits() {
		Cell cell = new Cell();
		for(var terrain : new raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain[] {
			null, TerrainType.FLATS, TerrainType.MOUNTAINS_1, TerrainType.ISLAND_MOUNTAINS
		}) {
			cell.terrain = terrain;
			for(float edge : EDGES) {
				cell.terrainRegionEdge = edge;
				float sharedMountainFade = RiverTerrainFade.mountainFade(cell);
				assertBitsEqual(legacyMountainFade(cell), sharedMountainFade);
				for(float heightFade : HEIGHTS) {
					assertBitsEqual(legacyValleyFade(cell, heightFade), RiverTerrainFade.valleyFade(heightFade, sharedMountainFade));
					assertBitsEqual(legacyBanksFade(cell, heightFade), RiverTerrainFade.banksFade(heightFade, sharedMountainFade));
					assertBitsEqual(legacyBedFade(cell, heightFade), RiverTerrainFade.bedFade(heightFade, sharedMountainFade));
				}
			}
		}
	}

	private static float legacyMountainFade(Cell cell) {
		if(cell.terrain == null || !cell.terrain.isMountain()) {
			return 0.0F;
		}
		float edge = NoiseUtil.clamp(cell.terrainRegionEdge, 0.0F, 0.35F);
		return NoiseUtil.interpHermite(edge / 0.35F);
	}

	private static float legacyValleyFade(Cell cell, float heightFade) {
		return heightFade * NoiseUtil.lerp(1.0F, RiverTerrainFade.MOUNTAIN_VALLEY_FADE, legacyMountainFade(cell));
	}

	private static float legacyBanksFade(Cell cell, float heightFade) {
		return heightFade * NoiseUtil.lerp(1.0F, RiverTerrainFade.MOUNTAIN_BANKS_FADE, legacyMountainFade(cell));
	}

	private static float legacyBedFade(Cell cell, float heightFade) {
		return heightFade * NoiseUtil.lerp(1.0F, RiverTerrainFade.MOUNTAIN_BED_FADE, legacyMountainFade(cell));
	}

	private static void assertBitsEqual(float expected, float actual) {
		assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
	}
}
