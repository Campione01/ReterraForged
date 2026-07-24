package raccoonman.reterraforged.data.worldgen.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagEntry;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.tags.RTFBlockTags;

class RTFBlockTagsProviderTest {
	private static final List<String> LEGACY_ROCK_ENTRIES = List.of(
		"minecraft:granite",
		"minecraft:andesite",
		"minecraft:stone",
		"minecraft:diorite"
	);

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void compatibleStoneDefaultPreservesLegacyRockEntriesExactly() {
		Preset preset = Presets.makeRTFDefault();

		List<TagEntry> entries = new InspectableProvider(preset).rockEntries();

		assertTrue(preset.miscellaneous().oreCompatibleStoneOnly);
		assertEquals(LEGACY_ROCK_ENTRIES, entries.stream().map(TagEntry::toString).toList());
	}

	@Test
	void disablingCompatibleStoneOnlyAddsExpandableCommonStoneTag() {
		Preset preset = Presets.makeRTFDefault();
		preset.miscellaneous().oreCompatibleStoneOnly = false;

		List<TagEntry> entries = new InspectableProvider(preset).rockEntries();

		assertEquals(
			List.of(
				"minecraft:granite",
				"minecraft:andesite",
				"minecraft:stone",
				"minecraft:diorite",
				"#c:stones?"
			),
			entries.stream().map(TagEntry::toString).toList()
		);

		TagEntry commonStones = entries.get(entries.size() - 1);
		List<String> resolved = new ArrayList<>();
		assertTrue(commonStones.build(new TagEntry.Lookup<String>() {
			@Override
			public String element(ResourceLocation location) {
				return null;
			}

			@Override
			public Collection<String> tag(ResourceLocation location) {
				return List.of("thirdparty:marble");
			}
		}, resolved::add));
		assertEquals(List.of("thirdparty:marble"), resolved);
	}

	private static final class InspectableProvider extends RTFBlockTagsProvider {
		private InspectableProvider(Preset preset) {
			super(preset, new PackOutput(Path.of(".")), new CompletableFuture<HolderLookup.Provider>());
		}

		private List<TagEntry> rockEntries() {
			this.addTags(null);
			return this.getOrCreateRawBuilder(RTFBlockTags.ROCK).build();
		}
	}
}
