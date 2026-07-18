package raccoonman.reterraforged.world.worldgen.quicknoise;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QuickNoiseNativeTest {
	@Test
	void loadsBestBackendAndGeneratesDeterministicTile() {
		assertTrue(QuickNoiseNative.initialize());
		assertTrue(switch(QuickNoiseNative.backendName()) {
			case "avx2", "scalar" -> true;
			default -> false;
		});

		float[] first = new float[QuickNoiseNative.TILE_SAMPLES];
		float[] repeated = new float[QuickNoiseNative.TILE_SAMPLES];
		assertTrue(QuickNoiseNative.fillTile(0x5EED1234L, -2, -1, 3, 0.32F, 0.085F, 0.055F, first));
		assertTrue(QuickNoiseNative.fillTile(0x5EED1234L, -2, -1, 3, 0.32F, 0.085F, 0.055F, repeated));
		assertArrayEquals(first, repeated);
	}
}
