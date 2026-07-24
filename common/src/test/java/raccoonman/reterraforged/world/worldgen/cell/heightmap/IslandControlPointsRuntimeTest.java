package raccoonman.reterraforged.world.worldgen.cell.heightmap;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings.ControlPoints;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;

class IslandControlPointsRuntimeTest {
	private static final Levels LEVELS = new Levels(384, 63);
	private static final CellPopulator OCEANS = (cell, x, z) -> cell.height = -1.0F;

	@Test
	void initialReleaseDefaultsKeepTheExistingOceanPathByIdentity() {
		ControlPoints defaults = Presets.makeRTFDefault().world().controlPoints;

		assertSame(OCEANS, Heightmap.makeIslandPopulator(LEVELS, defaults, OCEANS));
	}

	@Test
	void negativeLegacyControlPointsRemainDisabled() {
		ControlPoints disabled = controlPoints(-1.0F, -1.0F);

		assertSame(OCEANS, Heightmap.makeIslandPopulator(LEVELS, disabled, OCEANS));
	}

	@Test
	void nonDefaultControlPointsSelectTheIslandPath() {
		assertTrue(Heightmap.usesCustomIslandControlPoints(controlPoints(0.2F, 0.4F)));
	}

	private static ControlPoints controlPoints(float islandInland, float islandCoast) {
		return new ControlPoints(islandInland, islandCoast, 0.1F, 0.25F, 0.327F, 0.448F, 0.502F);
	}
}
