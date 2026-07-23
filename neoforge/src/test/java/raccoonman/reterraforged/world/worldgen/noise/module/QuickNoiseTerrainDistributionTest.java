package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.Lifecycle;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import raccoonman.reterraforged.data.worldgen.preset.PresetClimateNoise;
import raccoonman.reterraforged.data.worldgen.preset.PresetTerrainTypeNoise;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.data.worldgen.preset.settings.TerrainSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainCategory;
import raccoonman.reterraforged.world.worldgen.noise.function.DistanceFunction;
import raccoonman.reterraforged.world.worldgen.noise.function.EdgeFunction;
import raccoonman.reterraforged.world.worldgen.noise.function.Interpolation;
import raccoonman.reterraforged.world.worldgen.util.Seed;

class QuickNoiseTerrainDistributionTest {
	private static final int WORLD_SEED = 0x5EED_2171;
	private static final int WINDOW_SIZE = 64;
	private static final int[][] SELECTOR_ORIGINS = {
		{-4096, 3072},
		{-2048, -2048},
		{-32, -32},
		{3584, -4608}
	};

	@Test
	void mountainSelectorPreservesLegacyCoverage() {
		Noise selector = mountainSelector(Presets.makeRTFDefault(), WORLD_SEED);
		int samples = SELECTOR_ORIGINS.length * WINDOW_SIZE * WINDOW_SIZE;
		int[] legacyBuckets = new int[3];
		int[] quickBuckets = new int[3];
		int bucketMismatches = 0;
		int bitMismatches = 0;
		float maxError = 0.0F;
		double squaredError = 0.0D;

		try(QuickNoiseRuntime.Engine engine = new QuickNoiseRuntime.Engine(); QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
			for(int[] origin : SELECTOR_ORIGINS) {
				for(int dz = 0; dz < WINDOW_SIZE; dz++) {
					for(int dx = 0; dx < WINDOW_SIZE; dx++) {
						int x = origin[0] + dx;
						int z = origin[1] + dz;
						float legacy = computeLegacy(selector, x, z);
						QuickNoiseRuntime.prepareSample(x, z);
						float quick = selector.computeRoot(x, z, 0);
						if(Float.floatToRawIntBits(legacy) != Float.floatToRawIntBits(quick)) {
							bitMismatches++;
						}
						float error = Math.abs(legacy - quick);
						maxError = Math.max(maxError, error);
						squaredError += error * error;
						int legacyBucket = mountainBucket(legacy);
						int quickBucket = mountainBucket(quick);
						legacyBuckets[legacyBucket]++;
						quickBuckets[quickBucket]++;
						if(legacyBucket != quickBucket) {
							bucketMismatches++;
						}
					}
				}
			}
		}

		float rootMeanSquareError = (float)Math.sqrt(squaredError / samples);
		assertTrue(bitMismatches == 0, "Mountain selector bit mismatches=" + bitMismatches + ", max error=" + maxError + ", rmse=" + rootMeanSquareError);
		assertTrue(bucketMismatches == 0, "Mountain selector bucket mismatches=" + bucketMismatches + ", legacy=" + counts(legacyBuckets) + ", quick=" + counts(quickBuckets));
		System.out.println("RTF_QUICK_V2_MOUNTAIN_SELECTOR max=" + maxError + ",rmse=" + rootMeanSquareError + ",bitMismatches=" + bitMismatches + ",bucketMismatches=" + bucketMismatches + ",legacy=" + counts(legacyBuckets) + ",quick=" + counts(quickBuckets));
	}

