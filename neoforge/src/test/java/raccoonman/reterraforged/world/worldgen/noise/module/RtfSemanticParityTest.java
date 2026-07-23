package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import javax.imageio.ImageIO;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import com.mojang.serialization.Lifecycle;
import com.mojang.serialization.JsonOps;

import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.neoforged.fml.loading.LoadingModList;
import raccoonman.reterraforged.client.gui.screen.presetconfig.RenderMode;
import raccoonman.reterraforged.concurrent.SimpleResource;
import raccoonman.reterraforged.concurrent.ThreadPools;
import raccoonman.reterraforged.data.worldgen.preset.PresetClimateNoise;
import raccoonman.reterraforged.data.worldgen.preset.PresetTerrainTypeNoise;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.WorldFilters;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.biome.type.BiomeType;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.cell.terrain.Terrain;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainCategory;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.cell.terrain.fakewater.FakeWaterBiomeTarget;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter.BeachDetect;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter.Erosion;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter.NoiseCorrection;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter.Smoothing;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter.Steepness;

/**
 * End-to-end semantic gate for QUICK_V2. This deliberately compares the two
 * engines through the normal RTF Cell and preview paths instead of comparing
 * isolated noise nodes or aggregate distributions.
 */
class RtfSemanticParityTest {
	private static final String DIAGNOSTICS_PROPERTY = "reterraforged.quickNoise.verifyLegacy";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final int WORLD_SEED = 0x5EED_2171;
	private static final int PREVIEW_SIZE = 256;
	private static final int PREVIEW_FACTOR = 4;
	private static final int STRATIFIED_FACTOR = 1;
	private static final int PREVIEW_ZOOM = 50;
	private static final int RIVER_DETAIL_ZOOM = 6;
	private static final List<ContinuousField> RENDERED_FIELDS = List.of(
		ContinuousField.HEIGHT,
		ContinuousField.HEIGHT_EROSION,
		ContinuousField.SEDIMENT,
		ContinuousField.GRADIENT,
		ContinuousField.CONTINENT_EDGE,
		ContinuousField.TERRAIN_REGION_EDGE,
		ContinuousField.RIVER_MASK,
		ContinuousField.TEMPERATURE,
		ContinuousField.MOISTURE
	);
	private static final List<RenderMode> RENDER_MODES = List.of(
		RenderMode.BIOME_TYPE,
		RenderMode.TERRAIN_REGION,
		RenderMode.TRANSITION_POINTS
	);
	private static final List<TerrainTarget> DEFAULT_STRATIFIED_TARGETS = List.of(
		TerrainTarget.category("deep-ocean", TerrainCategory.DEEP_OCEAN),
		TerrainTarget.category("shallow-ocean", TerrainCategory.SHALLOW_OCEAN),
		TerrainTarget.category("coast", TerrainCategory.COAST),
		TerrainTarget.category("river", TerrainCategory.RIVER),
		TerrainTarget.category("lake", TerrainCategory.LAKE),
		TerrainTarget.category("wetland", TerrainCategory.WETLAND),
		TerrainTarget.category("flatland", TerrainCategory.FLATLAND),
		TerrainTarget.category("lowland", TerrainCategory.LOWLAND),
		TerrainTarget.category("highland", TerrainCategory.HIGHLAND)
	);
	private static final List<TerrainTarget> ARCHIPELAGO_STRATIFIED_TARGETS = List.of(
		TerrainTarget.name("island-beach", TerrainType.ISLAND_BEACH.getName()),
		TerrainTarget.name("island", TerrainType.ISLAND.getName()),
		TerrainTarget.name("island-mountains", TerrainType.ISLAND_MOUNTAINS.getName())
	);
	private static final List<SearchPass> DEFAULT_SEARCH_PASSES = List.of(
		new SearchPass(8192, 128),
		new SearchPass(32768, 256),
		new SearchPass(4096, 16)
	);
	private static final List<SearchPass> ARCHIPELAGO_SEARCH_PASSES = List.of(
		new SearchPass(32768, 128),
		new SearchPass(16384, 32)
	);
	private static final Set<String> REQUIRED_ACTIVE_TERRAIN_NAMES = Set.of(
		TerrainType.DEEP_OCEAN.getName(),
		TerrainType.SHALLOW_OCEAN.getName(),
		TerrainType.COAST.getName(),
		TerrainType.BEACH.getName(),
		TerrainType.RIVER.getName(),
		TerrainType.LAKE.getName(),
		TerrainType.WETLAND.getName(),
		TerrainType.FLATS.getName(),
		TerrainType.STEPPE.getName(),
		TerrainType.BADLANDS.getName(),
		TerrainType.PLATEAU.getName(),
		TerrainType.HILLS.getName(),
		TerrainType.MOUNTAINS_1.getName(),
		TerrainType.MOUNTAINS_2.getName(),
		TerrainType.MOUNTAINS_3.getName(),
		TerrainType.MOUNTAIN_CHAIN.getName(),
		TerrainType.VOLCANO.getName(),
		TerrainType.VOLCANO_PIPE.getName(),
		TerrainType.ISLAND.getName(),
		TerrainType.ISLAND_BEACH.getName(),
		TerrainType.ISLAND_MOUNTAINS.getName()
	);
	private String previousDiagnostics;

	@BeforeEach
	void enableRootDiagnostics() {
		this.previousDiagnostics = System.getProperty(DIAGNOSTICS_PROPERTY);
		System.setProperty(DIAGNOSTICS_PROPERTY, "true");
	}

	@AfterEach
	void restoreRootDiagnostics() {
		if(this.previousDiagnostics == null) {
			System.clearProperty(DIAGNOSTICS_PROPERTY);
		} else {
			System.setProperty(DIAGNOSTICS_PROPERTY, this.previousDiagnostics);
		}
	}

	@Test
	void quickV2MatchesLegacyThroughTheCompletePreviewPipeline() throws Exception {
		Path outputRoot = outputRoot();
		Files.createDirectories(outputRoot);
		List<ScenarioReport> reports = new ArrayList<>();
		List<String> failures = new ArrayList<>();

		Preset defaultPreset = Presets.makeRTFDefault();
		reports.add(runAtSpawn("default-overview", defaultPreset, PREVIEW_ZOOM, outputRoot, failures));
		reports.add(runAtLegacyRiver("default-river-detail", defaultPreset, RIVER_DETAIL_ZOOM, outputRoot, failures));
		reports.add(runAtLegacyHighlandNative("default-native-erosion-highland", defaultPreset, outputRoot, failures));
		reports.add(runAtLegacyRiverNative("default-native-erosion-river", defaultPreset, outputRoot, failures));
		reports.addAll(runStratifiedCorpus("default-native", defaultPreset, WORLD_SEED, DEFAULT_STRATIFIED_TARGETS, DEFAULT_SEARCH_PASSES, outputRoot, failures));
		reports.addAll(runDeterministicSeedWindows(defaultPreset, outputRoot, failures));

		Preset archipelagoPreset = Presets.makeRTFDefault();
		archipelagoPreset.island().enableArchipelago = true;
		reports.add(runAtSpawn("archipelago-overview", archipelagoPreset, PREVIEW_ZOOM, outputRoot, failures));
		Preset archipelagoStress = archipelagoPreset.copy();
		archipelagoStress.island().islandDensity = 1.0F;
		archipelagoStress.island().mountainChance = 1.0F;
		archipelagoStress.island().volcanoChance = 1.0F;
		reports.addAll(runStratifiedCorpus("archipelago-native", archipelagoStress, WORLD_SEED, ARCHIPELAGO_STRATIFIED_TARGETS, ARCHIPELAGO_SEARCH_PASSES, outputRoot, failures));

		String externalPreset = System.getenv("RTF_PARITY_PRESET");
		if(externalPreset != null && !externalPreset.isBlank()) {
			Preset preset = loadPreset(Path.of(externalPreset));
			reports.add(runAtSpawn("external-overview", preset, PREVIEW_ZOOM, outputRoot, failures));
			reports.add(runAtLegacyRiver("external-river-detail", preset, RIVER_DETAIL_ZOOM, outputRoot, failures));
			reports.add(runAtLegacyHighlandNative("external-native-erosion-highland", preset, outputRoot, failures));
			reports.addAll(runStratifiedCorpus("external-native", preset, WORLD_SEED, DEFAULT_STRATIFIED_TARGETS, DEFAULT_SEARCH_PASSES, outputRoot, failures));
			if(preset.island().enableArchipelago) {
				reports.addAll(runStratifiedCorpus("external-archipelago-native", preset, WORLD_SEED, ARCHIPELAGO_STRATIFIED_TARGETS, ARCHIPELAGO_SEARCH_PASSES, outputRoot, failures));
			}
		}

		verifyCorpusCoverage(reports, failures);

		ParityReport report = new ParityReport(
			"LEGACY",
			"QUICK_V2",
			"Float.floatToRawIntBits and exact discrete identity",
			reports,
			failures
		);
		Path reportPath = outputRoot.resolve("report.json");
		try(var writer = Files.newBufferedWriter(reportPath)) {
			GSON.toJson(report, writer);
		}

		System.out.println("RTF_SEMANTIC_PARITY_REPORT " + reportPath.toAbsolutePath());
		assertTrue(failures.isEmpty(), () -> "RTF semantic parity failed; inspect " + reportPath.toAbsolutePath() + System.lineSeparator() + String.join(System.lineSeparator(), failures));
	}

