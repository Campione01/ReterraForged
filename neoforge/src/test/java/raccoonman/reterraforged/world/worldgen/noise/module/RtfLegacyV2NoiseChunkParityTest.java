package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.Lifecycle;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.HolderOwner;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.NoiseRouterData;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.neoforged.fml.loading.LoadingModList;
import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseRouterData;
import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings.CompatibilityMode;
import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings.DensityAlgorithm;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.WorldLookup;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;

class RtfLegacyV2NoiseChunkParityTest {
	private static final int SEED = -1_448_906_188;
	private static final int CORPUS_SEED = 0x5EED_2171;
	private static final int TILE_FACTOR = 3;
	private static final int BATCH_COUNT = 6;
	private static final int ARTIFACT_CHUNK_X = Math.floorDiv(5_312, 16);
	private static final int ARTIFACT_CHUNK_Z = Math.floorDiv(15_146, 16);
	private static final int CLIENT_CHUNK_X = 505;
	private static final int CLIENT_CHUNK_Z = 583;

	@BeforeAll
	static void bootstrapMinecraft() {
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void legacyV2MatchesLegacyThroughProductionNoiseChunksAtCentersAndTileSeams() throws Exception {
		HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
		Templates templates = templates(vanilla);
		long samples = 0L;
		long legacyFallbacks = 0L;
		long legacyV2Fallbacks = 0L;

		try(
			GeneratorContext legacyContext = context(templates.legacy().preset());
			GeneratorContext legacyV2Context = context(templates.legacyV2().preset())
		) {
			for(ChunkTarget target : productionTargets()) {
				Fixture legacy = fixture(templates.legacy(), legacyContext, target);
				Fixture legacyV2 = fixture(templates.legacyV2(), legacyV2Context, target);
				samples += compareCompleteChunk(target, legacy, legacyV2);
				legacyFallbacks += legacy.fallback().calls();
				legacyV2Fallbacks += legacyV2.fallback().calls();
			}
		}

		assertTrue(legacyFallbacks > 0L, "Production Legacy NoiseChunk never exercised cross-chunk Cache2d fallback");
		assertTrue(legacyV2Fallbacks > 0L, "Production Legacy V2 NoiseChunk never exercised cross-chunk Cache2d fallback");
		System.out.printf(
			Locale.ROOT,
			"RTF_LEGACY_V2_NOISE_CHUNK productionChunks=%d samples=%d cacheOnce=%d/%d cellMarkers=%d/%d fallbacks=%d/%d%n",
			productionTargets().size(),
			samples,
			templates.legacy().cacheOnceCount(),
			templates.legacyV2().cacheOnceCount(),
			templates.legacy().cellMarkerCount(),
			templates.legacyV2().cellMarkerCount(),
			legacyFallbacks,
			legacyV2Fallbacks
		);
	}

	@Test
	void cachedSlopedCheeseExperimentRemainsUnrefutedAcrossProductionCorpus() throws Exception {
		HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
		PresetCorpus corpus = cacheExperimentPreset();
		ExperimentTemplates templates = cacheExperimentTemplates(corpus.preset(), vanilla);
		long samples = 0L;
		long legacyFallbacks = 0L;
		long cachedFallbacks = 0L;
		long legacyCacheChunks = 0L;
		long cachedCacheChunks = 0L;
		ExperimentDifference first = null;

		search:
		for(int seed : cacheExperimentSeeds()) {
			try(GeneratorContext context = context(corpus.preset(), seed)) {
				for(ChunkTarget target : cacheExperimentTargets()) {
					Fixture legacy = fixture(templates.legacy(), context, target, seed);
					Fixture cached = fixture(templates.cached(), context, target, seed);
					ChunkScan scan = scanCompleteChunk(target, legacy, cached);
					samples += scan.samples();
					legacyFallbacks += legacy.fallback().calls();
					cachedFallbacks += cached.fallback().calls();
					legacyCacheChunks += legacy.cacheChunkCount();
					cachedCacheChunks += cached.cacheChunkCount();
					if(scan.difference() != null) {
						first = new ExperimentDifference(seed, scan.difference());
						break search;
					}
				}
			}
		}

		String firstDescription = first == null ? "UNREFUTED" : first.describe();
		System.out.printf(
			Locale.ROOT,
			"RTF_CACHE_ONCE_SLOPED_CHEESE preset=%s samples=%d cacheOnce=%d/%d cacheChunks=%d/%d fallbacks=%d/%d first=%s%n",
			corpus.label(),
			samples,
			templates.legacy().cacheOnceCount(),
			templates.cached().cacheOnceCount(),
			legacyCacheChunks,
			cachedCacheChunks,
			legacyFallbacks,
			cachedFallbacks,
			firstDescription
		);
		long expectedSamples = (long) cacheExperimentSeeds().size()
			* cacheExperimentTargets().size()
			* 16L
			* 16L
			* (corpus.preset().world().properties.worldDepth + corpus.preset().world().properties.worldHeight);
		assertEquals(expectedSamples, samples, "Cache-marker corpus did not scan every complete NoiseChunk");
		assertTrue(legacyFallbacks > 0L, "Legacy corpus never exercised cross-chunk Cache2d fallback");
		assertEquals(legacyFallbacks, cachedFallbacks, "Cache marker changed Cache2d fallback traffic");
		assertTrue(
			first == null,
			first == null
				? "cacheOnce(slopedCheese) remains unrefuted"
				: "cacheOnce(slopedCheese) first counterexample: " + first.describe()
		);
	}

	private static Templates templates(HolderLookup.Provider vanilla) throws Exception {
		Preset source = sourcePreset().preset();
		Preset legacyPreset = source.copy();
		legacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		legacyPreset.caves().densityAlgorithm = DensityAlgorithm.LEGACY;
		legacyPreset.caves().compatibilityMode = CompatibilityMode.RTF;
		Preset legacyV2Preset = source.copy();
		legacyV2Preset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY_V2;
		legacyV2Preset.caves().densityAlgorithm = DensityAlgorithm.LEGACY_V2;
		legacyV2Preset.caves().compatibilityMode = CompatibilityMode.RTF;

		Template legacy = template(legacyPreset, vanilla);
		Template legacyV2 = template(legacyV2Preset, vanilla);
		assertTrue(legacy.cellMarkerCount() > 0, "Production Legacy density graph exposed no CellSampler markers");
		assertEquals(legacy.cellMarkerCount(), legacyV2.cellMarkerCount(), "Legacy V2 changed the production CellSampler surface");
		assertEquals(
			legacy.cacheOnceCount(),
			legacyV2.cacheOnceCount(),
			"Legacy V2 must not add a cacheOnce marker to final density"
		);
		assertEquals(
			legacy.router().finalDensity(),
			legacyV2.router().finalDensity(),
			"Legacy and Legacy V2 must export the same final-density graph"
		);
		return new Templates(legacy, legacyV2);
	}

	private static PresetCorpus cacheExperimentPreset() throws Exception {
		PresetCorpus source = sourcePreset();
		Preset preset = source.preset().copy();
		preset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		preset.caves().densityAlgorithm = DensityAlgorithm.LEGACY;
		return new PresetCorpus(source.label(), preset);
	}

	private static PresetCorpus sourcePreset() throws Exception {
		String externalPreset = System.getenv("RTF_PARITY_PRESET");
		Preset source;
		String label;
		if(externalPreset != null && !externalPreset.isBlank()) {
			Path path = Path.of(externalPreset).toAbsolutePath().normalize();
			source = RtfSemanticParityTest.loadPreset(path);
			label = "external:" + path;
		} else {
			source = Presets.makeRTFDefault();
			label = "default";
		}
		return new PresetCorpus(label, source);
	}

	private static ExperimentTemplates cacheExperimentTemplates(Preset preset, HolderLookup.Provider vanilla) {
		HolderGetter<DensityFunction> densityFunctions = productionDensityFunctions(preset, vanilla);
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters = vanilla.lookupOrThrow(Registries.NOISE);
		NoiseRouter legacyRouter = RtfRouterAccess.create(preset, densityFunctions, noiseParameters);
		NoiseRouter cachedRouter = withCachedSlopedCheeseRangeInput(preset, legacyRouter, densityFunctions);
		Template legacy = new Template(
			preset,
			legacyRouter,
			noiseParameters,
			countCellMarkers(legacyRouter),
			countCacheOnce(legacyRouter.finalDensity())
		);
		Template cached = new Template(
			preset,
			cachedRouter,
			noiseParameters,
			countCellMarkers(cachedRouter),
			countCacheOnce(cachedRouter.finalDensity())
		);
		assertEquals(legacy.cellMarkerCount(), cached.cellMarkerCount(), "Test-only cache marker changed the CellSampler surface");
		assertEquals(
			legacy.cacheOnceCount() + 2,
			cached.cacheOnceCount(),
			"One shared test-only cacheOnce must appear on the selector and entrance graph edges"
		);
		return new ExperimentTemplates(legacy, cached);
	}

	private static Template template(Preset preset, HolderLookup.Provider vanilla) {
		HolderGetter<DensityFunction> densityFunctions = productionDensityFunctions(preset, vanilla);
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters = vanilla.lookupOrThrow(Registries.NOISE);
		NoiseRouter router = RtfRouterAccess.create(preset, densityFunctions, noiseParameters);
		return new Template(preset, router, noiseParameters, countCellMarkers(router), countCacheOnce(router.finalDensity()));
	}

	private static GeneratorContext context(Preset preset) {
		return context(preset, SEED);
	}

	private static GeneratorContext context(Preset preset, int seed) {
		return GeneratorContext.makeCached(
			preset,
			RtfSemanticParityTest.noiseLookup(preset),
			seed,
			TILE_FACTOR,
			BATCH_COUNT,
			false
		);
	}

	private static Fixture fixture(
		Template template,
		GeneratorContext context,
		ChunkTarget target
	) throws ReflectiveOperationException {
		return fixture(template, context, target, SEED);
	}

	private static Fixture fixture(
		Template template,
		GeneratorContext context,
		ChunkTarget target,
		int seed
	) throws ReflectiveOperationException {
		Tile tile = context.cache.provideAtChunk(target.chunkX(), target.chunkZ());
		Tile.Chunk realChunk = tile.getChunkReader(target.chunkX(), target.chunkZ());
		int startX = target.chunkX() << 4;
		int startZ = target.chunkZ() << 4;
		assertEquals(target.chunkX(), realChunk.getChunkX(), "Tile cache returned the wrong chunk X for " + target.label());
		assertEquals(target.chunkZ(), realChunk.getChunkZ(), "Tile cache returned the wrong chunk Z for " + target.label());
		assertNotNull(realChunk.getCell(startX, startZ), "Real tile missed the NoiseChunk origin for " + target.label());

		Supplier<WorldLookup> worldLookup = () -> context.lookup;
		EnumMap<CellSampler.Field, CellSampler> samplers = new EnumMap<>(CellSampler.Field.class);
		NoiseRouter sampledRouter = template.router().mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof CellSampler.Marker marker) {
					return samplers.computeIfAbsent(marker.field(), field -> new CellSampler(worldLookup, field));
				}
				return function;
			}
		});
		assertEquals(template.cellMarkerCount(), countCellMarkers(template.router()), "Cell marker count changed before NoiseChunk mapping");

		NoiseGeneratorSettings settings = productionSettings(template.preset(), sampledRouter);
		RandomState randomState = RandomState.create(settings, template.noiseParameters(), seed);
		TrackingCache2d fallback = new TrackingCache2d();
		Map<CellSampler, DensityFunction> cacheChunks = new HashMap<>();
		NoiseRouter chunkRouter = randomState.router().mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof CellSampler sampler) {
					return cacheChunks.computeIfAbsent(
						sampler,
						key -> key.new CacheChunk(realChunk, fallback, target.chunkX(), target.chunkZ())
					);
				}
				return function;
			}
		});
		assertTrue(!cacheChunks.isEmpty(), "Seeded production router created no CellSampler.CacheChunk wrappers");
		setRandomStateRouter(randomState, chunkRouter);

		Aquifer.FluidStatus lava = new Aquifer.FluidStatus(template.preset().world().properties.lavaLevel, Blocks.LAVA.defaultBlockState());
		Aquifer.FluidStatus defaultFluid = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
		Aquifer.FluidPicker fluidPicker = (x, y, z) -> y < Math.min(template.preset().world().properties.lavaLevel, settings.seaLevel())
			? lava
			: defaultFluid;
		NoiseSettings noiseSettings = settings.noiseSettings();
		ExposedNoiseChunk noiseChunk = new ExposedNoiseChunk(
			16 / noiseSettings.getCellWidth(),
			randomState,
			startX,
			startZ,
			noiseSettings,
			DensityFunctions.BeardifierMarker.INSTANCE,
			settings,
			fluidPicker,
			Blender.empty()
		);
		assertEquals("CacheAllInCell", noiseChunk.finalDensityWrapper(), "Fixture did not bind final density to NoiseChunk's cell cache");
		return new Fixture(noiseChunk, settings, realChunk, fallback, cacheChunks.size());
	}

	private static NoiseGeneratorSettings productionSettings(Preset preset, NoiseRouter router) {
		WorldSettings.Properties properties = preset.world().properties;
		HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
		NoiseGeneratorSettings vanillaSettings = vanilla.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD)
			.value();
		return new NoiseGeneratorSettings(
			NoiseSettings.create(-properties.worldDepth, properties.worldDepth + properties.worldHeight, 1, 2),
			Blocks.STONE.defaultBlockState(),
			Blocks.WATER.defaultBlockState(),
			router,
			vanillaSettings.surfaceRule(),
			properties.spawnType.getParameterPoints(),
			properties.seaLevel,
			false,
			true,
			preset.caves().largeOreVeins,
			false
		);
	}

	private static long compareCompleteChunk(ChunkTarget target, Fixture expected, Fixture actual) {
		ChunkScan scan = scanCompleteChunk(target, expected, actual);
		if(scan.difference() != null) {
			fail(scan.difference().describe());
		}
		return scan.samples();
	}

	private static ChunkScan scanCompleteChunk(ChunkTarget target, Fixture expected, Fixture actual) {
		NoiseSettings expectedSettings = expected.settings().noiseSettings();
		NoiseSettings actualSettings = actual.settings().noiseSettings();
		assertEquals(expectedSettings.minY(), actualSettings.minY(), "NoiseChunk minY differs");
		assertEquals(expectedSettings.height(), actualSettings.height(), "NoiseChunk height differs");
		assertEquals(expectedSettings.getCellWidth(), actualSettings.getCellWidth(), "NoiseChunk cell width differs");
		assertEquals(expectedSettings.getCellHeight(), actualSettings.getCellHeight(), "NoiseChunk cell height differs");

		int cellWidth = expectedSettings.getCellWidth();
		int cellHeight = expectedSettings.getCellHeight();
		int cellCountXZ = 16 / cellWidth;
		int cellCountY = Math.floorDiv(expectedSettings.height(), cellHeight);
		int minCellY = Math.floorDiv(expectedSettings.minY(), cellHeight);
		int startX = target.chunkX() << 4;
		int startZ = target.chunkZ() << 4;
		long samples = 0L;
		boolean expectedStarted = false;
		boolean actualStarted = false;
		try {
			expected.chunk().initializeForFirstCellX();
			expectedStarted = true;
			actual.chunk().initializeForFirstCellX();
			actualStarted = true;
			for(int cellX = 0; cellX < cellCountXZ; cellX++) {
				expected.chunk().advanceCellX(cellX);
				actual.chunk().advanceCellX(cellX);
				for(int cellZ = 0; cellZ < cellCountXZ; cellZ++) {
					for(int cellY = cellCountY - 1; cellY >= 0; cellY--) {
						expected.chunk().selectCellYZ(cellY, cellZ);
						actual.chunk().selectCellYZ(cellY, cellZ);
						for(int inY = cellHeight - 1; inY >= 0; inY--) {
							int blockY = (minCellY + cellY) * cellHeight + inY;
							double yFraction = (double) inY / cellHeight;
							expected.chunk().updateForY(blockY, yFraction);
							actual.chunk().updateForY(blockY, yFraction);
							for(int inX = 0; inX < cellWidth; inX++) {
								int blockX = startX + cellX * cellWidth + inX;
								double xFraction = (double) inX / cellWidth;
								expected.chunk().updateForX(blockX, xFraction);
								actual.chunk().updateForX(blockX, xFraction);
								for(int inZ = 0; inZ < cellWidth; inZ++) {
									int blockZ = startZ + cellZ * cellWidth + inZ;
									double zFraction = (double) inZ / cellWidth;
									expected.chunk().updateForZ(blockZ, zFraction);
									actual.chunk().updateForZ(blockZ, zFraction);
									samples++;
									Difference difference = differenceAt(target, blockX, blockY, blockZ, expected, actual);
									if(difference != null) {
										return new ChunkScan(samples, difference);
									}
								}
							}
						}
					}
				}
				expected.chunk().swapSlices();
				actual.chunk().swapSlices();
			}
		} finally {
			if(expectedStarted) {
				expected.chunk().stopInterpolation();
			}
			if(actualStarted) {
				actual.chunk().stopInterpolation();
			}
		}
		assertEquals(16L * 16L * expectedSettings.height(), samples, "Fixture did not visit the complete chunk density volume");
		return new ChunkScan(samples, null);
	}

	private static Difference differenceAt(
		ChunkTarget target,
		int blockX,
		int blockY,
		int blockZ,
		Fixture expected,
		Fixture actual
	) {
		double expectedDensity = expected.chunk().finalDensity();
		double actualDensity = actual.chunk().finalDensity();
		long expectedBits = Double.doubleToRawLongBits(expectedDensity);
		long actualBits = Double.doubleToRawLongBits(actualDensity);
		BlockState expectedState = expected.chunk().materialState();
		BlockState actualState = actual.chunk().materialState();
		if(expectedState == null) {
			expectedState = expected.settings().defaultBlock();
		}
		if(actualState == null) {
			actualState = actual.settings().defaultBlock();
		}
		if(expectedBits != actualBits || !expectedState.equals(actualState)) {
			return new Difference(
				target,
				blockX,
				blockY,
				blockZ,
				expectedBits,
				actualBits,
				expectedDensity,
				actualDensity,
				expectedState,
				actualState
			);
		}
		return null;
	}

	private static List<ChunkTarget> productionTargets() {
		int seamChunkX = Math.floorDiv(ARTIFACT_CHUNK_X, 1 << TILE_FACTOR) * (1 << TILE_FACTOR);
		int seamChunkZ = Math.floorDiv(ARTIFACT_CHUNK_Z, 1 << TILE_FACTOR) * (1 << TILE_FACTOR);
		return List.of(
			new ChunkTarget("origin-center", 0, 0),
			new ChunkTarget("reported-artifact-center", ARTIFACT_CHUNK_X, ARTIFACT_CHUNK_Z),
			new ChunkTarget("x-seam-west", seamChunkX - 1, ARTIFACT_CHUNK_Z),
			new ChunkTarget("x-seam-east", seamChunkX, ARTIFACT_CHUNK_Z),
			new ChunkTarget("z-seam-north", ARTIFACT_CHUNK_X, seamChunkZ - 1),
			new ChunkTarget("z-seam-south", ARTIFACT_CHUNK_X, seamChunkZ)
		);
	}

	private static List<Integer> cacheExperimentSeeds() {
		return List.of(CORPUS_SEED, SEED, -828_453_099);
	}

	private static List<ChunkTarget> cacheExperimentTargets() {
		return List.of(
			new ChunkTarget("client-difference", CLIENT_CHUNK_X, CLIENT_CHUNK_Z),
			new ChunkTarget("client-z-seam-south", CLIENT_CHUNK_X, CLIENT_CHUNK_Z + 1),
			new ChunkTarget("client-x-seam-west", 503, CLIENT_CHUNK_Z),
			new ChunkTarget("client-x-seam-east", 504, CLIENT_CHUNK_Z),
			new ChunkTarget("client-next-x-seam-west", 511, CLIENT_CHUNK_Z),
			new ChunkTarget("client-next-x-seam-east", 512, CLIENT_CHUNK_Z),
			new ChunkTarget("client-tile-center", 507, 579),
			new ChunkTarget("origin-center", 0, 0),
			new ChunkTarget("reported-artifact-center", ARTIFACT_CHUNK_X, ARTIFACT_CHUNK_Z),
			new ChunkTarget("personal-corpus-1", 729, 159),
			new ChunkTarget("personal-corpus-2", 892, 1_116),
			new ChunkTarget("personal-corpus-3", 585, 159),
			new ChunkTarget("personal-corpus-4", 457, 567),
			new ChunkTarget("personal-corpus-5", 177, 159),
			new ChunkTarget("personal-corpus-6", 353, 159)
		);
	}

	private static int countCellMarkers(NoiseRouter router) {
		AtomicInteger count = new AtomicInteger();
		router.mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof CellSampler.Marker) {
					count.incrementAndGet();
				}
				return function;
			}
		});
		return count.get();
	}

	private static int countCacheOnce(DensityFunction density) {
		AtomicInteger count = new AtomicInteger();
		density.mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof DensityFunctions.Marker marker
					&& marker.type() == DensityFunctions.Marker.Type.CacheOnce) {
					count.incrementAndGet();
				}
				return function;
			}
		});
		return count.get();
	}

	private static NoiseRouter withCachedSlopedCheeseRangeInput(
		Preset preset,
		NoiseRouter router,
		HolderGetter<DensityFunction> densityFunctions
	) {
		DensityFunction slopedCheese = NoiseRouterData.getFunction(densityFunctions, NoiseRouterData.SLOPED_CHEESE);
		DensityFunction cachedSlopedCheese = DensityFunctions.cacheOnce(slopedCheese);
		AtomicInteger replacements = new AtomicInteger();
		DensityFunction finalDensity = router.finalDensity().mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof DensityFunctions.RangeChoice range
					&& Double.doubleToRawLongBits(range.minInclusive()) == Double.doubleToRawLongBits(-1_000_000.0D)
					&& Double.doubleToRawLongBits(range.maxExclusive())
						== Double.doubleToRawLongBits(preset.caves().cheeseCaveDepthOffset)) {
					replacements.incrementAndGet();
					DensityFunction entrances = preset.caves().entranceCaveProbability > 0.0F
						? DensityFunctions.min(
							cachedSlopedCheese,
							DensityFunctions.mul(
								DensityFunctions.constant(5.0D),
								DensityFunctions.interpolated(
									NoiseRouterData.getFunction(densityFunctions, NoiseRouterData.ENTRANCES)
								)
							)
						)
						: cachedSlopedCheese;
					return DensityFunctions.rangeChoice(
						cachedSlopedCheese,
						range.minInclusive(),
						range.maxExclusive(),
						entrances,
						range.whenOutOfRange()
					);
				}
				return function;
			}
		});
		assertEquals(1, replacements.get(), "Could not identify the production sloped-cheese cave range");
		return withFinalDensity(router, finalDensity);
	}

	private static NoiseRouter withFinalDensity(NoiseRouter router, DensityFunction finalDensity) {
		return new NoiseRouter(
			router.barrierNoise(),
			router.fluidLevelFloodednessNoise(),
			router.fluidLevelSpreadNoise(),
			router.lavaNoise(),
			router.temperature(),
			router.vegetation(),
			router.continents(),
			router.erosion(),
			router.depth(),
			router.ridges(),
			router.initialDensityWithoutJaggedness(),
			finalDensity,
			router.veinToggle(),
			router.veinRidged(),
			router.veinGap()
		);
	}

	private static HolderGetter<DensityFunction> productionDensityFunctions(Preset preset, HolderLookup.Provider vanilla) {
		MutableDensityLookup lookup = new MutableDensityLookup();
		BootstrapContext<DensityFunction> context = new BootstrapContext<>() {
			@Override
			public Holder.Reference<DensityFunction> register(
				ResourceKey<DensityFunction> key,
				DensityFunction value,
				Lifecycle lifecycle
			) {
				return lookup.bind(key, value);
			}

			@Override
			@SuppressWarnings("unchecked")
			public <S> HolderGetter<S> lookup(ResourceKey<? extends Registry<? extends S>> registryKey) {
				if(registryKey.equals(Registries.DENSITY_FUNCTION)) {
					return (HolderGetter<S>) lookup;
				}
				return vanilla.lookupOrThrow(registryKey);
			}

			@Override
			@SuppressWarnings("unchecked")
			public <S> Optional<HolderLookup.RegistryLookup<S>> registryLookup(
				ResourceKey<? extends Registry<? extends S>> registryKey
			) {
				if(registryKey.equals(Registries.DENSITY_FUNCTION)) {
					return Optional.of((HolderLookup.RegistryLookup<S>) lookup);
				}
				return vanilla.lookup(registryKey);
			}
		};
		NoiseRouterData.bootstrap(context);
		PresetNoiseRouterData.bootstrap(preset, context);
		lookup.assertFullyBound();
		return lookup;
	}

	private static void setRandomStateRouter(RandomState randomState, NoiseRouter router) throws ReflectiveOperationException {
		Field field = RandomState.class.getDeclaredField("router");
		field.setAccessible(true);
		field.set(randomState, router);
	}

	private static String raw(long bits) {
		return String.format(Locale.ROOT, "0x%016X", bits);
	}

	private record Templates(Template legacy, Template legacyV2) {
	}

	private record ExperimentTemplates(Template legacy, Template cached) {
	}

	private record PresetCorpus(String label, Preset preset) {
	}

	private record Template(
		Preset preset,
		NoiseRouter router,
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters,
		int cellMarkerCount,
		int cacheOnceCount
	) {
	}

	private record ChunkTarget(String label, int chunkX, int chunkZ) {
	}

	private record ChunkScan(long samples, Difference difference) {
	}

	private record Difference(
		ChunkTarget target,
		int blockX,
		int blockY,
		int blockZ,
		long expectedBits,
		long actualBits,
		double expectedDensity,
		double actualDensity,
		BlockState expectedState,
		BlockState actualState
	) {
		private String describe() {
			return "NoiseChunk mismatch target=" + this.target.label()
				+ " chunk=" + this.target.chunkX() + "," + this.target.chunkZ()
				+ " block=" + this.blockX + "," + this.blockY + "," + this.blockZ
				+ " quartResidue=" + Math.floorMod(this.blockX, 4) + "," + Math.floorMod(this.blockZ, 4)
				+ " density=" + raw(this.expectedBits) + "(" + this.expectedDensity + ")->"
				+ raw(this.actualBits) + "(" + this.actualDensity + ")"
				+ " state=" + this.expectedState + "->" + this.actualState;
		}
	}

	private record ExperimentDifference(int seed, Difference difference) {
		private String describe() {
			return "seed=" + this.seed + " " + this.difference.describe();
		}
	}

	private record Fixture(
		ExposedNoiseChunk chunk,
		NoiseGeneratorSettings settings,
		Tile.Chunk realChunk,
		TrackingCache2d fallback,
		int cacheChunkCount
	) {
	}

	private static final class ExposedNoiseChunk extends NoiseChunk {
		private final DensityFunction finalDensity;

		private ExposedNoiseChunk(
			int cellCountXZ,
			RandomState randomState,
			int minBlockX,
			int minBlockZ,
			NoiseSettings noiseSettings,
			DensityFunctions.BeardifierOrMarker beardifier,
			NoiseGeneratorSettings settings,
			Aquifer.FluidPicker fluidPicker,
			Blender blender
		) {
			super(cellCountXZ, randomState, minBlockX, minBlockZ, noiseSettings, beardifier, settings, fluidPicker, blender);
			NoiseRouter mappedRouter = randomState.router().mapAll(this::wrap);
			this.finalDensity = DensityFunctions.cacheAllInCell(
				DensityFunctions.add(mappedRouter.finalDensity(), DensityFunctions.BeardifierMarker.INSTANCE)
			).mapAll(this::wrap);
		}

		private double finalDensity() {
			return this.finalDensity.compute(this);
		}

		private BlockState materialState() {
			return this.getInterpolatedState();
		}

		private String finalDensityWrapper() {
			return this.finalDensity.getClass().getSimpleName();
		}
	}

	private static final class TrackingCache2d extends CellSampler.Cache2d {
		private long calls;
		private int xResidues;
		private int zResidues;

		@Override
		public Cell getAndUpdate(
			WorldLookup lookup,
			int blockX,
			int blockZ,
			boolean sampleClimate
		) {
			this.calls++;
			this.xResidues |= 1 << Math.floorMod(blockX, 4);
			this.zResidues |= 1 << Math.floorMod(blockZ, 4);
			return super.getAndUpdate(lookup, blockX, blockZ, sampleClimate);
		}

		private long calls() {
			return this.calls;
		}

		private int xResidues() {
			return this.xResidues;
		}

		private int zResidues() {
			return this.zResidues;
		}
	}

	private static final class MutableDensityLookup implements HolderLookup.RegistryLookup<DensityFunction> {
		private final Map<ResourceKey<DensityFunction>, MutableReference<DensityFunction>> values = new LinkedHashMap<>();

		private Holder.Reference<DensityFunction> bind(ResourceKey<DensityFunction> key, DensityFunction value) {
			MutableReference<DensityFunction> reference = this.values.computeIfAbsent(
				key,
				missing -> new MutableReference<>(this, missing)
			);
			reference.bind(value);
			return reference;
		}

		private void assertFullyBound() {
			this.values.forEach((key, reference) -> assertTrue(reference.isBound(), "Unbound production density holder " + key));
		}

		@Override
		public ResourceKey<? extends Registry<? extends DensityFunction>> key() {
			return Registries.DENSITY_FUNCTION;
		}

		@Override
		public Lifecycle registryLifecycle() {
			return Lifecycle.stable();
		}

		@Override
		public Optional<Holder.Reference<DensityFunction>> get(ResourceKey<DensityFunction> key) {
			return Optional.of(this.values.computeIfAbsent(key, missing -> new MutableReference<>(this, missing)));
		}

		@Override
		public Optional<HolderSet.Named<DensityFunction>> get(TagKey<DensityFunction> tagKey) {
			return Optional.empty();
		}

		@Override
		public Stream<Holder.Reference<DensityFunction>> listElements() {
			return this.values.values().stream().map(reference -> reference);
		}

		@Override
		public Stream<HolderSet.Named<DensityFunction>> listTags() {
			return Stream.empty();
		}
	}

	private static final class MutableReference<T> extends Holder.Reference<T> {
		private MutableReference(HolderOwner<T> owner, ResourceKey<T> key) {
			super(Type.STAND_ALONE, owner, key, null);
		}

		private void bind(T value) {
			this.bindValue(value);
		}
	}

	private static final class RtfRouterAccess extends PresetNoiseRouterData {
		private static NoiseRouter create(
			Preset preset,
			HolderGetter<DensityFunction> densityFunctions,
			HolderGetter<NormalNoise.NoiseParameters> noiseParameters
		) {
			return overworld(preset, densityFunctions, noiseParameters, null);
		}
	}
}
