package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

class PresetWidgetsRandomSaltTest {

	@Test
	void nonNegativeMappingCoversBothEndsOfTheFullIntDomain() {
		assertEquals(0, PresetWidgets.toNonNegative(0));
		assertEquals(0, PresetWidgets.toNonNegative(Integer.MIN_VALUE));
		assertEquals(Integer.MAX_VALUE, PresetWidgets.toNonNegative(Integer.MAX_VALUE));
		assertEquals(Integer.MAX_VALUE, PresetWidgets.toNonNegative(-1));
	}

	@Test
	void nonNegativeMappingNeverProducesAnInvalidSalt() {
		int[] samples = {
			Integer.MIN_VALUE,
			-1_000_000_000,
			-1,
			0,
			1,
			1_000_000_000,
			Integer.MAX_VALUE
		};
		for(int sample : samples) {
			assertTrue(PresetWidgets.toNonNegative(sample) >= 0);
		}
	}

	@Test
	void nonNegativeRandomButtonPublishesTheMappedFullRangeValue() {
		AtomicInteger accepted = new AtomicInteger(-1);
		var button = PresetWidgets.createNonNegativeRandomButton("test", 0, accepted::set, () -> -1);

		button.onPress();

		assertEquals(Integer.MAX_VALUE, button.getValue());
		assertEquals(Integer.MAX_VALUE, accepted.get());
	}
}