	@Test
	void legacyV2PreservesLegacyAcrossProductionPreviewStages() throws Exception {
		Path outputRoot = outputRoot().resolve("legacy-v2-first-gate");
		Files.createDirectories(outputRoot);
		List<String> failures = new ArrayList<>();

		Preset defaultPreset = Presets.makeRTFDefault();
		Preset archipelagoPreset = Presets.makeRTFDefault();
		archipelagoPreset.island().enableArchipelago = true;
		List<PresetCase> cases = List.of(
			new PresetCase("default", defaultPreset),
			new PresetCase("archipelago", archipelagoPreset)
		);

		for(PresetCase testCase : cases) {
			Preset legacyPreset = testCase.preset.copy();
			legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
			Preset legacyV2Preset = testCase.preset.copy();
			legacyV2Preset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY_V2;

			try(GeneratorContext legacy = context(legacyPreset); GeneratorContext legacyV2 = context(legacyV2Preset)) {
				BlockPos center = legacyPreset.world().properties.spawnType.getSearchCenter(legacy);
				EnumMap<Stage, Frame> expectedStages = sampleStages(legacy, center.getX(), center.getZ(), PREVIEW_ZOOM);
				EnumMap<Stage, Frame> actualStages = sampleStages(legacyV2, center.getX(), center.getZ(), PREVIEW_ZOOM);
				Path scenarioRoot = outputRoot.resolve(testCase.name);
				for(Stage stage : expectedStages.keySet()) {
					StageReport report = compare(testCase.name, stage, expectedStages.get(stage), actualStages.get(stage), scenarioRoot);
					if(report.totalFloatBitMismatches() != 0 || report.discrete().totalMismatches() != 0) {
						failures.add(testCase.name + "/" + stage.fileName + ": floatBits=" + report.totalFloatBitMismatches()
							+ ", discrete=" + report.discrete().totalMismatches());
					}
				}
				assertTrue(legacyV2.legacyV2Engine == null, "Production LEGACY_V2 must not enable unproven root batching by default");
			}
		}

		assertTrue(failures.isEmpty(), () -> "LEGACY_V2 production parity failures:\n" + String.join("\n", failures));
	}

	@Test
	void legacyV2WorkerClimateCacheIsBitExactAcrossHitsMissesAndHighlands() {
		Preset source = Presets.makeRTFDefault();
		Preset legacyPreset = source.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		Preset legacyV2Preset = source.copy();
		legacyV2Preset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY_V2;

		try(GeneratorContext legacy = context(legacyPreset); GeneratorContext legacyV2 = context(legacyV2Preset)) {
			BlockPos spawn = legacyPreset.world().properties.spawnType.getSearchCenter(legacy);
			BlockPos highland = findHighlandCenter(legacy, spawn);
			List<BlockPos> sequence = List.of(
				spawn,
				spawn,
				spawn.offset(1, 0, 0),
				spawn.offset(0, 0, 1),
				spawn.offset(8192, 0, -8192),
				spawn,
				highland,
				highland,
				highland.offset(1, 0, 0),
				highland
			);

			List<ClimateProbe> expected = sampleClimateOnWorker(legacy, sequence);
			List<ClimateProbe> actual = sampleClimateOnWorker(legacyV2, sequence);
			assertEquals(expected, actual);
			assertTrue(expected.stream().anyMatch(probe -> probe.terrainCategory == TerrainCategory.HIGHLAND),
				"Climate cache regression corpus must exercise the highland override");
		}
	}

	private static List<ClimateProbe> sampleClimateOnWorker(GeneratorContext context, List<BlockPos> positions) {
		return CompletableFuture.supplyAsync(() -> {
			try {
				Heightmap heightmap = context.generator.getHeightmap();
				List<ClimateProbe> probes = new ArrayList<>(positions.size());
				for(BlockPos position : positions) {
					Cell cell = new Cell();
					float x = position.getX();
					float z = position.getZ();
					heightmap.applyTerrain(cell, x, z);
					Rivermap rivermap = Rivermap.get(cell, null, heightmap);
					heightmap.applyRivers(cell, x, z, rivermap);
					heightmap.applyClimate(cell, x, z, true);
					probes.add(ClimateProbe.capture(cell));
				}
				return probes;
			} finally {
				ThreadPools.clearWorldgenScratch();
			}
		}, ThreadPools.WORLD_GEN).join();
	}

	@Test
	void quickV2MatchesLegacyThroughTheConcurrentProductionTilePath() throws Exception {
		String externalPreset = System.getenv("RTF_PARITY_PRESET");
		Assumptions.assumeTrue(externalPreset != null && !externalPreset.isBlank());
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		int seed = -828_453_099;
		int tileX = Math.floorDiv(1_098_545, Size.blocks(3, 0).size());
		int tileZ = Math.floorDiv(-1_098_290, Size.blocks(3, 0).size());
		List<String> failures = new ArrayList<>();
		List<ProductionTileProfile> profiles = new ArrayList<>();
		for(int batchCount : List.of(1, 6)) {
			Preset preset = loadPreset(Path.of(externalPreset));
			preset.world().noiseEngine = WorldSettings.NoiseEngine.QUICK_V2;
			try(GeneratorContext context = GeneratorContext.makeUncached(preset, noiseLookup(preset), seed, 3, productionBorder(preset), batchCount)) {
				long start = System.nanoTime();
				Tile generated = context.generator.generate(tileX, tileZ).join();
				long generationNanos = System.nanoTime() - start;
				try(Tile tile = generated) {
					// Closing the tile remains outside the generation timing sample.
				}
				List<QuickNoiseRuntime.SemanticMismatch> mismatches = context.noiseEngine.semanticMismatches();
				if(!mismatches.isEmpty()) {
					failures.add("batchCount=" + batchCount + ": " + mismatches);
				}
				profiles.add(new ProductionTileProfile(batchCount, generationNanos, context.noiseEngine.compiledGraphCount(), context.noiseEngine.fallbackGraphCount(), context.noiseEngine.profileSnapshot()));
			}
		}
		Path profilePath = outputRoot().resolve("production-tile-profile.json");
		Files.createDirectories(profilePath.getParent());
		try(var writer = Files.newBufferedWriter(profilePath)) {
			GSON.toJson(profiles, writer);
		}
		System.out.println("RTF_PRODUCTION_TILE_PROFILE " + profilePath.toAbsolutePath());
		assertTrue(failures.isEmpty(), () -> "Production tile found QUICK_V2 root mismatches: " + failures);
	}

	@Test
	void quickV2MatchesLegacyForPlayerReportedProductionTile() throws Exception {
		String externalPreset = System.getenv("RTF_PARITY_PRESET");
		Assumptions.assumeTrue(externalPreset != null && !externalPreset.isBlank());
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		Preset source = loadPreset(Path.of(externalPreset));
		Preset legacyPreset = source.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		Preset quickPreset = source.copy();
		quickPreset.world().noiseEngine = WorldSettings.NoiseEngine.QUICK_V2;

		int factor = 3;
		int border = productionBorder(source);
		int tileBlockSize = Size.blocks(factor, 0).size();
		int tileX = Math.floorDiv(5_312, tileBlockSize);
		int tileZ = Math.floorDiv(15_146, tileBlockSize);
		int centerX = tileX * tileBlockSize + tileBlockSize / 2;
		int centerZ = tileZ * tileBlockSize + tileBlockSize / 2;

		try(
			GeneratorContext legacy = GeneratorContext.makeUncached(legacyPreset, noiseLookup(legacyPreset), WORLD_SEED, factor, border, 6);
			GeneratorContext quick = GeneratorContext.makeUncached(quickPreset, noiseLookup(quickPreset), WORLD_SEED, factor, border, 6);
			Tile legacyTile = legacy.generator.generate(tileX, tileZ).join();
			Tile quickTile = quick.generator.generate(tileX, tileZ).join()
		) {
			Frame expected = captureTile(legacyTile, legacy, centerX, centerZ);
			Frame actual = captureTile(quickTile, quick, centerX, centerZ);
			for(ContinuousField field : ContinuousField.values()) {
				FieldReport report = compareField(field, expected.values.get(field), actual.values.get(field), expected);
				assertEquals(0L, report.bitMismatches(), () -> "Player production tile diverged in " + field.fileName + ": " + report.firstMismatch());
			}
			DiscreteReport discrete = compareDiscrete(expected, actual);
			assertEquals(0, discrete.totalMismatches(), () -> "Player production tile discrete fields diverged: " + discrete);
		}
	}

