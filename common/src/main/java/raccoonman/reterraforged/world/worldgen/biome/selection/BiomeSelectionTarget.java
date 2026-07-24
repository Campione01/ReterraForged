package raccoonman.reterraforged.world.worldgen.biome.selection;

public record BiomeSelectionTarget(
	boolean mountain,
	boolean volcano,
	float macroBiomeId,
	float terrainRegionId,
	float biomeRegionId,
	float temperature,
	float height,
	int blockX,
	int blockZ
) {
}
