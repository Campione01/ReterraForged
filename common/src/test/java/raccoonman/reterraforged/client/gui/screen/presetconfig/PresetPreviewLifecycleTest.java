package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.Test;

import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import raccoonman.reterraforged.client.gui.screen.page.LinkedPageScreen.Page;

class PresetPreviewLifecycleTest {
	@Test
	void optionalFiltersRefreshExactlyWhenThePageRequirementChanges() {
		PresetEditorPage terrain = new TerrainSettingsPage(null, null);
		PresetEditorPage filters = new FilterSettingsPage(null, null);

		assertFalse(PresetEditorPage.requiresOptionalPreviewFilterRefresh(false, terrain));
		assertTrue(PresetEditorPage.requiresOptionalPreviewFilterRefresh(false, filters));
		assertFalse(PresetEditorPage.requiresOptionalPreviewFilterRefresh(true, filters));
		assertTrue(PresetEditorPage.requiresOptionalPreviewFilterRefresh(true, terrain));
	}

	@Test
	void previewIsRetainedOnlyWhileNavigatingBetweenEditorPages() {
		assertTrue(PresetConfigScreen.retainsPreview(new WorldSettingsPage(null, null)));
		assertFalse(PresetConfigScreen.retainsPreview(new NonEditorPage()));
	}

	private static final class NonEditorPage implements Page {
		@Override
		public Component title() {
			return CommonComponents.EMPTY;
		}

		@Override
		public void init() {
		}

		@Override
		public Optional<Page> previous() {
			return Optional.empty();
		}

		@Override
		public Optional<Page> next() {
			return Optional.empty();
		}
	}
}