	@Test
	void quickV2MatchesLegacyAcrossAdjacentProductionTiles() {
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		int factor = 3;
		Preset source = Presets.makeRTFDefault();
		int border = productionBorder(source);
		int tileBlockSize = Size.blocks(factor, 0).size();
		int originTileX = Math.floorDiv(5_312, tileBlockSize);
		int originTileZ = Math.floorDiv(15_146, tileBlockSize);

		for(int seed : List.of(WORLD_SEED, -828_453_099, -1_448_906_188)) {
			Preset legacyPreset = source.copy();
			legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
			Preset quickPreset = source.copy();
			quickPreset.world().noiseEngine = WorldSettings.NoiseEngine.QUICK_V2;
			try(
				GeneratorContext legacy = GeneratorContext.makeUncached(legacyPreset, noiseLookup(legacyPreset), seed, factor, border, 6);
				GeneratorContext quick = GeneratorContext.makeUncached(quickPreset, noiseLookup(quickPreset), seed, factor, border, 6)
			) {
				for(int offsetZ = -1; offsetZ <= 1; offsetZ++) {
					for(int offsetX = -1; offsetX <= 1; offsetX++) {
						int tileX = originTileX + offsetX;
						int tileZ = originTileZ + offsetZ;
						int centerX = tileX * tileBlockSize + tileBlockSize / 2;
						int centerZ = tileZ * tileBlockSize + tileBlockSize / 2;
						try(
							Tile legacyTile = legacy.generator.generate(tileX, tileZ).join();
							Tile quickTile = quick.generator.generate(tileX, tileZ).join()
						) {
							Frame expected = captureTile(legacyTile, legacy, centerX, centerZ);
							Frame actual = captureTile(quickTile, quick, centerX, centerZ);
							for(ContinuousField field : ContinuousField.values()) {
								FieldReport report = compareField(field, expected.values.get(field), actual.values.get(field), expected);
								assertEquals(0L, report.bitMismatches(), () -> "Adjacent production tile diverged for seed=" + seed
									+ ", tile=" + tileX + "," + tileZ + ", field=" + field.fileName + ": " + report.firstMismatch());
							}
							DiscreteReport discrete = compareDiscrete(expected, actual);
							assertEquals(0, discrete.totalMismatches(), () -> "Adjacent production tile discrete fields diverged for seed="
								+ seed + ", tile=" + tileX + "," + tileZ + ": " + discrete);
						}
					}
				}
			}
		}
	}

	@Test
	void cachedCellSamplerMatchesLegacyAtReportedChunksAndTileSeams() {
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		int factor = 3;
		int batchCount = 6;
		int seed = -1_448_906_188;
		Preset source = Presets.makeRTFDefault();
		Preset legacyPreset = source.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		Preset quickPreset = source.copy();
		quickPreset.world().noiseEngine = WorldSettings.NoiseEngine.QUICK_V2;

		int reportedChunkX = Math.floorDiv(5_312, 16);
		int reportedChunkZ = Math.floorDiv(15_146, 16);
		List<int[]> chunks = new ArrayList<>();
		for(int offsetZ = -1; offsetZ <= 1; offsetZ++) {
			for(int offsetX = -1; offsetX <= 1; offsetX++) {
				chunks.add(new int[] { reportedChunkX + offsetX, reportedChunkZ + offsetZ });
			}
		}
		int seamChunkX = (reportedChunkX >> factor) << factor;
		int seamChunkZ = (reportedChunkZ >> factor) << factor;
		chunks.add(new int[] { seamChunkX - 1, reportedChunkZ });
		chunks.add(new int[] { seamChunkX, reportedChunkZ });
		chunks.add(new int[] { reportedChunkX, seamChunkZ - 1 });
		chunks.add(new int[] { reportedChunkX, seamChunkZ });

		try(
			GeneratorContext legacy = GeneratorContext.makeCached(legacyPreset, noiseLookup(legacyPreset), seed, factor, batchCount, false);
			GeneratorContext quick = GeneratorContext.makeCached(quickPreset, noiseLookup(quickPreset), seed, factor, batchCount, false)
		) {
			for(int[] chunkPos : chunks) {
				int chunkX = chunkPos[0];
				int chunkZ = chunkPos[1];
				Tile.Chunk legacyChunk = legacy.cache.provideAtChunk(chunkX, chunkZ).getChunkReader(chunkX, chunkZ);
				Tile.Chunk quickChunk = quick.cache.provideAtChunk(chunkX, chunkZ).getChunkReader(chunkX, chunkZ);
				assertEquals(chunkX, legacyChunk.getChunkX(), "Legacy cache returned the wrong chunk X");
				assertEquals(chunkZ, legacyChunk.getChunkZ(), "Legacy cache returned the wrong chunk Z");
				assertEquals(chunkX, quickChunk.getChunkX(), "Quick V2 cache returned the wrong chunk X");
				assertEquals(chunkZ, quickChunk.getChunkZ(), "Quick V2 cache returned the wrong chunk Z");

				for(CellSampler.Field field : CellSampler.Field.values()) {
					DensityFunction legacySampler = new CellSampler(() -> legacy.lookup, field)
						.new CacheChunk(legacyChunk, new CellSampler.Cache2d(), chunkX, chunkZ);
					DensityFunction quickSampler = new CellSampler(() -> quick.lookup, field)
						.new CacheChunk(quickChunk, new CellSampler.Cache2d(), chunkX, chunkZ);
					for(int dz = 0; dz < 16; dz++) {
						for(int dx = 0; dx < 16; dx++) {
							int blockX = (chunkX << 4) + dx;
							int blockZ = (chunkZ << 4) + dz;
							DensityFunction.SinglePointContext sample = new DensityFunction.SinglePointContext(blockX, 64, blockZ);
							double directLegacy = field.read(legacyChunk.getCell(blockX, blockZ), legacy.generator.getHeightmap());
							double cachedLegacy = legacySampler.compute(sample);
							double cachedQuick = quickSampler.compute(sample);
							String location = "field=" + field + ", block=" + blockX + "," + blockZ;
							assertEquals(
								Double.doubleToRawLongBits(directLegacy),
								Double.doubleToRawLongBits(cachedLegacy),
								"Cached Legacy sampler fell back or selected the wrong cell at " + location
							);
							assertEquals(
								Double.doubleToRawLongBits(cachedLegacy),
								Double.doubleToRawLongBits(cachedQuick),
								"Cached Quick V2 sampler diverged from Legacy at " + location
							);
						}
					}
				}
			}
		}
	}

	@Test
	void promotedProductionRootBatchesRemainBitExactOnTheNextTile() throws Exception {
		String externalPreset = System.getenv("RTF_PARITY_PRESET");
		Assumptions.assumeTrue(externalPreset != null && !externalPreset.isBlank());
		Assumptions.assumeFalse(Boolean.getBoolean("reterraforged.quickNoise.profile"));
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		List<BatchParityProfile> profiles = new ArrayList<>();
		List<String> failures = new ArrayList<>();
		Preset defaultPreset = Presets.makeRTFDefault();
		profiles.add(verifyPromotedPreset("default", defaultPreset, List.of(
			new BlockPos(-6128, 0, -4976),
			new BlockPos(4032, 0, -4928),
			new BlockPos(1808, 0, -4976),
			new BlockPos(-752, 0, 1296),
			new BlockPos(1424, 0, 1040),
			new BlockPos(2064, 0, -4976),
			new BlockPos(784, 0, -4976)
		), failures));

		Preset archipelagoPreset = defaultPreset.copy();
		archipelagoPreset.island().enableArchipelago = true;
		archipelagoPreset.island().islandDensity = 1.0F;
		archipelagoPreset.island().mountainChance = 1.0F;
		archipelagoPreset.island().volcanoChance = 1.0F;
		profiles.add(verifyPromotedPreset("archipelago", archipelagoPreset, List.of(
			new BlockPos(-27504, 0, -29552),
			new BlockPos(-28272, 0, -29552),
			new BlockPos(-28144, 0, -29552)
		), failures));

		Preset personalPreset = loadPreset(Path.of(externalPreset));
		profiles.add(verifyPromotedPreset("personal", personalPreset, List.of(
			new BlockPos(11664, 0, 2544),
			new BlockPos(14272, 0, 17856),
			new BlockPos(9360, 0, 2544),
			new BlockPos(8080, 0, 9328),
			new BlockPos(7312, 0, 9072),
			new BlockPos(2832, 0, 2544),
			new BlockPos(5648, 0, 2544)
		), failures));
		if(personalPreset.island().enableArchipelago) {
			profiles.add(verifyPromotedPreset("personal-archipelago", personalPreset, List.of(
				new BlockPos(29328, 0, -22032),
				new BlockPos(-19312, 0, -21264),
				new BlockPos(-24048, 0, -17040)
			), failures));
		}

		Path optimizationPath = outputRoot().resolve("production-batch-optimization.json");
		Files.createDirectories(optimizationPath.getParent());
		try(var writer = Files.newBufferedWriter(optimizationPath)) {
			GSON.toJson(profiles, writer);
		}
		System.out.println("RTF_PRODUCTION_BATCH_OPTIMIZATION " + optimizationPath.toAbsolutePath());
		assertTrue(failures.isEmpty(), () -> "Promoted production roots diverged: " + failures);
	}

