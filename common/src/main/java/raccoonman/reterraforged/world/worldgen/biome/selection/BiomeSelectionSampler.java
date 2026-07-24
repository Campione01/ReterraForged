package raccoonman.reterraforged.world.worldgen.biome.selection;

import java.util.function.UnaryOperator;

import net.minecraft.world.level.levelgen.DensityFunction;

public record BiomeSelectionSampler(
	DensityFunction mountain,
	DensityFunction volcano,
	DensityFunction macroBiomeId,
	DensityFunction terrainRegionId,
	DensityFunction biomeRegionId,
	DensityFunction height
) {

	public BiomeSelectionTarget sample(DensityFunction.FunctionContext context, float temperature) {
		return new BiomeSelectionTarget(
			this.mountain.compute(context) >= 0.5D,
			this.volcano.compute(context) >= 0.5D,
			(float) this.macroBiomeId.compute(context),
			(float) this.terrainRegionId.compute(context),
			(float) this.biomeRegionId.compute(context),
			temperature,
			(float) this.height.compute(context),
			context.blockX(),
			context.blockZ()
		);
	}

	public BiomeSelectionSampler map(UnaryOperator<DensityFunction> mapper) {
		return new BiomeSelectionSampler(
			mapper.apply(this.mountain),
			mapper.apply(this.volcano),
			mapper.apply(this.macroBiomeId),
			mapper.apply(this.terrainRegionId),
			mapper.apply(this.biomeRegionId),
			mapper.apply(this.height)
		);
	}
}