	@Test
	void defaultPresetTerrainAndClimateDistributionMatchesLegacy() {
		Preset quickPreset = Presets.makeRTFDefault();
		quickPreset.world().noiseEngine = WorldSettings.NoiseEngine.QUICK_V2;
		Preset legacyPreset = quickPreset.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;

		try(GeneratorContext legacy = context(legacyPreset); GeneratorContext quick = context(quickPreset)) {
			List<int[]> origins = findTerrainOrigins(legacy);
			Cell[] legacyCells = sample(legacy, origins);
			Cell[] quickCells = sample(quick, origins);
			Comparison comparison = compare(legacyCells, quickCells);

			assertExact(comparison);
			assertTrue(quick.noiseEngine != null && quick.noiseEngine.compiledGraphCount() > 0, "QUICK_V2 must compile production terrain graphs");
			System.out.println("RTF_QUICK_V2_DEFAULT_DISTRIBUTION " + comparison);
		}
	}

	@Test
	void archipelagoTerrainDistributionMatchesLegacy() {
		Preset quickPreset = Presets.makeRTFDefault();
		quickPreset.world().noiseEngine = WorldSettings.NoiseEngine.QUICK_V2;
		quickPreset.island().enableArchipelago = true;
		Preset legacyPreset = quickPreset.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;

		try(GeneratorContext legacy = context(legacyPreset); GeneratorContext quick = context(quickPreset)) {
			int[] origin = findCategoryOrigin(legacy, TerrainCategory.ISLAND, 64);
			Cell[] legacyCells = sample(legacy, List.of(origin));
			Cell[] quickCells = sample(quick, List.of(origin));
			Comparison comparison = compare(legacyCells, quickCells);
			int legacyIslands = comparison.legacyCategories.getOrDefault(TerrainCategory.ISLAND, 0);

			assertTrue(legacyIslands > 0, comparison::toString);
			assertExact(comparison);
			assertTrue(quick.noiseEngine != null && quick.noiseEngine.compiledGraphCount() > 0, "QUICK_V2 must compile archipelago graphs");
			System.out.println("RTF_QUICK_V2_ARCHIPELAGO_DISTRIBUTION " + comparison);
		}
	}

	@Test
	void riverGenerationDistributionMatchesLegacy() {
		Preset quickPreset = Presets.makeRTFDefault();
		quickPreset.world().noiseEngine = WorldSettings.NoiseEngine.QUICK_V2;
		Preset legacyPreset = quickPreset.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;

		try(GeneratorContext legacy = context(legacyPreset); GeneratorContext quick = context(quickPreset)) {
			int[] origin = findRiverOrigin(legacy);
			Cell[] legacyCells = sampleWithRivers(legacy, List.of(origin));
			Cell[] quickCells = sampleWithRivers(quick, List.of(origin));
			Comparison comparison = compare(legacyCells, quickCells);
			int legacyRiverSamples = 0;
			for(Cell cell : legacyCells) {
				if(cell.riverMask < 0.999F) {
					legacyRiverSamples++;
				}
			}

			assertTrue(legacyRiverSamples > 0, comparison::toString);
			assertExact(comparison);
			System.out.println("RTF_QUICK_V2_RIVER_DISTRIBUTION affected=" + legacyRiverSamples + "," + comparison);
		}
	}

	private static GeneratorContext context(Preset preset) {
		return GeneratorContext.makeUncached(preset, noiseLookup(preset), WORLD_SEED, 1, 1, 1);
	}

	private static HolderGetter<Noise> noiseLookup(Preset preset) {
		MappedRegistry<Noise> registry = new MappedRegistry<>(RTFRegistries.NOISE, Lifecycle.stable());
		float ground = preset.world().properties.seaLevel / (float)preset.world().properties.terrainScaler();
		registry.register(PresetTerrainTypeNoise.GROUND, Noises.constant(ground), RegistrationInfo.BUILT_IN);
		registry.register(PresetClimateNoise.BIOME_EDGE_SHAPE, preset.climate().biomeEdgeShape.build(0), RegistrationInfo.BUILT_IN);
		return registry.asLookup();
	}