	private static BatchParityProfile verifyPromotedPreset(String name, Preset source, List<BlockPos> positions, List<String> failures) throws Exception {
		String promotionProperty = "reterraforged.quickNoise.batchPromotionTiles";
		String batchProperty = "reterraforged.quickNoise.batchRoots";
		String previousPromotionTiles = System.getProperty(promotionProperty);
		String previousBatchRoots = System.getProperty(batchProperty);
		System.setProperty(promotionProperty, "0");
		System.setProperty(batchProperty, "true");
		try {
			Preset preset = source.copy();
			preset.world().noiseEngine = WorldSettings.NoiseEngine.QUICK_V2;
			try(GeneratorContext context = GeneratorContext.makeUncached(preset, noiseLookup(preset), WORLD_SEED, 3, productionBorder(preset), 6, true)) {
				for(BlockPos position : positions) {
					int tileX = Math.floorDiv(position.getX(), Size.blocks(3, 0).size());
					int tileZ = Math.floorDiv(position.getZ(), Size.blocks(3, 0).size());
					try(Tile ignored = context.generator.generate(tileX, tileZ).join()) {
					}
				}
				QuickNoiseRuntime.BatchOptimization optimization = context.noiseEngine.batchOptimizationSnapshot();
				if(optimization.groups() == 0 || optimization.roots() < 2 || optimization.batchNodes() >= optimization.separateNodes()) {
					failures.add(name + " did not retain a useful promoted root batch: " + optimization);
				}
				List<QuickNoiseRuntime.SemanticMismatch> mismatches = context.noiseEngine.semanticMismatches();
				if(!mismatches.isEmpty()) {
					failures.add(name + " root mismatches: " + mismatches);
				}
				return new BatchParityProfile(name, positions.size(), optimization, mismatches);
			}
		} finally {
			if(previousPromotionTiles == null) {
				System.clearProperty(promotionProperty);
			} else {
				System.setProperty(promotionProperty, previousPromotionTiles);
			}
			if(previousBatchRoots == null) {
				System.clearProperty(batchProperty);
			} else {
				System.setProperty(batchProperty, previousBatchRoots);
			}
		}
	}

	private record BatchParityProfile(String name, int tiles, QuickNoiseRuntime.BatchOptimization optimization, List<QuickNoiseRuntime.SemanticMismatch> mismatches) {
	}

	private static void verifyCorpusCoverage(List<ScenarioReport> reports, List<String> failures) {
		Set<String> observed = new HashSet<>();
		for(ScenarioReport scenario : reports) {
			for(String key : scenario.terrainInventory().keySet()) {
				int separator = key.indexOf('/');
				observed.add(separator >= 0 ? key.substring(separator + 1) : key);
			}
		}
		Set<String> missing = new HashSet<>(REQUIRED_ACTIVE_TERRAIN_NAMES);
		missing.removeAll(observed);
		if(!missing.isEmpty()) {
			failures.add("corpus did not execute required active terrain types: " + missing.stream().sorted().toList());
		}
	}

	private static ScenarioReport runAtSpawn(String name, Preset source, int zoom, Path outputRoot, List<String> failures) throws Exception {
		Preset legacyPreset = source.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		try(GeneratorContext legacy = context(legacyPreset)) {
			BlockPos center = legacyPreset.world().properties.spawnType.getSearchCenter(legacy);
			return runScenario(name, source, center.getX(), center.getZ(), zoom, outputRoot, failures);
		}
	}

	private static ScenarioReport runAtLegacyRiver(String name, Preset source, int zoom, Path outputRoot, List<String> failures) throws Exception {
		Preset legacyPreset = source.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		try(GeneratorContext legacy = context(legacyPreset)) {
			BlockPos spawn = legacyPreset.world().properties.spawnType.getSearchCenter(legacy);
			BlockPos center = findRiverCenter(legacy, spawn);
			return runScenario(name, source, center.getX(), center.getZ(), zoom, outputRoot, failures);
		}
	}

	private static ScenarioReport runAtLegacyHighlandNative(String name, Preset source, Path outputRoot, List<String> failures) throws Exception {
		Preset legacyPreset = source.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		try(GeneratorContext legacy = context(legacyPreset)) {
			BlockPos spawn = legacyPreset.world().properties.spawnType.getSearchCenter(legacy);
			BlockPos center = findHighlandCenter(legacy, spawn);
			return runNativeScenario(name, source, center.getX(), center.getZ(), outputRoot, failures);
		}
	}

	private static ScenarioReport runAtLegacyRiverNative(String name, Preset source, Path outputRoot, List<String> failures) throws Exception {
		Preset legacyPreset = source.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		try(GeneratorContext legacy = context(legacyPreset)) {
			BlockPos spawn = legacyPreset.world().properties.spawnType.getSearchCenter(legacy);
			BlockPos center = findRiverCenter(legacy, spawn);
			return runNativeScenario(name, source, center.getX(), center.getZ(), outputRoot, failures);
		}
	}

	private static List<ScenarioReport> runStratifiedCorpus(String prefix, Preset source, int seed, List<TerrainTarget> targets, List<SearchPass> passes, Path outputRoot, List<String> failures) throws Exception {
		targets = targets.stream().map(target -> target.category() == TerrainCategory.COAST ? target.withMaximumContinentEdge(source.world().controlPoints.beach) : target).toList();
		Map<TerrainTarget, BlockPos> anchors = findTargetCenters(source, seed, targets, passes);
		List<ScenarioReport> reports = new ArrayList<>();
		for(TerrainTarget target : targets) {
			BlockPos anchor = anchors.get(target);
			if(anchor == null) {
				failures.add(prefix + ": legacy search did not hit required target " + target.label());
				continue;
			}
			List<TerrainTarget> coverage = new ArrayList<>();
			coverage.add(target);
			if(target.category() == TerrainCategory.COAST) {
				coverage.add(TerrainTarget.category("beach-after-filters", Stage.NATIVE_REQUIRED_FILTERS, TerrainCategory.BEACH));
			}
			int factor = target.category() == TerrainCategory.COAST ? 3 : STRATIFIED_FACTOR;
			reports.add(runNativeScenario(prefix + "-" + target.label(), source, seed, anchor.getX(), anchor.getZ(), factor, false, coverage, outputRoot, failures));
		}
		return reports;
	}

	private static List<ScenarioReport> runDeterministicSeedWindows(Preset source, Path outputRoot, List<String> failures) throws Exception {
		int[] seeds = {0, -1, 0x1357_9BDF, Integer.MIN_VALUE, -828_453_099};
		int[][] centers = {
			{1_000_003, -999_983},
			{-7_654_321, 4_567_891},
			{16_777_213, -12_345_679},
			{-29_000_003, 28_000_019},
			{1_098_545, -1_098_290}
		};
		List<ScenarioReport> reports = new ArrayList<>();
		for(int index = 0; index < seeds.length; index++) {
			reports.add(runNativeScenario("seed-window-" + index, source, seeds[index], centers[index][0], centers[index][1], STRATIFIED_FACTOR, false, List.of(), outputRoot, failures));
		}
		return reports;
	}

	private static Map<TerrainTarget, BlockPos> findTargetCenters(Preset source, int seed, List<TerrainTarget> targets, List<SearchPass> passes) throws Exception {
		Preset legacyPreset = source.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		Map<TerrainTarget, BlockPos> found = new LinkedHashMap<>();
		Set<TerrainTarget> remaining = new HashSet<>(targets);
		try(GeneratorContext legacy = context(legacyPreset, seed, STRATIFIED_FACTOR, 0); NoiseRootRuntime.Scope ignored = NoiseRootRuntime.bind(null)) {
			BlockPos origin = legacyPreset.world().properties.spawnType.getSearchCenter(legacy);
			Heightmap heightmap = legacy.generator.getHeightmap();
			Cell cell = new Cell();
			for(SearchPass pass : passes) {
				for(int z = origin.getZ() - pass.radius(); z <= origin.getZ() + pass.radius() && !remaining.isEmpty(); z += pass.step()) {
					Rivermap rivermap = null;
					for(int x = origin.getX() - pass.radius(); x <= origin.getX() + pass.radius() && !remaining.isEmpty(); x += pass.step()) {
						cell.reset();
						heightmap.applyTerrain(cell, x, z);
						rivermap = Rivermap.get(cell, rivermap, heightmap);
						heightmap.applyRivers(cell, x, z, rivermap);
						heightmap.applyClimate(cell, x, z, true);
						for(TerrainTarget target : List.copyOf(remaining)) {
							if(target.matches(cell)) {
								found.put(target, new BlockPos(x, 0, z));
								remaining.remove(target);
							}
						}
					}
				}
				if(remaining.isEmpty()) {
					break;
				}
			}
		}
		return found;
	}

