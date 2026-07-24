package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import org.junit.jupiter.api.Test;

class PresetPageNavigationTest {

	@Test
	void terrainToRiverSectionHasSymmetricNavigation() {
		TerrainSettingsPage terrain = new TerrainSettingsPage(null, null);
		FakeWaterBiomeSettingsPage fakeWater = assertInstanceOf(FakeWaterBiomeSettingsPage.class, terrain.next().orElseThrow());
		IslandSettingsPage island = assertInstanceOf(IslandSettingsPage.class, fakeWater.next().orElseThrow());
		RiverSettingsPage river = assertInstanceOf(RiverSettingsPage.class, island.next().orElseThrow());

		assertInstanceOf(IslandSettingsPage.class, river.previous().orElseThrow());
		assertInstanceOf(FakeWaterBiomeSettingsPage.class, island.previous().orElseThrow());
		assertInstanceOf(TerrainSettingsPage.class, fakeWater.previous().orElseThrow());
	}
}
