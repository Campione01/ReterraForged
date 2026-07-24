package raccoonman.reterraforged.client.gui.widget;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

class SliderCommitTest {
	@Test
	void mouseDragCommitsEveryDisplayedValue() {
		AtomicInteger commits = new AtomicInteger();
		Slider slider = new Slider(0, 0, 108, 20, 0.0F, 0.0F, 1.0F, Component.literal("test"), Slider.Format.FLOAT, (widget, value) -> {
			commits.incrementAndGet();
			return value;
		});

		Component initialMessage = slider.getMessage();
		slider.onClick(24.0, 10.0);
		double clickedValue = slider.getValue();
		slider.mouseDragged(84.0, 10.0, 0, 60.0, 0.0);

		assertNotEquals(clickedValue, slider.getValue());
		assertNotEquals(initialMessage, slider.getMessage());
		assertEquals(2, commits.get());
	}

	@Test
	void keyboardAdjustmentCommitsImmediately() throws ReflectiveOperationException {
		AtomicInteger commits = new AtomicInteger();
		Slider slider = new Slider(0, 0, 108, 20, 0.5F, 0.0F, 1.0F, Component.literal("test"), Slider.Format.FLOAT, (widget, value) -> {
			commits.incrementAndGet();
			return value;
		});
		Field canChangeValue = AbstractSliderButton.class.getDeclaredField("canChangeValue");
		canChangeValue.setAccessible(true);
		canChangeValue.setBoolean(slider, true);

		assertTrue(slider.keyPressed(262, 0, 0));
		assertEquals(1, commits.get());
	}
}