	private static ScenarioReport runScenario(String name, Preset source, int centerX, int centerZ, int zoom, Path outputRoot, List<String> failures) throws Exception {
		Preset legacyPreset = source.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		Preset quickPreset = source.copy();
		quickPreset.world().noiseEngine = WorldSettings.NoiseEngine.QUICK_V2;
		Path scenarioRoot = outputRoot.resolve(fileName(name));
		Files.createDirectories(scenarioRoot);
		List<StageReport> stageReports = new ArrayList<>();

		try(GeneratorContext legacy = context(legacyPreset); GeneratorContext quick = context(quickPreset)) {
			EnumMap<Stage, Frame> legacyStages = sampleStages(legacy, centerX, centerZ, zoom);
			EnumMap<Stage, Frame> quickStages = sampleStages(quick, centerX, centerZ, zoom);
			for(Stage stage : legacyStages.keySet()) {
				Frame expected = legacyStages.get(stage);
				Frame actual = quickStages.get(stage);
				StageReport stageReport = compare(name, stage, expected, actual, scenarioRoot);
				stageReports.add(stageReport);
				if(stageReport.totalFloatBitMismatches() != 0 || stageReport.discrete().totalMismatches() != 0) {
					failures.add(name + "/" + stage.fileName + ": floatBits=" + stageReport.totalFloatBitMismatches() + ", discrete=" + stageReport.discrete().totalMismatches());
				}
			}
			List<QuickNoiseRuntime.SemanticMismatch> rootMismatches = quick.noiseEngine != null ? quick.noiseEngine.semanticMismatches() : List.of();
			if(!rootMismatches.isEmpty()) {
				failures.add(name + ": root diagnostics found " + rootMismatches.size() + " mismatched QUICK_V2 graph(s)");
			}
			return new ScenarioReport(name, WORLD_SEED, centerX, centerZ, zoom, PREVIEW_SIZE, -1L, Map.of(), terrainInventory(legacyStages), quick.noiseEngine != null ? quick.noiseEngine.compiledGraphCount() : 0, quick.noiseEngine != null ? quick.noiseEngine.fallbackGraphCount() : 0, rootMismatches, stageReports);
		}
	}

	private static ScenarioReport runNativeScenario(String name, Preset source, int blockX, int blockZ, Path outputRoot, List<String> failures) throws Exception {
		return runNativeScenario(name, source, WORLD_SEED, blockX, blockZ, PREVIEW_FACTOR, true, List.of(), outputRoot, failures);
	}

	private static ScenarioReport runNativeScenario(String name, Preset source, int seed, int blockX, int blockZ, int factor, boolean requireErosionActivity, List<TerrainTarget> coverageTargets, Path outputRoot, List<String> failures) throws Exception {
		Preset legacyPreset = source.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		Preset quickPreset = source.copy();
		quickPreset.world().noiseEngine = WorldSettings.NoiseEngine.QUICK_V2;
		int border = productionBorder(source);
		int tileSize = Size.blocks(factor, 0).size();
		int tileX = Math.floorDiv(blockX, tileSize);
		int tileZ = Math.floorDiv(blockZ, tileSize);
		int centerX = tileX * tileSize + tileSize / 2;
		int centerZ = tileZ * tileSize + tileSize / 2;
		Path scenarioRoot = outputRoot.resolve(fileName(name));
		Files.createDirectories(scenarioRoot);
		List<StageReport> stageReports = new ArrayList<>();

		try(GeneratorContext legacy = context(legacyPreset, seed, factor, border); GeneratorContext quick = context(quickPreset, seed, factor, border)) {
			EnumMap<Stage, Frame> legacyStages = sampleNativeErosionStages(legacy, tileX, tileZ, factor, border);
			EnumMap<Stage, Frame> quickStages = sampleNativeErosionStages(quick, tileX, tileZ, factor, border);
			long erosionChangedSamples = countErosionActivity(legacyStages.get(Stage.HYDRAULIC_EROSION));
			if(requireErosionActivity && erosionChangedSamples == 0) {
				failures.add(name + ": selected native tile contains no hydraulic erosion or deposition activity");
			}
			Map<String, Long> coverage = new LinkedHashMap<>();
			for(TerrainTarget target : coverageTargets) {
				long count = legacyStages.get(target.stage()).count(target);
				coverage.put(target.label(), count);
				if(count == 0) {
					failures.add(name + ": selected native tile did not contain required target " + target.label() + " at " + target.stage().fileName);
				}
			}
			for(Stage stage : legacyStages.keySet()) {
				StageReport stageReport = compare(name, stage, legacyStages.get(stage), quickStages.get(stage), scenarioRoot);
				stageReports.add(stageReport);
				if(stageReport.totalFloatBitMismatches() != 0 || stageReport.discrete().totalMismatches() != 0) {
					failures.add(name + "/" + stage.fileName + ": floatBits=" + stageReport.totalFloatBitMismatches() + ", discrete=" + stageReport.discrete().totalMismatches());
				}
			}
			List<QuickNoiseRuntime.SemanticMismatch> rootMismatches = quick.noiseEngine != null ? quick.noiseEngine.semanticMismatches() : List.of();
			if(!rootMismatches.isEmpty()) {
				failures.add(name + ": root diagnostics found " + rootMismatches.size() + " mismatched QUICK_V2 graph(s)");
			}
			return new ScenarioReport(name, seed, centerX, centerZ, 1, tileSize, erosionChangedSamples, coverage, terrainInventory(legacyStages), quick.noiseEngine != null ? quick.noiseEngine.compiledGraphCount() : 0, quick.noiseEngine != null ? quick.noiseEngine.fallbackGraphCount() : 0, rootMismatches, stageReports);
		}
	}

	private static EnumMap<Stage, Frame> sampleStages(GeneratorContext context, int centerX, int centerZ, int zoom) {
		EnumMap<Stage, Frame> frames = new EnumMap<>(Stage.class);
		Frame terrain = new Frame(PREVIEW_SIZE, centerX, centerZ, zoom);
		Frame rivers = new Frame(PREVIEW_SIZE, centerX, centerZ, zoom);
		Frame climate = new Frame(PREVIEW_SIZE, centerX, centerZ, zoom);
		frames.put(Stage.TERRAIN, terrain);
		frames.put(Stage.RIVERS, rivers);
		frames.put(Stage.CLIMATE, climate);

		Heightmap heightmap = context.generator.getHeightmap();
		float translateX = centerX - PREVIEW_SIZE * zoom / 2.0F;
		float translateZ = centerZ - PREVIEW_SIZE * zoom / 2.0F;
		try(NoiseRootRuntime.Scope ignored = NoiseRootRuntime.bind(context.rootNoiseEngine)) {
			Cell cell = new Cell();
			for(int chunkZ = 0; chunkZ < PREVIEW_SIZE; chunkZ += 16) {
				for(int chunkX = 0; chunkX < PREVIEW_SIZE; chunkX += 16) {
					Rivermap rivermap = null;
					for(int dz = 0; dz < 16; dz++) {
						for(int dx = 0; dx < 16; dx++) {
							int sampleX = chunkX + dx;
							int sampleZ = chunkZ + dz;
							int index = sampleZ * PREVIEW_SIZE + sampleX;
							float worldX = sampleX * zoom + translateX;
							float worldZ = sampleZ * zoom + translateZ;

							cell.reset();
							NoiseRootRuntime.prepareSample(sampleX, sampleZ, zoom, translateX, zoom, translateZ);
							heightmap.applyTerrain(cell, worldX, worldZ);
							terrain.capture(index, cell, context);

							rivermap = Rivermap.get(cell, rivermap, heightmap);
							heightmap.applyRivers(cell, worldX, worldZ, rivermap);
							rivers.capture(index, cell, context);

							heightmap.applyClimate(cell, worldX, worldZ, true);
							climate.capture(index, cell, context);
						}
					}
				}
			}
		}

		Frame filtered = new Frame(PREVIEW_SIZE, centerX, centerZ, zoom);
		try(Tile tile = generateZoomedForParity(context, centerX, centerZ, zoom)) {
			tile.iterate((cell, x, z) -> filtered.capture(z * PREVIEW_SIZE + x, cell, context));
		}
		frames.put(Stage.REQUIRED_FILTERS, filtered);
		return frames;
	}

	private static Tile generateZoomedForParity(GeneratorContext context, float centerX, float centerZ, float zoom) {
		Size blockSize = Size.blocks(PREVIEW_FACTOR, 0);
		Size chunkSize = Size.chunks(PREVIEW_FACTOR, 0);
		Cell[] cells = new Cell[blockSize.arraySize()];
		for(int index = 0; index < cells.length; index++) {
			cells[index] = new Cell();
		}
		Tile.Chunk[] chunks = new Tile.Chunk[chunkSize.arraySize()];
		Tile tile = new Tile(
			0,
			0,
			PREVIEW_FACTOR,
			0,
			blockSize,
			chunkSize,
			new SimpleResource<>(cells, ignored -> { }),
			new SimpleResource<>(chunks, ignored -> { })
		);
		Heightmap heightmap = context.generator.getHeightmap();
		float translateX = centerX - PREVIEW_SIZE * zoom / 2.0F;
		float translateZ = centerZ - PREVIEW_SIZE * zoom / 2.0F;
		try(NoiseRootRuntime.Scope ignored = NoiseRootRuntime.bind(context.rootNoiseEngine)) {
			for(int chunkZ = 0; chunkZ < chunkSize.total(); chunkZ++) {
				for(int chunkX = 0; chunkX < chunkSize.total(); chunkX++) {
					Tile.Chunk chunk = tile.getChunkWriter(chunkX, chunkZ);
					Rivermap rivermap = null;
					for(int dz = 0; dz < 16; dz++) {
						for(int dx = 0; dx < 16; dx++) {
							int sampleX = chunk.getBlockX() + dx;
							int sampleZ = chunk.getBlockZ() + dz;
							float worldX = sampleX * zoom + translateX;
							float worldZ = sampleZ * zoom + translateZ;
							Cell cell = chunk.getCell(dx, dz);

							NoiseRootRuntime.prepareSample(sampleX, sampleZ, zoom, translateX, zoom, translateZ);
							heightmap.applyTerrain(cell, worldX, worldZ);
							rivermap = Rivermap.get(cell, rivermap, heightmap);
							heightmap.applyRivers(cell, worldX, worldZ, rivermap);
							heightmap.applyClimate(cell, worldX, worldZ, true);
						}
					}
				}
			}
		}
		new WorldFilters(context).apply(tile, false);
		return tile;
	}

