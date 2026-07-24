package raccoonman.reterraforged.world.worldgen.cell.terrain.populator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.MapCodec;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;

class IslandPopulatorControlPointTest {
	private static final Levels LEVELS = new Levels(384, 63);
	private static final Noise ISLAND_THRESHOLD = constant(0.75F);
	private static final Noise ISLAND_CHANCE = constant(0.0F);
	private static final CellPopulator OCEANS = (cell, x, z) -> {
		cell.height = -1.0F;
		cell.terrain = TerrainType.DEEP_OCEAN;
	};

	@Test
	void nonDefaultInlandAndCoastPointsBothChangeIslandGeometry() {
		Cell narrow = sample(islands(0.2F, 0.4F), 1.0F);
		Cell wide = sample(islands(0.2F, 0.8F), 1.0F);
		Cell shifted = sample(islands(0.45F, 0.8F), 1.0F);

		assertSame(TerrainType.MUSHROOM_FIELDS, narrow.terrain);
		assertNotEquals(Float.floatToRawIntBits(narrow.height), Float.floatToRawIntBits(wide.height));
		assertNotEquals(Float.floatToRawIntBits(wide.height), Float.floatToRawIntBits(shifted.height));
	}

	@Test
	void equalPointsFormAFiniteHardThreshold() {
		IslandPopulator threshold = islands(0.5F, 0.5F);
		Cell ocean = sample(threshold, 1.0F);
		Cell island = sample(threshold, 2.0F);

		assertEquals(-1.0F, ocean.height);
		assertSame(TerrainType.DEEP_OCEAN, ocean.terrain);
		assertSame(TerrainType.MUSHROOM_FIELDS, island.terrain);
		assertTrue(Float.isFinite(island.height));
	}

	private static IslandPopulator islands(float inland, float coast) {
		return new IslandPopulator(LEVELS, OCEANS, inland, coast, ISLAND_THRESHOLD, ISLAND_CHANCE);
	}

	private static Cell sample(IslandPopulator populator, float continentDistance) {
		Cell cell = new Cell();
		cell.continentDistance = continentDistance;
		cell.terrainRegionId = -1.0F;
		cell.terrainRegionEdge = Float.MAX_VALUE;
		populator.apply(cell, 0.0F, 0.0F);
		return cell;
	}

	private static Noise constant(float value) {
		return new Noise() {
			@Override
			public float compute(float x, float z, int seed) {
				return value;
			}

			@Override
			public float minValue() {
				return value;
			}

			@Override
			public float maxValue() {
				return value;
			}

			@Override
			public Noise mapAll(Visitor visitor) {
				return visitor.apply(this);
			}

			@Override
			public MapCodec<? extends Noise> codec() {
				throw new UnsupportedOperationException();
			}
		};
	}
}
