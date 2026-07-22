package raccoonman.reterraforged.world.worldgen.cell.biome.spawn;

import java.util.List;

import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.biome.Climate.ParameterPoint;
import net.minecraft.world.level.biome.Climate.Sampler;
import net.minecraft.world.level.biome.Climate.TargetPoint;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
	
public class SpawnFinderFix {
	public Result result;
	private final BlockPos searchCenter;

	public SpawnFinderFix(List<ParameterPoint> list, Sampler sampler) {
		this(list, sampler, getSearchCenter(sampler));
	}

	SpawnFinderFix(List<ParameterPoint> list, Sampler sampler, BlockPos searchCenter) {
		this.searchCenter = searchCenter;
		if(list.isEmpty()) {
			this.result = new Result(searchCenter, 0L);
			return;
		}

		this.result = SpawnFinderFix.getSpawnPositionAndFitness(list, sampler, searchCenter.getX(), searchCenter.getZ(), searchCenter);
		this.radialSearch(list, sampler, 2048.0F, 512.0F);
		this.radialSearch(list, sampler, 512.0F, 32.0F);
	}

	private void radialSearch(List<ParameterPoint> list, Sampler sampler, float f, float g) {
		float h = 0.0f;
		float i = g;
		BlockPos blockPos = this.result.location();
		while (i <= f) {
			int j = blockPos.getX() + (int) (Math.sin(h) * (double) i);
			Result result = SpawnFinderFix.getSpawnPositionAndFitness(list, sampler, j,
					blockPos.getZ() + (int) (Math.cos(h) * (double) i), this.searchCenter);
			if (result.fitness() < this.result.fitness()) {
				this.result = result;
			}
			if (!((double) (h += g / i) > Math.PI * 2))
				continue;
			h = 0.0f;
			i += g;
		}
	}

	private static Result getSpawnPositionAndFitness(List<ParameterPoint> list, Sampler sampler, int i, int j, BlockPos searchCenter) {
		double d = Mth.square(2500.0);
		long relativeX = (long)i - searchCenter.getX();
		long relativeZ = (long)j - searchCenter.getZ();
		long l = (long) ((double) Mth.square(10000.0f)
				* Math.pow((double) (Mth.square(relativeX) + Mth.square(relativeZ)) / d, 2.0));
		TargetPoint targetPoint = sampler.sample(QuartPos.fromBlock(i), 0, QuartPos.fromBlock(j));
		TargetPoint targetPoint2 = new TargetPoint(targetPoint.temperature(), targetPoint.humidity(),
				targetPoint.continentalness(), targetPoint.erosion(), 0L, targetPoint.weirdness());
		long m = Long.MAX_VALUE;
		for (ParameterPoint parameterPoint : list) {
			m = Math.min(m, parameterPoint.fitness(targetPoint2));
		}
		return new Result(new BlockPos(i, 0, j), l + m);
	}

	private static BlockPos getSearchCenter(Sampler sampler) {
		if((Object)sampler instanceof RTFClimateSampler rtfClimateSampler) {
			BlockPos center = rtfClimateSampler.getSpawnSearchCenter();
			return center != null ? center : BlockPos.ZERO;
		}
		return BlockPos.ZERO;
	}

	public record Result(BlockPos location, long fitness) {
	}
}