	private static EnumMap<Stage, Frame> sampleNativeErosionStages(GeneratorContext context, int tileX, int tileZ, int factor, int border) {
		EnumMap<Stage, Frame> frames = new EnumMap<>(Stage.class);
		int tileSize = Size.blocks(factor, 0).size();
		int centerX = tileX * tileSize + tileSize / 2;
		int centerZ = tileZ * tileSize + tileSize / 2;
		try(Tile tile = generateNativeInputForParity(context, tileX, tileZ, factor, border)) {
			frames.put(Stage.NATIVE_INPUT, captureTile(tile, context, centerX, centerZ));

			Erosion erosion = Erosion.factory(context).apply(tile.getBlockSize().total());
			erosion.apply(tile, tile.getX(), tile.getZ(), context.preset.filters().erosion.dropletsPerChunk);
			frames.put(Stage.HYDRAULIC_EROSION, captureTile(tile, context, centerX, centerZ));

			Smoothing smoothing = Smoothing.make(context.preset.filters().smoothing, context.levels);
			smoothing.apply(tile, tile.getX(), tile.getZ(), context.preset.filters().smoothing.iterations);
			frames.put(Stage.SMOOTHING, captureTile(tile, context, centerX, centerZ));

			Steepness.make(1, 10.0F, context.levels).apply(tile, tile.getX(), tile.getZ(), 1);
			BeachDetect.make(context).apply(tile, tile.getX(), tile.getZ(), 1);
			frames.put(Stage.NATIVE_REQUIRED_FILTERS, captureTile(tile, context, centerX, centerZ));

			new NoiseCorrection(context.levels).apply(tile, tile.getX(), tile.getZ(), 1);
			frames.put(Stage.NOISE_CORRECTION, captureTile(tile, context, centerX, centerZ));
		}
		return frames;
	}

	private static Tile generateNativeInputForParity(GeneratorContext context, int tileX, int tileZ, int factor, int border) {
		Size blockSize = Size.blocks(factor, border);
		Size chunkSize = Size.chunks(factor, border);
		Cell[] cells = new Cell[blockSize.arraySize()];
		for(int index = 0; index < cells.length; index++) {
			cells[index] = new Cell();
		}
		Tile.Chunk[] chunks = new Tile.Chunk[chunkSize.arraySize()];
		Tile tile = new Tile(
			tileX,
			tileZ,
			factor,
			border,
			blockSize,
			chunkSize,
			new SimpleResource<>(cells, ignored -> { }),
			new SimpleResource<>(chunks, ignored -> { })
		);
		Heightmap heightmap = context.generator.getHeightmap();
		try(NoiseRootRuntime.Scope ignored = NoiseRootRuntime.bind(context.rootNoiseEngine)) {
			for(int chunkZ = 0; chunkZ < chunkSize.total(); chunkZ++) {
				for(int chunkX = 0; chunkX < chunkSize.total(); chunkX++) {
					Tile.Chunk chunk = tile.getChunkWriter(chunkX, chunkZ);
					Rivermap rivermap = null;
					for(int dz = 0; dz < 16; dz++) {
						for(int dx = 0; dx < 16; dx++) {
							int worldX = chunk.getBlockX() + dx;
							int worldZ = chunk.getBlockZ() + dz;
							Cell cell = chunk.getCell(dx, dz);
							NoiseRootRuntime.prepareSample(worldX, worldZ);
							heightmap.applyTerrain(cell, worldX, worldZ);
							rivermap = Rivermap.get(cell, rivermap, heightmap);
							heightmap.applyRivers(cell, worldX, worldZ, rivermap);
							heightmap.applyClimate(cell, worldX, worldZ, true);
						}
					}
				}
			}
		}
		return tile;
	}

	private static Frame captureTile(Tile tile, GeneratorContext context, int centerX, int centerZ) {
		int size = tile.getBlockSize().size();
		Frame frame = new Frame(size, centerX, centerZ, 1);
		tile.iterate((cell, x, z) -> frame.capture(z * size + x, cell, context));
		return frame;
	}

	private static long countErosionActivity(Frame frame) {
		float[] eroded = frame.values.get(ContinuousField.HEIGHT_EROSION);
		float[] deposited = frame.values.get(ContinuousField.SEDIMENT);
		long count = 0;
		for(int index = 0; index < eroded.length; index++) {
			if(Float.floatToRawIntBits(eroded[index]) != 0 || Float.floatToRawIntBits(deposited[index]) != 0) {
				count++;
			}
		}
		return count;
	}

	private static Map<String, Long> terrainInventory(EnumMap<Stage, Frame> stages) {
		Map<String, Long> inventory = new LinkedHashMap<>();
		for(Map.Entry<Stage, Frame> entry : stages.entrySet()) {
			Frame frame = entry.getValue();
			for(String terrainName : frame.terrainName) {
				inventory.merge(entry.getKey().fileName + "/" + terrainName, 1L, Long::sum);
			}
		}
		return inventory;
	}

	static int productionBorder(Preset preset) {
		return Math.min(2, Math.max(1, preset.filters().erosion.dropletLifetime / 16));
	}

	private static StageReport compare(String scenario, Stage stage, Frame expected, Frame actual, Path scenarioRoot) throws IOException {
		Map<String, FieldReport> fields = new LinkedHashMap<>();
		long totalFloatBitMismatches = 0;
		for(ContinuousField field : ContinuousField.values()) {
			FieldReport report = compareField(field, expected.values.get(field), actual.values.get(field), expected);
			fields.put(field.fileName, report);
			totalFloatBitMismatches += report.bitMismatches();
		}

		DiscreteReport discrete = compareDiscrete(expected, actual);
		Path stageRoot = scenarioRoot.resolve(stage.fileName);
		Files.createDirectories(stageRoot);
		for(ContinuousField field : RENDERED_FIELDS) {
			writeFieldTriptych(stageRoot.resolve(field.fileName + ".png"), expected, actual, field, fields.get(field.fileName));
		}
		for(RenderMode mode : RENDER_MODES) {
			writeColorTriptych(stageRoot.resolve(mode.name().toLowerCase(Locale.ROOT) + ".png"), expected, actual, mode);
		}
		return new StageReport(stage.fileName, totalFloatBitMismatches, fields, discrete);
	}

	private static FieldReport compareField(ContinuousField field, float[] expected, float[] actual, Frame frame) {
		float[] errors = new float[expected.length];
		long bitMismatches = 0;
		double sum = 0.0D;
		double squared = 0.0D;
		float max = 0.0F;
		FirstMismatch first = null;
		for(int index = 0; index < expected.length; index++) {
			float error = Math.abs(expected[index] - actual[index]);
			errors[index] = error;
			sum += error;
			squared += (double)error * error;
			max = Math.max(max, error);
			if(Float.floatToRawIntBits(expected[index]) != Float.floatToRawIntBits(actual[index])) {
				bitMismatches++;
				if(first == null) {
					first = frame.mismatch(index, expected[index], actual[index]);
				}
			}
		}
		Arrays.sort(errors);
		return new FieldReport(
			field.fileName,
			bitMismatches,
			max,
			(float)(sum / expected.length),
			(float)Math.sqrt(squared / expected.length),
			percentile(errors, 0.50F),
			percentile(errors, 0.95F),
			percentile(errors, 0.99F),
			first
		);
	}

	private static DiscreteReport compareDiscrete(Frame expected, Frame actual) {
		Map<String, Integer> mismatches = new LinkedHashMap<>();
		Map<String, FirstDiscreteMismatch> first = new LinkedHashMap<>();
		compareDiscreteField("continentX", expected, actual, expected.continentX, actual.continentX, value -> Integer.toString(value), mismatches, first);
		compareDiscreteField("continentZ", expected, actual, expected.continentZ, actual.continentZ, value -> Integer.toString(value), mismatches, first);
		compareDiscreteField("erosionMask", expected, actual, expected.erosionMask, actual.erosionMask, value -> Boolean.toString(value), mismatches, first);
		compareDiscreteField("terrainId", expected, actual, expected.terrainId, actual.terrainId, value -> Integer.toString(value), mismatches, first);
		compareDiscreteField("terrainName", expected, actual, expected.terrainName, actual.terrainName, Function.identity(), mismatches, first);
		compareDiscreteField("terrainCategory", expected, actual, expected.terrainCategory, actual.terrainCategory, TerrainCategory::name, mismatches, first);
		compareDiscreteField("biome", expected, actual, expected.biome, actual.biome, BiomeType::name, mismatches, first);
		compareDiscreteField("fakeWaterBiome", expected, actual, expected.fakeWaterBiome, actual.fakeWaterBiome, FakeWaterBiomeTarget::name, mismatches, first);
		int total = mismatches.values().stream().mapToInt(Integer::intValue).sum();
		return new DiscreteReport(total, mismatches, first);
	}