	private static List<int[]> findTerrainOrigins(GeneratorContext context) {
		Set<TerrainCategory> targets = EnumSet.of(TerrainCategory.DEEP_OCEAN, TerrainCategory.SHALLOW_OCEAN, TerrainCategory.COAST, TerrainCategory.FLATLAND, TerrainCategory.LOWLAND, TerrainCategory.HIGHLAND);
		Map<TerrainCategory, int[]> anchors = new EnumMap<>(TerrainCategory.class);
		Heightmap heightmap = context.generator.getHeightmap();
		try(QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(null)) {
			for(int[] origin : SELECTOR_ORIGINS) {
				Cell cell = new Cell();
				heightmap.applyTerrain(cell, origin[0], origin[1]);
				heightmap.applyClimate(cell, origin[0], origin[1], true);
				TerrainCategory category = cell.terrain.getCategory();
				if(targets.contains(category)) {
					anchors.putIfAbsent(category, new int[] {origin[0] - WINDOW_SIZE / 2, origin[1] - WINDOW_SIZE / 2});
				}
			}
			for(int z = -12288; z <= 12288 && anchors.size() < targets.size(); z += 128) {
				for(int x = -12288; x <= 12288 && anchors.size() < targets.size(); x += 128) {
					Cell cell = new Cell();
					heightmap.applyTerrain(cell, x, z);
					heightmap.applyClimate(cell, x, z, true);
					TerrainCategory category = cell.terrain.getCategory();
					if(targets.contains(category)) {
						anchors.putIfAbsent(category, new int[] {x - WINDOW_SIZE / 2, z - WINDOW_SIZE / 2});
					}
				}
			}
		}
		assertTrue(anchors.keySet().containsAll(targets), "Could not locate all terrain categories: " + anchors.keySet());
		List<int[]> origins = new ArrayList<>(targets.size());
		for(TerrainCategory target : targets) {
			origins.add(anchors.get(target));
		}
		return origins;
	}

	private static int[] findCategoryOrigin(GeneratorContext context, TerrainCategory target, int step) {
		Heightmap heightmap = context.generator.getHeightmap();
		try(QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(null)) {
			for(int z = -12288; z <= 12288; z += step) {
				for(int x = -12288; x <= 12288; x += step) {
					Cell cell = new Cell();
					heightmap.applyTerrain(cell, x, z);
					heightmap.applyClimate(cell, x, z, true);
					if(cell.terrain.getCategory() == target) {
						return new int[] {x - WINDOW_SIZE / 2, z - WINDOW_SIZE / 2};
					}
				}
			}
		}
		throw new AssertionError("Could not locate terrain category " + target);
	}

	private static int[] findRiverOrigin(GeneratorContext context) {
		List<int[]> terrainOrigins = findTerrainOrigins(context);
		Heightmap heightmap = context.generator.getHeightmap();
		try(QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(null)) {
			for(int originIndex = 3; originIndex < terrainOrigins.size(); originIndex++) {
				int[] terrainOrigin = terrainOrigins.get(originIndex);
				int centerX = terrainOrigin[0] + WINDOW_SIZE / 2;
				int centerZ = terrainOrigin[1] + WINDOW_SIZE / 2;
				for(int z = centerZ - 1024; z <= centerZ + 1024; z += 16) {
					for(int x = centerX - 1024; x <= centerX + 1024; x += 16) {
						Cell cell = new Cell();
						heightmap.apply(cell, x, z, true);
						if(cell.riverMask < 0.9F && !cell.terrain.getCategory().isDeepOcean() && !cell.terrain.getCategory().isShallowOcean()) {
							return new int[] {x - WINDOW_SIZE / 2, z - WINDOW_SIZE / 2};
						}
					}
				}
			}
		}
		throw new AssertionError("Could not locate an affected river sample");
	}

