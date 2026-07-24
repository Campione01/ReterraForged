package raccoonman.reterraforged.data.worldgen.tags;

import java.util.concurrent.CompletableFuture;

import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.PackOutput;
import net.minecraft.data.tags.TagsProvider;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import raccoonman.reterraforged.tags.RTFBiomeTags;

public class RTFBiomeTagsProvider extends TagsProvider<Biome> {

	public RTFBiomeTagsProvider(PackOutput packOutput, CompletableFuture<HolderLookup.Provider> completableFuture) {
		super(packOutput, Registries.BIOME, completableFuture);
	}

	@Override
	protected void addTags(HolderLookup.Provider provider) {
		this.tag(RTFBiomeTags.MOUNTAIN_BIOMES).addOptionalTag(BiomeTags.IS_MOUNTAIN.location());
		this.tag(RTFBiomeTags.VOLCANO_BIOMES);
	}
}