	private static <T> void compareDiscreteField(String name, Frame expectedFrame, Frame actualFrame, T[] expected, T[] actual, Function<T, String> formatter, Map<String, Integer> mismatches, Map<String, FirstDiscreteMismatch> first) {
		int count = 0;
		for(int index = 0; index < expected.length; index++) {
			if(!java.util.Objects.equals(expected[index], actual[index])) {
				count++;
				if(!first.containsKey(name)) {
					FirstMismatch location = expectedFrame.mismatch(index, 0.0F, 0.0F);
					first.put(name, new FirstDiscreteMismatch(location.sampleX(), location.sampleZ(), location.worldX(), location.worldZ(), formatter.apply(expected[index]), formatter.apply(actual[index])));
				}
			}
		}
		mismatches.put(name, count);
	}

	private static void compareDiscreteField(String name, Frame expectedFrame, Frame actualFrame, int[] expected, int[] actual, Function<Integer, String> formatter, Map<String, Integer> mismatches, Map<String, FirstDiscreteMismatch> first) {
		Integer[] expectedBoxed = Arrays.stream(expected).boxed().toArray(Integer[]::new);
		Integer[] actualBoxed = Arrays.stream(actual).boxed().toArray(Integer[]::new);
		compareDiscreteField(name, expectedFrame, actualFrame, expectedBoxed, actualBoxed, formatter, mismatches, first);
	}

	private static void compareDiscreteField(String name, Frame expectedFrame, Frame actualFrame, boolean[] expected, boolean[] actual, Function<Boolean, String> formatter, Map<String, Integer> mismatches, Map<String, FirstDiscreteMismatch> first) {
		Boolean[] expectedBoxed = new Boolean[expected.length];
		Boolean[] actualBoxed = new Boolean[actual.length];
		for(int index = 0; index < expected.length; index++) {
			expectedBoxed[index] = expected[index];
			actualBoxed[index] = actual[index];
		}
		compareDiscreteField(name, expectedFrame, actualFrame, expectedBoxed, actualBoxed, formatter, mismatches, first);
	}

	private static void writeFieldTriptych(Path path, Frame expected, Frame actual, ContinuousField field, FieldReport report) throws IOException {
		int size = expected.size;
		BufferedImage image = new BufferedImage(size * 3, size, BufferedImage.TYPE_INT_ARGB);
		float[] expectedValues = expected.values.get(field);
		float[] actualValues = actual.values.get(field);
		float min = Float.POSITIVE_INFINITY;
		float max = Float.NEGATIVE_INFINITY;
		for(int index = 0; index < expectedValues.length; index++) {
			min = Math.min(min, Math.min(expectedValues[index], actualValues[index]));
			max = Math.max(max, Math.max(expectedValues[index], actualValues[index]));
		}
		for(int index = 0; index < expectedValues.length; index++) {
			int x = index % size;
			int z = index / size;
			image.setRGB(x, z, scalarColor(expectedValues[index], min, max));
			image.setRGB(size + x, z, scalarColor(actualValues[index], min, max));
			image.setRGB(size * 2 + x, z, differenceColor(Math.abs(expectedValues[index] - actualValues[index]), report.maxAbsoluteError()));
		}
		ImageIO.write(image, "png", path.toFile());
	}

	private static void writeColorTriptych(Path path, Frame expected, Frame actual, RenderMode mode) throws IOException {
		int size = expected.size;
		BufferedImage image = new BufferedImage(size * 3, size, BufferedImage.TYPE_INT_ARGB);
		int[] expectedColors = expected.colors.get(mode);
		int[] actualColors = actual.colors.get(mode);
		for(int index = 0; index < expectedColors.length; index++) {
			int x = index % size;
			int z = index / size;
			int expectedArgb = abgrToArgb(expectedColors[index]);
			int actualArgb = abgrToArgb(actualColors[index]);
			image.setRGB(x, z, expectedArgb);
			image.setRGB(size + x, z, actualArgb);
			image.setRGB(size * 2 + x, z, expectedArgb == actualArgb ? 0xFF000000 : 0xFFFF2040);
		}
		ImageIO.write(image, "png", path.toFile());
	}

	private static int scalarColor(float value, float min, float max) {
		float normalized = max > min ? (value - min) / (max - min) : 0.0F;
		normalized = Math.max(0.0F, Math.min(1.0F, normalized));
		int red = Math.round(255.0F * normalized);
		int green = Math.round(255.0F * (1.0F - Math.abs(normalized * 2.0F - 1.0F)));
		int blue = Math.round(255.0F * (1.0F - normalized));
		return 0xFF000000 | red << 16 | green << 8 | blue;
	}

	private static int differenceColor(float error, float maxError) {
		if(error == 0.0F || maxError == 0.0F) {
			return 0xFF000000;
		}
		float normalized = Math.max(0.0F, Math.min(1.0F, error / maxError));
		int red = 255;
		int green = Math.round(255.0F * normalized);
		return 0xFF000000 | red << 16 | green << 8;
	}

	private static int abgrToArgb(int abgr) {
		int alpha = abgr & 0xFF000000;
		int red = abgr & 0x000000FF;
		int green = abgr & 0x0000FF00;
		int blue = abgr & 0x00FF0000;
		return alpha | red << 16 | green | blue >> 16;
	}

	private static float percentile(float[] sorted, float percentile) {
		if(sorted.length == 0) {
			return 0.0F;
		}
		int index = Math.min(sorted.length - 1, Math.round((sorted.length - 1) * percentile));
		return sorted[index];
	}

	private static BlockPos findRiverCenter(GeneratorContext context, BlockPos origin) {
		Heightmap heightmap = context.generator.getHeightmap();
		int[] radii = {2048, 8192, 16384};
		int[] steps = {16, 32, 64};
		try(NoiseRootRuntime.Scope ignored = NoiseRootRuntime.bind(null)) {
			Cell cell = new Cell();
			for(int pass = 0; pass < radii.length; pass++) {
				int radius = radii[pass];
				int step = steps[pass];
				for(int z = origin.getZ() - radius; z <= origin.getZ() + radius; z += step) {
					for(int x = origin.getX() - radius; x <= origin.getX() + radius; x += step) {
						heightmap.apply(cell.reset(), x, z, true);
						TerrainCategory category = cell.terrain.getCategory();
						if(cell.riverMask < 0.75F && !category.isDeepOcean() && !category.isShallowOcean()) {
							return new BlockPos(x, 0, z);
						}
					}
				}
			}
		}
		throw new AssertionError("Could not find a legacy river near " + origin);
	}

	private static BlockPos findHighlandCenter(GeneratorContext context, BlockPos origin) {
		Heightmap heightmap = context.generator.getHeightmap();
		int[] radii = {2048, 8192, 16384};
		int[] steps = {64, 128, 256};
		try(NoiseRootRuntime.Scope ignored = NoiseRootRuntime.bind(null)) {
			Cell cell = new Cell();
			for(int pass = 0; pass < radii.length; pass++) {
				BlockPos best = null;
				float bestHeight = Float.NEGATIVE_INFINITY;
				int radius = radii[pass];
				int step = steps[pass];
				for(int z = origin.getZ() - radius; z <= origin.getZ() + radius; z += step) {
					for(int x = origin.getX() - radius; x <= origin.getX() + radius; x += step) {
						heightmap.applyTerrain(cell.reset(), x, z);
						if(cell.terrain.isMountain() && cell.height > bestHeight) {
							bestHeight = cell.height;
							best = new BlockPos(x, 0, z);
						}
					}
				}
				if(best != null) {
					return best;
				}
			}
		}
		throw new AssertionError("Could not find a legacy highland near " + origin);
	}

	private static GeneratorContext context(Preset preset) {
		return context(preset, WORLD_SEED, PREVIEW_FACTOR, 0);
	}

	private static GeneratorContext context(Preset preset, int seed, int factor, int border) {
		return GeneratorContext.makeUncached(preset, noiseLookup(preset), seed, factor, border, 1);
	}

	static HolderGetter<Noise> noiseLookup(Preset preset) {
		MappedRegistry<Noise> registry = new MappedRegistry<>(RTFRegistries.NOISE, Lifecycle.stable());
		float ground = preset.world().properties.seaLevel / (float)preset.world().properties.terrainScaler();
		registry.register(PresetTerrainTypeNoise.GROUND, Noises.constant(ground), RegistrationInfo.BUILT_IN);
		registry.register(PresetClimateNoise.BIOME_EDGE_SHAPE, preset.climate().biomeEdgeShape.build(0), RegistrationInfo.BUILT_IN);
		return registry.asLookup();
	}

	static Preset loadPreset(Path path) throws IOException {
		try(Reader reader = Files.newBufferedReader(path)) {
			return Preset.DIRECT_CODEC.parse(JsonOps.INSTANCE, JsonParser.parseReader(reader))
				.resultOrPartial(message -> System.err.println("RTF preset decode: " + message))
				.orElseThrow(() -> new IllegalArgumentException("Could not decode preset " + path));
		}
	}

	static Path outputRoot() {
		String configured = System.getProperty("rtf.parity.outputDir");
		return configured == null || configured.isBlank()
			? Path.of("build", "reports", "rtf-parity").toAbsolutePath().normalize()
			: Path.of(configured).toAbsolutePath().normalize();
	}

	private static String fileName(String value) {
		return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-");
	}

