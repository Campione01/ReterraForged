package raccoonman.reterraforged.world.worldgen.cell.continent.fancy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class FancyRiverGeneratorCountTest {
	@Test
	void initialReleaseDefaultKeepsEveryHardCodedAttemptCount() {
		for(int islandId = 0; islandId < 20; ++islandId) {
			assertEquals(Math.max(1, 8 - islandId), FancyRiverGenerator.segmentLineCount(8, islandId));
			assertEquals(Math.max(4, 12 - islandId), FancyRiverGenerator.endpointCount(8, islandId));
		}
	}

	@Test
	void configuredCountChangesFancyRiverDensityAndZeroDisablesIt() {
		assertEquals(0, FancyRiverGenerator.segmentLineCount(0, 0));
		assertEquals(0, FancyRiverGenerator.endpointCount(0, 0));

		assertNotEquals(
			FancyRiverGenerator.segmentLineCount(4, 0),
			FancyRiverGenerator.segmentLineCount(16, 0)
		);
		assertNotEquals(
			FancyRiverGenerator.endpointCount(4, 0),
			FancyRiverGenerator.endpointCount(16, 0)
		);
	}
}
