package raccoonman.reterraforged.data.worldgen.preset.settings;

import java.util.List;

import com.mojang.serialization.Codec;

import net.minecraft.core.BlockPos;
import net.minecraft.util.Mth;
import net.minecraft.util.StringRepresentable;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.Climate.ParameterPoint;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainCategory;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;

public enum SpawnType implements StringRepresentable {
    CONTINENT_CENTER("CONTINENT_CENTER") {
		@Override
    	public BlockPos getSearchCenter(GeneratorContext ctx) {
    		long center = ctx.generator.getHeightmap().continent().getNearestCenter(0.0F, 0.0F);
    		return new BlockPos(PosUtil.unpackLeft(center), 0, PosUtil.unpackRight(center));
    	}
    	
		@Override
		public List<ParameterPoint> getParameterPoints() {
			return SpawnType.surfaceLandTargets();
		}
	}, 
    ISLANDS("ISLANDS") {

		@Override
		public BlockPos getSearchCenter(GeneratorContext ctx) {
			return SpawnType.findNearestIsland(ctx);
		}

		@Override
		public List<ParameterPoint> getParameterPoints() {
			return SpawnType.surfaceLandTargets();
		}
	},
    WORLD_ORIGIN("WORLD_ORIGIN") {

		@Override
		public BlockPos getSearchCenter(GeneratorContext ctx) {
			return BlockPos.ZERO;
		}

		@Override
		public List<ParameterPoint> getParameterPoints() {
			return List.of();
		}
	};
	
	public static final Codec<SpawnType> CODEC = StringRepresentable.fromEnum(SpawnType::values);
	private static final Climate.Parameter FULL_RANGE = Climate.Parameter.span(-1.0F, 1.0F);
	private static final Climate.Parameter SURFACE_DEPTH = Climate.Parameter.point(0.0F);
	private static final Climate.Parameter INLAND_CONTINENTALNESS = Climate.Parameter.span(-0.11F, 0.55F);
	private static final int MIN_ISLAND_SEARCH_RADIUS = 12288;
	private static final int MAX_ISLAND_SEARCH_RADIUS = 32768;

	private final String name;
	
	private SpawnType(String name) {
		this.name = name;
	}
	
	@Override
	public String getSerializedName() {
		return this.name;
	}
	
	public abstract BlockPos getSearchCenter(GeneratorContext ctx);
	
	public abstract List<ParameterPoint> getParameterPoints();

	private static List<ParameterPoint> surfaceLandTargets() {
		Climate.Parameter continentalness = Climate.Parameter.span(INLAND_CONTINENTALNESS, FULL_RANGE);
		ParameterPoint target = new Climate.ParameterPoint(FULL_RANGE, FULL_RANGE, continentalness, FULL_RANGE, SURFACE_DEPTH, FULL_RANGE, 0L);
		return List.of(target, target);
	}

	private static BlockPos findNearestIsland(GeneratorContext ctx) {
		if(!ctx.preset.island().enableArchipelago) {
			return CONTINENT_CENTER.getSearchCenter(ctx);
		}

		Heightmap heightmap = ctx.generator.getHeightmap();
		Cell cell = new Cell();
		int step = Mth.clamp(Math.round(ctx.preset.island().islandSize * 0.5F), 64, 192);
		int configuredRadius = Math.max(MIN_ISLAND_SEARCH_RADIUS, ctx.preset.world().continent.continentScale * 2);
		int maxRadius = Math.min(MAX_ISLAND_SEARCH_RADIUS, configuredRadius);

		IslandCandidate candidate = sampleIsland(heightmap, cell, 0, 0, null);
		for(int radius = step; candidate == null && radius <= maxRadius; radius += step) {
			for(int x = -radius; x <= radius; x += step) {
				candidate = sampleIsland(heightmap, cell, x, -radius, candidate);
				candidate = sampleIsland(heightmap, cell, x, radius, candidate);
			}
			for(int z = -radius + step; z < radius; z += step) {
				candidate = sampleIsland(heightmap, cell, -radius, z, candidate);
				candidate = sampleIsland(heightmap, cell, radius, z, candidate);
			}
		}

		if(candidate == null) {
			return CONTINENT_CENTER.getSearchCenter(ctx);
		}

		int refinementRadius = step * 2;
		int refinementStep = Math.max(8, step / 8);
		IslandCandidate refined = candidate;
		for(int z = candidate.z - refinementRadius; z <= candidate.z + refinementRadius; z += refinementStep) {
			for(int x = candidate.x - refinementRadius; x <= candidate.x + refinementRadius; x += refinementStep) {
				IslandCandidate sampled = sampleIsland(heightmap, cell, x, z, null);
				if(sampled != null && (sampled.inlandness > refined.inlandness || sampled.inlandness == refined.inlandness && sampled.distanceSquared < refined.distanceSquared)) {
					refined = sampled;
				}
			}
		}
		return new BlockPos(refined.x, 0, refined.z);
	}

	private static IslandCandidate sampleIsland(Heightmap heightmap, Cell cell, int x, int z, IslandCandidate current) {
		heightmap.applyTerrain(cell.reset(), x, z);
		if(cell.terrain.getCategory() != TerrainCategory.ISLAND || cell.height <= heightmap.levels().water) {
			return current;
		}
		long distanceSquared = (long)x * x + (long)z * z;
		IslandCandidate sampled = new IslandCandidate(x, z, distanceSquared, cell.continentEdge);
		if(current == null || sampled.distanceSquared < current.distanceSquared || sampled.distanceSquared == current.distanceSquared && sampled.inlandness > current.inlandness) {
			return sampled;
		}
		return current;
	}

	private record IslandCandidate(int x, int z, long distanceSquared, float inlandness) {
	}
}
