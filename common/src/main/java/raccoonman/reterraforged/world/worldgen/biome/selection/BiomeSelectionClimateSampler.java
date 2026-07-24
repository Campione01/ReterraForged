package raccoonman.reterraforged.world.worldgen.biome.selection;

import org.jetbrains.annotations.Nullable;

public interface BiomeSelectionClimateSampler {
	void setBiomeSelectionSampler(@Nullable BiomeSelectionSampler sampler);

	@Nullable
	BiomeSelectionSampler getBiomeSelectionSampler();

	@Nullable
	BiomeSelectionTarget getLastBiomeSelectionTarget(int quartX, int quartY, int quartZ);
}
