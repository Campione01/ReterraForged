package raccoonman.reterraforged.client.gui.screen.presetconfig;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.Test;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import raccoonman.reterraforged.data.worldgen.preset.settings.StructureSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.StructureSettings.StructureSetEntry;

class StructureSettingsPageIntegrityTest {
	private static final ResourceKey<StructureSet> VISIBLE_SET = key("thirdparty", "visible_set");
	private static final ResourceKey<StructureSet> UNRELATED_SET = key("thirdparty", "unrelated_set");

	@Test
	void registryDefaultsRemainDisplayOnlyUntilTheyDiffer() {
		StructureSettings structures = new StructureSettings();
		StructureSetEntry defaults = new StructureSetEntry(32, 8, 1_234_567, false);

		StructureSetEntry edited = StructureSettingsPage.editableEntry(structures, VISIBLE_SET, defaults);

		assertTrue(structures.entries.isEmpty());
		assertNotSame(defaults, edited);
		assertEntryEquals(defaults, edited);

		StructureSettingsPage.commitEntry(structures, VISIBLE_SET, defaults, edited);
		assertTrue(structures.entries.isEmpty());

		edited.spacing++;
		StructureSettingsPage.commitEntry(structures, VISIBLE_SET, defaults, edited);
		assertEntryEquals(edited, structures.entries.get(VISIBLE_SET));

		edited.spacing = defaults.spacing;
		StructureSettingsPage.commitEntry(structures, VISIBLE_SET, defaults, edited);
		assertTrue(structures.entries.isEmpty());
	}

	@Test
	void existingOverridesRemainEditableWithoutTouchingOtherRegistryKeys() {
		StructureSetEntry existing = new StructureSetEntry(48, 12, 777, true);
		StructureSetEntry unrelated = new StructureSetEntry(64, 16, 888, false);
		StructureSettings structures = new StructureSettings(Map.of(
			VISIBLE_SET, existing,
			UNRELATED_SET, unrelated
		));
		StructureSetEntry defaults = new StructureSetEntry(32, 8, 1_234_567, false);

		StructureSetEntry edited = StructureSettingsPage.editableEntry(structures, VISIBLE_SET, defaults);

		assertNotSame(existing, edited);
		assertEntryEquals(existing, edited);
		assertSame(unrelated, structures.entries.get(UNRELATED_SET));

		edited.salt = 999;
		StructureSettingsPage.commitEntry(structures, VISIBLE_SET, defaults, edited);

		assertEquals(999, structures.entries.get(VISIBLE_SET).salt);
		assertSame(unrelated, structures.entries.get(UNRELATED_SET));
	}

	private static ResourceKey<StructureSet> key(String namespace, String path) {
		return ResourceKey.create(Registries.STRUCTURE_SET, ResourceLocation.fromNamespaceAndPath(namespace, path));
	}

	private static void assertEntryEquals(StructureSetEntry expected, StructureSetEntry actual) {
		assertEquals(expected.spacing, actual.spacing);
		assertEquals(expected.separation, actual.separation);
		assertEquals(expected.salt, actual.salt);
		assertEquals(expected.disabled, actual.disabled);
	}
}
