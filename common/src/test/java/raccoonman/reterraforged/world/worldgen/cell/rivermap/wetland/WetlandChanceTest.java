package raccoonman.reterraforged.world.worldgen.cell.rivermap.wetland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.RiverSettings;

class WetlandChanceTest {
	@Test
	void initialReleaseDefaultKeepsDecisionsAndRandomStateExactly() {
		WetlandConfig config = config(WetlandConfig.INITIAL_DEFAULT_CHANCE);

		assertEquals(4, config.skipSize);
		for(int seed = -1024; seed < 1024; ++seed) {
			Random legacy = new Random(seed);
			Random actual = new Random(seed);

			assertEquals(legacy.nextInt(4) == 0, config.shouldGenerate(actual));
			assertEquals(legacy.nextLong(), actual.nextLong());
		}
	}

	@Test
	void nonDefaultChanceIsContinuousInsteadOfRoundedToOneSkipBucket() {
		WetlandConfig lower = config(0.51F);
		WetlandConfig higher = config(0.54F);

		assertEquals(lower.skipSize, higher.skipSize);
		assertFalse(lower.shouldGenerate(new FixedFloatRandom(0.52F)));
		assertTrue(higher.shouldGenerate(new FixedFloatRandom(0.52F)));
	}

	@Test
	void chanceEndpointsAreExact() {
		assertFalse(config(0.0F).shouldGenerate(new FixedFloatRandom(0.0F)));
		assertTrue(config(1.0F).shouldGenerate(new FixedFloatRandom(0.999999F)));
	}

	private static WetlandConfig config(float chance) {
		return new WetlandConfig(new RiverSettings.Wetland(chance, 175, 225));
	}

	private static final class FixedFloatRandom extends Random {
		private static final long serialVersionUID = 1L;
		private final float value;

		private FixedFloatRandom(float value) {
			this.value = value;
		}

		@Override
		public float nextFloat() {
			return this.value;
		}

		@Override
		public int nextInt(int bound) {
			throw new AssertionError("non-default chance must not use the quantized path");
		}
	}
}