	private enum Stage {
		TERRAIN("01-terrain"),
		RIVERS("02-rivers"),
		CLIMATE("03-climate"),
		REQUIRED_FILTERS("04-required-filters"),
		NATIVE_INPUT("01-native-input"),
		HYDRAULIC_EROSION("02-hydraulic-erosion"),
		SMOOTHING("03-smoothing"),
		NATIVE_REQUIRED_FILTERS("04-native-required-filters"),
		NOISE_CORRECTION("05-noise-correction");

		private final String fileName;

		Stage(String fileName) {
			this.fileName = fileName;
		}
	}

	private enum ContinuousField {
		HEIGHT("height", cell -> cell.height),
		HEIGHT_EROSION("heightErosion", cell -> cell.heightErosion),
		SEDIMENT("sediment", cell -> cell.sediment),
		GRADIENT("gradient", cell -> cell.gradient),
		REGION_MOISTURE("regionMoisture", cell -> cell.regionMoisture),
		REGION_TEMPERATURE("regionTemperature", cell -> cell.regionTemperature),
		CONTINENT_ID("continentId", cell -> cell.continentId),
		CONTINENT_EDGE("continentEdge", cell -> cell.continentEdge),
		CONTINENT_DISTANCE("continentDistance", cell -> cell.continentDistance),
		TERRAIN_REGION_ID("terrainRegionId", cell -> cell.terrainRegionId),
		TERRAIN_REGION_EDGE("terrainRegionEdge", cell -> cell.terrainRegionEdge),
		TERRAIN_REGION_CENTER_X("terrainRegionCenterX", cell -> cell.terrainRegionCenterX),
		TERRAIN_REGION_CENTER_Z("terrainRegionCenterZ", cell -> cell.terrainRegionCenterZ),
		BIOME_REGION_ID("biomeRegionId", cell -> cell.biomeRegionId),
		BIOME_REGION_EDGE("biomeRegionEdge", cell -> cell.biomeRegionEdge),
		MACRO_BIOME_ID("macroBiomeId", cell -> cell.macroBiomeId),
		RIVER_MASK("riverMask", cell -> cell.riverMask),
		EROSION("erosion", cell -> cell.erosion),
		WEIRDNESS("weirdness", cell -> cell.weirdness),
		TEMPERATURE("temperature", cell -> cell.temperature),
		MOISTURE("moisture", cell -> cell.moisture),
		BEACH_NOISE("beachNoise", cell -> cell.beachNoise);

		private final String fileName;
		private final CellFloat getter;

		ContinuousField(String fileName, CellFloat getter) {
			this.fileName = fileName;
			this.getter = getter;
		}
	}

	@FunctionalInterface
	private interface CellFloat {
		float get(Cell cell);
	}

	private static final class Frame {
		private final int size;
		private final int centerX;
		private final int centerZ;
		private final int zoom;
		private final EnumMap<ContinuousField, float[]> values = new EnumMap<>(ContinuousField.class);
		private final EnumMap<RenderMode, int[]> colors = new EnumMap<>(RenderMode.class);
		private final int[] continentX;
		private final int[] continentZ;
		private final boolean[] erosionMask;
		private final int[] terrainId;
		private final String[] terrainName;
		private final TerrainCategory[] terrainCategory;
		private final BiomeType[] biome;
		private final FakeWaterBiomeTarget[] fakeWaterBiome;

		private Frame(int size, int centerX, int centerZ, int zoom) {
			this.size = size;
			this.centerX = centerX;
			this.centerZ = centerZ;
			this.zoom = zoom;
			int samples = size * size;
			for(ContinuousField field : ContinuousField.values()) {
				this.values.put(field, new float[samples]);
			}
			for(RenderMode mode : RENDER_MODES) {
				this.colors.put(mode, new int[samples]);
			}
			this.continentX = new int[samples];
			this.continentZ = new int[samples];
			this.erosionMask = new boolean[samples];
			this.terrainId = new int[samples];
			this.terrainName = new String[samples];
			this.terrainCategory = new TerrainCategory[samples];
			this.biome = new BiomeType[samples];
			this.fakeWaterBiome = new FakeWaterBiomeTarget[samples];
		}

		private void capture(int index, Cell cell, GeneratorContext context) {
			for(ContinuousField field : ContinuousField.values()) {
				this.values.get(field)[index] = field.getter.get(cell);
			}
			for(RenderMode mode : RENDER_MODES) {
				this.colors.get(mode)[index] = mode.getColor(cell, context.levels);
			}
			this.continentX[index] = cell.continentX;
			this.continentZ[index] = cell.continentZ;
			this.erosionMask[index] = cell.erosionMask;
			Terrain terrain = cell.terrain;
			this.terrainId[index] = terrain.getId();
			this.terrainName[index] = terrain.getName();
			this.terrainCategory[index] = terrain.getCategory();
			this.biome[index] = cell.biome;
			this.fakeWaterBiome[index] = cell.fakeWaterBiome;
		}

		private long count(TerrainTarget target) {
			long count = 0;
			for(int index = 0; index < this.terrainName.length; index++) {
				if(target.matches(this.terrainName[index], this.terrainCategory[index], this.values.get(ContinuousField.CONTINENT_EDGE)[index])) {
					count++;
				}
			}
			return count;
		}

		private FirstMismatch mismatch(int index, float expected, float actual) {
			int sampleX = index % this.size;
			int sampleZ = index / this.size;
			float translateX = this.centerX - this.size * this.zoom / 2.0F;
			float translateZ = this.centerZ - this.size * this.zoom / 2.0F;
			return new FirstMismatch(sampleX, sampleZ, sampleX * this.zoom + translateX, sampleZ * this.zoom + translateZ, expected, actual);
		}
	}

	private record TerrainTarget(String label, Stage stage, TerrainCategory category, String terrainName, float minContinentEdge, float maxContinentEdge) {
		private static TerrainTarget category(String label, TerrainCategory category) {
			return category(label, Stage.NATIVE_INPUT, category);
		}

		private static TerrainTarget category(String label, Stage stage, TerrainCategory category) {
			return new TerrainTarget(label, stage, category, null, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
		}

		private TerrainTarget withMaximumContinentEdge(float maxContinentEdge) {
			return new TerrainTarget(this.label, this.stage, this.category, this.terrainName, this.minContinentEdge, maxContinentEdge);
		}

		private static TerrainTarget name(String label, String terrainName) {
			return new TerrainTarget(label, Stage.NATIVE_INPUT, null, terrainName, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY);
		}

		private boolean matches(Cell cell) {
			return this.matches(cell.terrain.getName(), cell.terrain.getCategory(), cell.continentEdge);
		}

		private boolean matches(String actualName, TerrainCategory actualCategory, float continentEdge) {
			return (this.category == null || this.category == actualCategory)
				&& (this.terrainName == null || this.terrainName.equals(actualName))
				&& continentEdge >= this.minContinentEdge
				&& continentEdge < this.maxContinentEdge;
		}
	}

	private record SearchPass(int radius, int step) {
	}

	private record ParityReport(String expectedEngine, String actualEngine, String equalityContract, List<ScenarioReport> scenarios, List<String> failures) {
	}

	private record ProductionTileProfile(int batchCount, long generationNanos, int compiledGraphs, int fallbackGraphs, QuickNoiseRuntime.ProfileSnapshot roots) {
	}

	private record PresetCase(String name, Preset preset) {
	}

	private record ClimateProbe(
		int regionMoistureBits,
		int regionTemperatureBits,
		int macroBiomeIdBits,
		int continentEdgeBits,
		int temperatureBits,
		int moistureBits,
		int biomeRegionIdBits,
		String biomeName,
		TerrainCategory terrainCategory
	) {
		private static ClimateProbe capture(Cell cell) {
			return new ClimateProbe(
				Float.floatToRawIntBits(cell.regionMoisture),
				Float.floatToRawIntBits(cell.regionTemperature),
				Float.floatToRawIntBits(cell.macroBiomeId),
				Float.floatToRawIntBits(cell.continentEdge),
				Float.floatToRawIntBits(cell.temperature),
				Float.floatToRawIntBits(cell.moisture),
				Float.floatToRawIntBits(cell.biomeRegionId),
				cell.biome.name(),
				cell.terrain.getCategory()
			);
		}
	}

	private record ScenarioReport(String name, int seed, int centerX, int centerZ, int zoom, int size, long erosionChangedSamples, Map<String, Long> coverage, Map<String, Long> terrainInventory, int compiledGraphs, int fallbackGraphs, List<QuickNoiseRuntime.SemanticMismatch> rootMismatches, List<StageReport> stages) {
	}

	private record StageReport(String stage, long totalFloatBitMismatches, Map<String, FieldReport> fields, DiscreteReport discrete) {
	}

	private record FieldReport(String field, long bitMismatches, float maxAbsoluteError, float meanAbsoluteError, float rootMeanSquareError, float p50, float p95, float p99, FirstMismatch firstMismatch) {
	}

	private record FirstMismatch(int sampleX, int sampleZ, float worldX, float worldZ, float expected, float actual) {
	}

	private record FirstDiscreteMismatch(int sampleX, int sampleZ, float worldX, float worldZ, String expected, String actual) {
	}

	private record DiscreteReport(int totalMismatches, Map<String, Integer> mismatches, Map<String, FirstDiscreteMismatch> firstMismatch) {
	}
}
