package raccoonman.reterraforged.client.gui.screen.page;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LinkedPageScreenLayoutTest {

	@Test
	void titleUsesAvailableScreenWidth() {
		assertEquals(608, LinkedPageScreen.pageTitleWidth(640));
	}

	@Test
	void titleRetainsMinimumWidthOnTinyScreens() {
		assertEquals(20, LinkedPageScreen.pageTitleWidth(40));
	}
}
