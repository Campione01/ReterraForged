package raccoonman.reterraforged.data.worldgen.tags;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.junit.jupiter.api.Test;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.PackOutput;
import net.minecraft.tags.TagEntry;
import raccoonman.reterraforged.tags.RTFBiomeTags;

class RTFBiomeTagsProviderTest {

	@Test
	void mountainDefaultsDelegateToVanillasRegistryTagAndVolcanoesRemainExtensible() {
		InspectableProvider provider = new InspectableProvider();
		provider.addTags(null);

		assertEquals(
			List.of("#minecraft:is_mountain?"),
			provider.entries(RTFBiomeTags.MOUNTAIN_BIOMES).stream().map(TagEntry::toString).toList()
		);
		assertEquals(List.of(), provider.entries(RTFBiomeTags.VOLCANO_BIOMES));
	}

	private static final class InspectableProvider extends RTFBiomeTagsProvider {
		private InspectableProvider() {
			super(new PackOutput(Path.of(".")), new CompletableFuture<HolderLookup.Provider>());
		}

		private List<TagEntry> entries(net.minecraft.tags.TagKey<net.minecraft.world.level.biome.Biome> tag) {
			return this.getOrCreateRawBuilder(tag).build();
		}
	}
}
