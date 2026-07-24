package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.fakewater.FakeWaterBiomeTarget;

class RenderModeFakeWaterTest {
	private static final Levels LEVELS = new Levels(384, 63);

	@Test
	void fakeWaterTargetsHaveStableDistinctColors() {
		Cell cell = new Cell();

		int none = color(cell, FakeWaterBiomeTarget.NONE);
		int river = color(cell, FakeWaterBiomeTarget.RIVER);
		int ocean = color(cell, FakeWaterBiomeTarget.OCEAN);

		assertEquals(rgba(70, 70, 70), none);
		assertEquals(rgba(35, 170, 225), river);
		assertEquals(rgba(25, 65, 150), ocean);
		assertNotEquals(none, river);
		assertNotEquals(none, ocean);
		assertNotEquals(river, ocean);
	}

	@Test
	void fakeWaterModeHandlesBelowSeaLevelCellsItself() {
		Cell cell = new Cell();
		cell.height = 0.0F;
		cell.fakeWaterBiome = FakeWaterBiomeTarget.NONE;

		assertTrue(RenderMode.FAKE_WATER_BIOME.handlesWater());
		assertEquals(rgba(70, 70, 70), RenderMode.FAKE_WATER_BIOME.getColor(cell, LEVELS));
	}

	private static int color(Cell cell, FakeWaterBiomeTarget target) {
		cell.fakeWaterBiome = target;
		return RenderMode.FAKE_WATER_BIOME.getColor(cell, LEVELS);
	}

	private static int rgba(int red, int green, int blue) {
		return red + (green << 8) + (blue << 16) + (255 << 24);
	}
}