	private static Cell[] sample(GeneratorContext context, List<int[]> origins) {
		Cell[] cells = new Cell[origins.size() * WINDOW_SIZE * WINDOW_SIZE];
		Heightmap heightmap = context.generator.getHeightmap();
		int index = 0;
		try(QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(context.noiseEngine)) {
			for(int[] origin : origins) {
				for(int dz = 0; dz < WINDOW_SIZE; dz++) {
					for(int dx = 0; dx < WINDOW_SIZE; dx++) {
						int x = origin[0] + dx;
						int z = origin[1] + dz;
						Cell cell = new Cell();
						QuickNoiseRuntime.prepareSample(x, z);
						heightmap.applyTerrain(cell, x, z);
						heightmap.applyClimate(cell, x, z, true);
						cells[index++] = cell;
					}
				}
			}
		}
		return cells;
	}

	private static Cell[] sampleWithRivers(GeneratorContext context, List<int[]> origins) {
		Cell[] cells = new Cell[origins.size() * WINDOW_SIZE * WINDOW_SIZE];
		Heightmap heightmap = context.generator.getHeightmap();
		int index = 0;
		try(QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(context.noiseEngine)) {
			for(int[] origin : origins) {
				for(int dz = 0; dz < WINDOW_SIZE; dz++) {
					for(int dx = 0; dx < WINDOW_SIZE; dx++) {
						int x = origin[0] + dx;
						int z = origin[1] + dz;
						Cell cell = new Cell();
						QuickNoiseRuntime.prepareSample(x, z);
						heightmap.apply(cell, x, z, true);
						cells[index++] = cell;
					}
				}
			}
		}
		return cells;
	}

	private static Comparison compare(Cell[] legacy, Cell[] quick) {
		FieldStats height = new FieldStats();
		FieldStats continentEdge = new FieldStats();
		FieldStats terrainRegionEdge = new FieldStats();
		FieldStats temperature = new FieldStats();
		FieldStats moisture = new FieldStats();
		FieldStats riverMask = new FieldStats();
		Map<TerrainCategory, Integer> legacyCategories = new EnumMap<>(TerrainCategory.class);
		Map<TerrainCategory, Integer> quickCategories = new EnumMap<>(TerrainCategory.class);
		int categoryMismatches = 0;
		int terrainMismatches = 0;
		for(int index = 0; index < legacy.length; index++) {
			Cell expected = legacy[index];
			Cell actual = quick[index];
			height.add(expected.height, actual.height);
			continentEdge.add(expected.continentEdge, actual.continentEdge);
			terrainRegionEdge.add(expected.terrainRegionEdge, actual.terrainRegionEdge);
			temperature.add(expected.temperature, actual.temperature);
			moisture.add(expected.moisture, actual.moisture);
			riverMask.add(expected.riverMask, actual.riverMask);
			TerrainCategory expectedCategory = expected.terrain.getCategory();
			TerrainCategory actualCategory = actual.terrain.getCategory();
			legacyCategories.merge(expectedCategory, 1, Integer::sum);
			quickCategories.merge(actualCategory, 1, Integer::sum);
			if(expectedCategory != actualCategory) {
				categoryMismatches++;
			}
			if(!expected.terrain.getName().equals(actual.terrain.getName())) {
				terrainMismatches++;
			}
		}
		return new Comparison(height, continentEdge, terrainRegionEdge, temperature, moisture, riverMask, legacy.length, categoryMismatches, terrainMismatches, legacyCategories, quickCategories);
	}

	private static void assertExact(Comparison comparison) {
		assertTrue(comparison.height.bitMismatches() == 0, comparison::toString);
		assertTrue(comparison.continentEdge.bitMismatches() == 0, comparison::toString);
		assertTrue(comparison.terrainRegionEdge.bitMismatches() == 0, comparison::toString);
		assertTrue(comparison.temperature.bitMismatches() == 0, comparison::toString);
		assertTrue(comparison.moisture.bitMismatches() == 0, comparison::toString);
		assertTrue(comparison.riverMask.bitMismatches() == 0, comparison::toString);
		assertTrue(comparison.categoryMismatches == 0, comparison::toString);
		assertTrue(comparison.terrainMismatches == 0, comparison::toString);
	}

