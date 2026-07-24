package raccoonman.reterraforged.tags;

import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import raccoonman.reterraforged.RTFCommon;

public final class RTFBiomeTags {
	public static final TagKey<Biome> MOUNTAIN_BIOMES = resolve("mountain_biomes");
	public static final TagKey<Biome> VOLCANO_BIOMES = resolve("volcano_biomes");

	private RTFBiomeTags() {
	}

	private static TagKey<Biome> resolve(String path) {
		return TagKey.create(Registries.BIOME, RTFCommon.location(path));
	}
}