	private static Noise mountainSelector(Preset preset, int seed) {
		TerrainSettings.General general = preset.terrain().general;
		Seed mountainSeed = new Seed(seed).offset(general.terrainSeedOffset);
		Noise shape = Noises.worleyEdge(mountainSeed.next(), general.legacyMountainScaling ? 1000 : Math.round(1000 * preset.terrain().mountains.horizontalScale * 2.25F), EdgeFunction.DISTANCE_2_ADD, DistanceFunction.EUCLIDEAN);
		shape = Noises.warpPerlin(shape, mountainSeed.next(), 333, 2, 250.0F);
		shape = Noises.curve(shape, Interpolation.CURVE3);
		shape = Noises.clamp(shape, 0.0F, 0.9F);
		return Noises.map(shape, 0.0F, 1.0F);
	}

	private static float computeLegacy(Noise noise, float x, float z) {
		try(QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(null)) {
			return noise.compute(x, z, 0);
		}
	}

	private static int mountainBucket(float value) {
		return value < 0.3F ? 0 : value > 0.8F ? 2 : 1;
	}

	private static String counts(int[] counts) {
		return counts[0] + "/" + counts[1] + "/" + counts[2];
	}

	private static final class FieldStats {
		private float maxError;
		private double squaredError;
		private int largeErrors;
		private int bitMismatches;
		private int samples;

		private void add(float expected, float actual) {
			float error = Math.abs(expected - actual);
			this.maxError = Math.max(this.maxError, error);
			this.squaredError += error * error;
			if(error > 0.01F) {
				this.largeErrors++;
			}
			if(Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(actual)) {
				this.bitMismatches++;
			}
			this.samples++;
		}

		private int bitMismatches() {
			return this.bitMismatches;
		}

		private float maxError() {
			return this.maxError;
		}

		private float rootMeanSquareError() {
			return this.samples == 0 ? 0.0F : (float)Math.sqrt(this.squaredError / this.samples);
		}

		private float largeErrorRatio() {
			return this.samples == 0 ? 0.0F : this.largeErrors / (float)this.samples;
		}

		@Override
		public String toString() {
			return "bits=" + this.bitMismatches() + ",max=" + this.maxError() + ",rmse=" + this.rootMeanSquareError() + ",large=" + this.largeErrorRatio();
		}
	}

	private record Comparison(FieldStats height, FieldStats continentEdge, FieldStats terrainRegionEdge, FieldStats temperature, FieldStats moisture, FieldStats riverMask, int samples, int categoryMismatches, int terrainMismatches, Map<TerrainCategory, Integer> legacyCategories, Map<TerrainCategory, Integer> quickCategories) {
		private float categoryMismatchRatio() {
			return this.categoryMismatches / (float)this.samples;
		}

		private float maxCategoryShareDelta() {
			float max = 0.0F;
			for(TerrainCategory category : TerrainCategory.values()) {
				float legacyShare = this.legacyCategories.getOrDefault(category, 0) / (float)this.samples;
				float quickShare = this.quickCategories.getOrDefault(category, 0) / (float)this.samples;
				max = Math.max(max, Math.abs(legacyShare - quickShare));
			}
			return max;
		}

		@Override
		public String toString() {
			return "height{" + this.height + "}, continentEdge{" + this.continentEdge + "}, terrainRegionEdge{" + this.terrainRegionEdge + "}, temperature{" + this.temperature + "}, moisture{" + this.moisture + "}, riverMask{" + this.riverMask + "}, categoryMismatch=" + this.categoryMismatchRatio() + ", terrainMismatch=" + this.terrainMismatches / (float)this.samples + ", maxCategoryShareDelta=" + this.maxCategoryShareDelta() + ", legacyCategories=" + this.legacyCategories + ", quickCategories=" + this.quickCategories;
		}
	}
}
