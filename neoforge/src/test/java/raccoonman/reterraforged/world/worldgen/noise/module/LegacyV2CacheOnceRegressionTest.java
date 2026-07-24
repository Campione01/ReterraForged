package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
import raccoonman.reterraforged.world.worldgen.cell.heightmap.WorldLookup;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;

class LegacyV2CacheOnceRegressionTest {
	private static final int SEED = -1_448_906_188;
	private static final int TILE_FACTOR = 3;
	private static final int BATCH_COUNT = 6;
	private static final int CHUNK_X = 505;
	private static final int CHUNK_Z = 583;

	@BeforeAll
	static void bootstrapMinecraft() {
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void currentLegacyV2RoutingHasNoCellBackedCacheOnceSlopedCheeseEdges() {
		Preset preset = legacyV2Preset();
		HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
		HolderGetter<DensityFunction> densityFunctions = productionDensityFunctions(preset, vanilla);
		NoiseRouter current = RtfRouterAccess.create(
			preset,
			densityFunctions,
			vanilla.lookupOrThrow(Registries.NOISE)
		);
		NoiseRouter historical = withHistoricalCachedSlopedCheese(preset, current, densityFunctions);

		assertEquals(
			2,
			countCellBackedCacheOnceEdges(historical.finalDensity()),
			"Historical c7/933 routing must reconstruct both vulnerable sloped-cheese edges"
		);
		assertEquals(
			0,
			countCellBackedCacheOnceEdges(current.finalDensity()),
			"Current LEGACY_V2 routing reintroduced cacheOnce around a CellSampler-backed sloped-cheese edge"
		);
	}

	@Test
	void historicalDistinctSamplerGraphDivergesAndReusesWithinNoiseCell() throws Exception {
		Preset preset = legacyV2Preset();
		HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
		HolderGetter<DensityFunction> densityFunctions = productionDensityFunctions(preset, vanilla);
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters = vanilla.lookupOrThrow(Registries.NOISE);
		NoiseRouter current = RtfRouterAccess.create(preset, densityFunctions, noiseParameters);
		NoiseRouter historical = withHistoricalCachedSlopedCheese(preset, current, densityFunctions);

		try(GeneratorContext context = GeneratorContext.makeCached(
			preset,
			RtfSemanticParityTest.noiseLookup(preset),
			SEED,
			TILE_FACTOR,
			BATCH_COUNT,
			false
		)) {
			Tile tile = context.cache.provideAtChunk(CHUNK_X, CHUNK_Z);
			Tile.Chunk realChunk = tile.getChunkReader(CHUNK_X, CHUNK_Z);
			Fixture plain = fixture(preset, current, noiseParameters, context, realChunk);
			Fixture cached = fixture(preset, historical, noiseParameters, context, realChunk);

			assertTrue(
				plain.supplierIdentityCount() > 1,
				"Production-like marker mapping did not create distinct CellSampler supplier identities"
			);
			assertEquals(
				plain.supplierIdentityCount(),
				cached.supplierIdentityCount(),
				"Historical cache markers changed the CellSampler marker surface"
			);
			assertEquals(
				plain.cacheChunkCount(),
				cached.cacheChunkCount(),
				"Historical cache markers changed the CellSampler.CacheChunk surface"
			);
			assertEquals(
				0,
				plain.cellBackedCacheOnceCount(),
				"Plain A75 semantics unexpectedly contained a CellSampler-backed CacheOnce"
			);
			assertEquals(
				2,
				cached.cellBackedCacheOnceCount(),
				"Historical graph did not produce two CellSampler-backed CacheOnce wrappers"
			);
			assertTrue(
				cached.cacheOnceSupplierIdentityCount() > 1,
				"Historical CacheOnce edges reused one canonical CellSampler supplier identity"
			);

			Scan scan = scanChunk(plain, cached);
			assertNotNull(
				scan.firstDifference(),
				"Historical cacheOnce graph unexpectedly matched plain A75 semantics at every block coordinate"
			);
			assertNotNull(
				scan.reuse(),
				"Historical cacheOnce graph diverged without exhibiting repeated output inside one 4x8x4 noise cell"
			);
			assertEquals(8_081, scan.firstDifference().blockX(), "Unexpected first mismatch X");
			assertEquals(79, scan.firstDifference().blockY(), "Unexpected first mismatch Y");
			assertEquals(9_331, scan.firstDifference().blockZ(), "Unexpected first mismatch Z");
			assertEquals(
				0xBFDD_42C2_DE3A_CD7AL,
				scan.firstDifference().expectedBits(),
				"Plain A75 density changed at the proven mismatch"
			);
			assertEquals(
				0xBFDD_5555_5555_5555L,
				scan.firstDifference().actualBits(),
				"Historical cacheOnce density changed at the proven mismatch"
			);
			assertEquals(
				scan.reuse().first().actualBits(),
				scan.reuse().second().actualBits(),
				"Historical output was not reused"
			);
			assertTrue(
				scan.reuse().first().expectedBits() != scan.reuse().second().expectedBits(),
				"Plain A75 output did not vary across the reuse pair"
			);
			System.out.println(
				"RTF_LEGACY_V2_CACHE_ONCE_REGRESSION first=" + scan.firstDifference().describe()
					+ ", reuse=" + scan.reuse().describe()
					+ ", suppliers=" + plain.supplierIdentityCount() + "/" + cached.supplierIdentityCount()
					+ ", cacheChunks=" + plain.cacheChunkCount() + "/" + cached.cacheChunkCount()
					+ ", vulnerableCacheOnce=" + cached.cellBackedCacheOnceCount()
					+ ", vulnerableSuppliers=" + cached.cacheOnceSupplierIdentityCount()
			);
		}
	}

	@Test
	void productionRepairRestoresPlainRawBitsAcrossEntireChunk() throws Exception {
		Preset preset = legacyV2Preset();
		HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
		HolderGetter<DensityFunction> densityFunctions = productionDensityFunctions(preset, vanilla);
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters = vanilla.lookupOrThrow(Registries.NOISE);
		NoiseRouter current = RtfRouterAccess.create(preset, densityFunctions, noiseParameters);
		NoiseRouter historical = withHistoricalCachedSlopedCheese(preset, current, densityFunctions);

		assertEquals(
			2,
			countCellBackedCacheOnceEdges(historical.finalDensity()),
			"Repair fixture no longer contains both persisted vulnerable edges"
		);
		try(GeneratorContext context = GeneratorContext.makeCached(
			preset,
			RtfSemanticParityTest.noiseLookup(preset),
			SEED,
			TILE_FACTOR,
			BATCH_COUNT,
			false
		)) {
			ProductionRepair plain = applyProductionRepair(
				preset,
				current,
				noiseParameters,
				context
			);
			ProductionRepair repaired = applyProductionRepair(
				preset,
				historical,
				noiseParameters,
				context
			);

			assertEquals(0, plain.repairedMarkers(), "Plain A75 graph unexpectedly required repair");
			assertEquals(2, repaired.repairedMarkers(), "Production guard did not repair both stale edges");
			assertTrue(plain.cellSamplers() > 1, "Plain graph did not create distinct CellSampler nodes");
			assertEquals(
				plain.cellSamplers(),
				plain.supplierIdentities(),
				"Plain production mapping reused a CellSampler supplier identity"
			);
			assertEquals(
				repaired.cellSamplers(),
				repaired.supplierIdentities(),
				"Repaired production mapping reused a CellSampler supplier identity"
			);
			assertEquals(
				plain.cellSamplers(),
				repaired.cellSamplers(),
				"Repair changed the mapped CellSampler surface"
			);
			assertEquals(
				0,
				runtimeCacheOnceSurface(repaired.router().finalDensity()).edges(),
				"Repaired graph retained a CellSampler-backed CacheOnce edge"
			);

			Tile tile = context.cache.provideAtChunk(CHUNK_X, CHUNK_Z);
			Tile.Chunk realChunk = tile.getChunkReader(CHUNK_X, CHUNK_Z);
			Fixture plainFixture = fixture(
				preset,
				plain.router(),
				noiseParameters,
				context,
				realChunk
			);
			Fixture repairedFixture = fixture(
				preset,
				repaired.router(),
				noiseParameters,
				context,
				realChunk
			);
			ExactScan scan = compareChunkExactly(plainFixture, repairedFixture);
			int expectedSamples = 16 * 16 * plainFixture.settings().noiseSettings().height();

			assertEquals(expectedSamples, scan.samples(), "Exact comparison did not cover the full chunk");
			assertEquals(
				0,
				scan.rawBitDifferences(),
				"Repaired stale graph differs from plain A75 semantics; first="
					+ (scan.firstDifference() == null ? "none" : scan.firstDifference().describe())
			);
			System.out.println(
				"RTF_LEGACY_V2_CACHE_ONCE_REPAIR samples=" + scan.samples()
					+ ", rawBitDifferences=" + scan.rawBitDifferences()
					+ ", repairs=" + repaired.repairedMarkers()
					+ ", vulnerableCacheOnce="
					+ runtimeCacheOnceSurface(repaired.router().finalDensity()).edges()
					+ ", suppliers=" + plain.supplierIdentities() + "/" + repaired.supplierIdentities()
			);
		}
	}

	private static Preset legacyV2Preset() {
		Preset preset = Presets.makeRTFDefault();
		preset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY_V2;
		preset.caves().densityAlgorithm = DensityAlgorithm.LEGACY_V2;
		preset.caves().compatibilityMode = CompatibilityMode.RTF;
		return preset;
	}

	private static Fixture fixture(
		Preset preset,
		NoiseRouter router,
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters,
		GeneratorContext context,
		Tile.Chunk realChunk
	) throws ReflectiveOperationException {
		List<CellSampler> samplers = new ArrayList<>();
		NoiseRouter sampledRouter = router.mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof CellSampler.Marker marker) {
					CellSampler sampler = new CellSampler(() -> context.lookup, marker.field());
					samplers.add(sampler);
					return sampler;
				}
				return function;
			}
		});
		Set<Supplier<WorldLookup>> suppliers = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
		for(CellSampler sampler : samplers) {
			assertTrue(
				suppliers.add(sampler.deferredLookup()),
				"Marker mapping reused a CellSampler supplier identity"
			);
		}
		RuntimeCacheOnceSurface cacheOnceSurface = runtimeCacheOnceSurface(
			sampledRouter.finalDensity()
		);

		NoiseGeneratorSettings settings = productionSettings(preset, sampledRouter);
		RandomState randomState = RandomState.create(settings, noiseParameters, SEED);
		AtomicInteger cacheChunks = new AtomicInteger();
		NoiseRouter chunkRouter = randomState.router().mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof CellSampler sampler) {
					cacheChunks.incrementAndGet();
					return sampler.new CacheChunk(realChunk, new CellSampler.Cache2d(), CHUNK_X, CHUNK_Z);
				}
				return function;
			}
		});
		setRandomStateRouter(randomState, chunkRouter);

		Aquifer.FluidStatus lava = new Aquifer.FluidStatus(
			preset.world().properties.lavaLevel,
			Blocks.LAVA.defaultBlockState()
		);
		Aquifer.FluidStatus defaultFluid = new Aquifer.FluidStatus(
			settings.seaLevel(),
			settings.defaultFluid()
		);
		Aquifer.FluidPicker fluidPicker = (x, y, z) ->
			y < Math.min(preset.world().properties.lavaLevel, settings.seaLevel()) ? lava : defaultFluid;
		NoiseSettings noiseSettings = settings.noiseSettings();
		return new Fixture(
			new ExposedNoiseChunk(
				16 / noiseSettings.getCellWidth(),
				randomState,
				CHUNK_X << 4,
				CHUNK_Z << 4,
				noiseSettings,
				DensityFunctions.BeardifierMarker.INSTANCE,
				settings,
				fluidPicker,
				Blender.empty()
			),
			settings,
			suppliers.size(),
			cacheChunks.get(),
			cacheOnceSurface.edges(),
			cacheOnceSurface.supplierIdentities()
		);
	}

	private static Scan scanChunk(Fixture plain, Fixture historical) {
		NoiseSettings settings = plain.settings().noiseSettings();
		assertEquals(settings, historical.settings().noiseSettings(), "Noise settings differ between fixtures");
		int cellWidth = settings.getCellWidth();
		int cellHeight = settings.getCellHeight();
		assertEquals(4, cellWidth, "Regression requires the production 4-block noise-cell width");
		assertEquals(8, cellHeight, "Regression requires the production 8-block noise-cell height");
		int cellCountXZ = 16 / cellWidth;
		int cellCountY = Math.floorDiv(settings.height(), cellHeight);
		int minCellY = Math.floorDiv(settings.minY(), cellHeight);
		int startX = CHUNK_X << 4;
		int startZ = CHUNK_Z << 4;
		Difference firstDifference = null;
		Reuse reuse = null;
		boolean plainStarted = false;
		boolean historicalStarted = false;
		try {
			plain.chunk().initializeForFirstCellX();
			plainStarted = true;
			historical.chunk().initializeForFirstCellX();
			historicalStarted = true;
			for(int cellX = 0; cellX < cellCountXZ; cellX++) {
				plain.chunk().advanceCellX(cellX);
				historical.chunk().advanceCellX(cellX);
				for(int cellZ = 0; cellZ < cellCountXZ; cellZ++) {
					for(int cellY = cellCountY - 1; cellY >= 0; cellY--) {
						Map<Long, Sample> historicalValues = new HashMap<>();
						plain.chunk().selectCellYZ(cellY, cellZ);
						historical.chunk().selectCellYZ(cellY, cellZ);
						for(int inY = cellHeight - 1; inY >= 0; inY--) {
							int blockY = (minCellY + cellY) * cellHeight + inY;
							double yFraction = (double)inY / cellHeight;
							plain.chunk().updateForY(blockY, yFraction);
							historical.chunk().updateForY(blockY, yFraction);
							for(int inX = 0; inX < cellWidth; inX++) {
								int blockX = startX + cellX * cellWidth + inX;
								double xFraction = (double)inX / cellWidth;
								plain.chunk().updateForX(blockX, xFraction);
								historical.chunk().updateForX(blockX, xFraction);
								for(int inZ = 0; inZ < cellWidth; inZ++) {
									int blockZ = startZ + cellZ * cellWidth + inZ;
									double zFraction = (double)inZ / cellWidth;
									plain.chunk().updateForZ(blockZ, zFraction);
									historical.chunk().updateForZ(blockZ, zFraction);
									double expected = plain.chunk().finalDensity();
									double actual = historical.chunk().finalDensity();
									long expectedBits = Double.doubleToRawLongBits(expected);
									long actualBits = Double.doubleToRawLongBits(actual);
									Sample sample = new Sample(
										blockX,
										blockY,
										blockZ,
										expectedBits,
										actualBits,
										expected,
										actual
									);
									if(firstDifference == null && expectedBits != actualBits) {
										firstDifference = Difference.from(sample);
									}
									Sample previous = historicalValues.putIfAbsent(actualBits, sample);
									if(
										reuse == null
											&& previous != null
											&& previous.expectedBits() != expectedBits
									) {
										reuse = new Reuse(previous, sample);
									}
								}
							}
						}
						if(firstDifference != null && reuse != null) {
							return new Scan(firstDifference, reuse);
						}
					}
				}
				plain.chunk().swapSlices();
				historical.chunk().swapSlices();
			}
			return new Scan(firstDifference, reuse);
		} finally {
			if(plainStarted) {
				plain.chunk().stopInterpolation();
			}
			if(historicalStarted) {
				historical.chunk().stopInterpolation();
			}
		}
	}

	private static ExactScan compareChunkExactly(Fixture plain, Fixture repaired) {
		NoiseSettings settings = plain.settings().noiseSettings();
		assertEquals(settings, repaired.settings().noiseSettings(), "Noise settings differ between fixtures");
		int cellWidth = settings.getCellWidth();
		int cellHeight = settings.getCellHeight();
		assertEquals(4, cellWidth, "Regression requires the production 4-block noise-cell width");
		assertEquals(8, cellHeight, "Regression requires the production 8-block noise-cell height");
		int cellCountXZ = 16 / cellWidth;
		int cellCountY = Math.floorDiv(settings.height(), cellHeight);
		int minCellY = Math.floorDiv(settings.minY(), cellHeight);
		int startX = CHUNK_X << 4;
		int startZ = CHUNK_Z << 4;
		int samples = 0;
		int rawBitDifferences = 0;
		Difference firstDifference = null;
		boolean plainStarted = false;
		boolean repairedStarted = false;
		try {
			plain.chunk().initializeForFirstCellX();
			plainStarted = true;
			repaired.chunk().initializeForFirstCellX();
			repairedStarted = true;
			for(int cellX = 0; cellX < cellCountXZ; cellX++) {
				plain.chunk().advanceCellX(cellX);
				repaired.chunk().advanceCellX(cellX);
				for(int cellZ = 0; cellZ < cellCountXZ; cellZ++) {
					for(int cellY = cellCountY - 1; cellY >= 0; cellY--) {
						plain.chunk().selectCellYZ(cellY, cellZ);
						repaired.chunk().selectCellYZ(cellY, cellZ);
						for(int inY = cellHeight - 1; inY >= 0; inY--) {
							int blockY = (minCellY + cellY) * cellHeight + inY;
							double yFraction = (double)inY / cellHeight;
							plain.chunk().updateForY(blockY, yFraction);
							repaired.chunk().updateForY(blockY, yFraction);
							for(int inX = 0; inX < cellWidth; inX++) {
								int blockX = startX + cellX * cellWidth + inX;
								double xFraction = (double)inX / cellWidth;
								plain.chunk().updateForX(blockX, xFraction);
								repaired.chunk().updateForX(blockX, xFraction);
								for(int inZ = 0; inZ < cellWidth; inZ++) {
									int blockZ = startZ + cellZ * cellWidth + inZ;
									double zFraction = (double)inZ / cellWidth;
									plain.chunk().updateForZ(blockZ, zFraction);
									repaired.chunk().updateForZ(blockZ, zFraction);
									double expected = plain.chunk().finalDensity();
									double actual = repaired.chunk().finalDensity();
									long expectedBits = Double.doubleToRawLongBits(expected);
									long actualBits = Double.doubleToRawLongBits(actual);
									samples++;
									if(expectedBits != actualBits) {
										rawBitDifferences++;
										if(firstDifference == null) {
											firstDifference = Difference.from(new Sample(
												blockX,
												blockY,
												blockZ,
												expectedBits,
												actualBits,
												expected,
												actual
											));
										}
									}
								}
							}
						}
					}
				}
				plain.chunk().swapSlices();
				repaired.chunk().swapSlices();
			}
			return new ExactScan(samples, rawBitDifferences, firstDifference);
		} finally {
			if(plainStarted) {
				plain.chunk().stopInterpolation();
			}
			if(repairedStarted) {
				repaired.chunk().stopInterpolation();
			}
		}
	}

	private static NoiseRouter withHistoricalCachedSlopedCheese(
		Preset preset,
		NoiseRouter router,
		HolderGetter<DensityFunction> densityFunctions
	) {
		DensityFunction slopedCheese = NoiseRouterData.getFunction(
			densityFunctions,
			NoiseRouterData.SLOPED_CHEESE
		);
		DensityFunction cachedSlopedCheese = DensityFunctions.cacheOnce(slopedCheese);
		AtomicInteger replacements = new AtomicInteger();
		DensityFunction finalDensity = router.finalDensity().mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof DensityFunctions.RangeChoice range
					&& Double.doubleToRawLongBits(range.minInclusive())
						== Double.doubleToRawLongBits(-1_000_000.0D)
					&& Double.doubleToRawLongBits(range.maxExclusive())
						== Double.doubleToRawLongBits(preset.caves().cheeseCaveDepthOffset)) {
					replacements.incrementAndGet();
					DensityFunction entrances = preset.caves().entranceCaveProbability > 0.0F
						? DensityFunctions.min(
							cachedSlopedCheese,
							DensityFunctions.mul(
								DensityFunctions.constant(5.0D),
								DensityFunctions.interpolated(
									NoiseRouterData.getFunction(
										densityFunctions,
										NoiseRouterData.ENTRANCES
									)
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
		assertEquals(1, replacements.get(), "Could not locate the production sloped-cheese range");
		return withFinalDensity(router, finalDensity);
	}

	private static ProductionRepair applyProductionRepair(
		Preset preset,
		NoiseRouter router,
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters,
		GeneratorContext context
	) throws ReflectiveOperationException {
		Class<?> mixinClass = Class.forName("raccoonman.reterraforged.mixin.MixinRandomState");
		var constructor = mixinClass.getDeclaredConstructor();
		constructor.setAccessible(true);
		Object mixin = constructor.newInstance();
		var redirect = mixinClass.getDeclaredMethod(
			"RandomState",
			NoiseRouter.class,
			DensityFunction.Visitor.class,
			NoiseGeneratorSettings.class,
			HolderGetter.class,
			long.class
		);
		redirect.setAccessible(true);
		NoiseRouter mapped = (NoiseRouter)redirect.invoke(
			mixin,
			router,
			new DensityFunction.Visitor() {
				@Override
				public DensityFunction apply(DensityFunction function) {
					return function;
				}

				@Override
				public DensityFunction.NoiseHolder visitNoise(
					DensityFunction.NoiseHolder noiseHolder
				) {
					return noiseHolder;
				}
			},
			productionSettings(preset, router),
			noiseParameters,
			(long)SEED
		);
		Field contextField = mixinClass.getDeclaredField("generatorContext");
		contextField.setAccessible(true);
		contextField.set(mixin, context);
		Field repairedField = mixinClass.getDeclaredField(
			"reterraforged$repairedCellSamplerCacheOnceMarkers"
		);
		repairedField.setAccessible(true);
		AtomicInteger cellSamplers = new AtomicInteger();
		Set<Supplier<WorldLookup>> suppliers = java.util.Collections.newSetFromMap(
			new IdentityHashMap<>()
		);
		mapped.finalDensity().mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof CellSampler sampler) {
					cellSamplers.incrementAndGet();
					suppliers.add(sampler.deferredLookup());
				}
				return function;
			}
		});
		return new ProductionRepair(
			mapped,
			repairedField.getInt(mixin),
			cellSamplers.get(),
			suppliers.size()
		);
	}

	private static int countCellBackedCacheOnceEdges(DensityFunction density) {
		AtomicInteger count = new AtomicInteger();
		density.mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof DensityFunctions.Marker marker
					&& marker.type() == DensityFunctions.Marker.Type.CacheOnce
					&& containsCellMarker(marker.wrapped())) {
					count.incrementAndGet();
				}
				return function;
			}
		});
		return count.get();
	}

	private static boolean containsCellMarker(DensityFunction density) {
		AtomicBoolean found = new AtomicBoolean();
		density.mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof CellSampler.Marker) {
					found.set(true);
				}
				return function;
			}
		});
		return found.get();
	}

	private static RuntimeCacheOnceSurface runtimeCacheOnceSurface(DensityFunction density) {
		AtomicInteger edges = new AtomicInteger();
		Set<Supplier<WorldLookup>> suppliers = java.util.Collections.newSetFromMap(
			new IdentityHashMap<>()
		);
		density.mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof DensityFunctions.Marker marker
					&& marker.type() == DensityFunctions.Marker.Type.CacheOnce) {
					AtomicBoolean containsCellSampler = new AtomicBoolean();
					marker.wrapped().mapAll(new DensityFunction.Visitor() {
						@Override
						public DensityFunction apply(DensityFunction nested) {
							if(nested instanceof CellSampler sampler) {
								containsCellSampler.set(true);
								suppliers.add(sampler.deferredLookup());
							}
							return nested;
						}
					});
					if(containsCellSampler.get()) {
						edges.incrementAndGet();
					}
				}
				return function;
			}
		});
		return new RuntimeCacheOnceSurface(edges.get(), suppliers.size());
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

	private static NoiseGeneratorSettings productionSettings(Preset preset, NoiseRouter router) {
		WorldSettings.Properties properties = preset.world().properties;
		NoiseGeneratorSettings vanillaSettings = VanillaRegistries.createLookup()
			.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD)
			.value();
		return new NoiseGeneratorSettings(
			NoiseSettings.create(
				-properties.worldDepth,
				properties.worldDepth + properties.worldHeight,
				1,
				2
			),
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

	private static HolderGetter<DensityFunction> productionDensityFunctions(
		Preset preset,
		HolderLookup.Provider vanilla
	) {
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
			public <S> HolderGetter<S> lookup(
				ResourceKey<? extends Registry<? extends S>> registryKey
			) {
				if(registryKey.equals(Registries.DENSITY_FUNCTION)) {
					return (HolderGetter<S>)lookup;
				}
				return vanilla.lookupOrThrow(registryKey);
			}

			@Override
			@SuppressWarnings("unchecked")
			public <S> Optional<HolderLookup.RegistryLookup<S>> registryLookup(
				ResourceKey<? extends Registry<? extends S>> registryKey
			) {
				if(registryKey.equals(Registries.DENSITY_FUNCTION)) {
					return Optional.of((HolderLookup.RegistryLookup<S>)lookup);
				}
				return vanilla.lookup(registryKey);
			}
		};
		NoiseRouterData.bootstrap(context);
		PresetNoiseRouterData.bootstrap(preset, context);
		lookup.assertFullyBound();
		return lookup;
	}

	private static void setRandomStateRouter(RandomState randomState, NoiseRouter router)
		throws ReflectiveOperationException {
		Field field = RandomState.class.getDeclaredField("router");
		field.setAccessible(true);
		field.set(randomState, router);
	}

	private static String raw(long bits) {
		return String.format(Locale.ROOT, "0x%016X", bits);
	}

	private record Fixture(
		ExposedNoiseChunk chunk,
		NoiseGeneratorSettings settings,
		int supplierIdentityCount,
		int cacheChunkCount,
		int cellBackedCacheOnceCount,
		int cacheOnceSupplierIdentityCount
	) {
	}

	private record RuntimeCacheOnceSurface(int edges, int supplierIdentities) {
	}

	private record ProductionRepair(
		NoiseRouter router,
		int repairedMarkers,
		int cellSamplers,
		int supplierIdentities
	) {
	}

	private record Scan(Difference firstDifference, Reuse reuse) {
	}

	private record ExactScan(int samples, int rawBitDifferences, Difference firstDifference) {
	}

	private record Sample(
		int blockX,
		int blockY,
		int blockZ,
		long expectedBits,
		long actualBits,
		double expected,
		double actual
	) {
	}

	private record Difference(
		int blockX,
		int blockY,
		int blockZ,
		long expectedBits,
		long actualBits,
		double expected,
		double actual
	) {
		private static Difference from(Sample sample) {
			return new Difference(
				sample.blockX(),
				sample.blockY(),
				sample.blockZ(),
				sample.expectedBits(),
				sample.actualBits(),
				sample.expected(),
				sample.actual()
			);
		}

		private String describe() {
			return "block=" + this.blockX + "," + this.blockY + "," + this.blockZ
				+ " cell=" + Math.floorDiv(this.blockX, 4) + ","
				+ Math.floorDiv(this.blockY, 8) + ","
				+ Math.floorDiv(this.blockZ, 4)
				+ " density=" + raw(this.expectedBits) + "(" + this.expected + ")->"
				+ raw(this.actualBits) + "(" + this.actual + ")";
		}
	}

	private record Reuse(Sample first, Sample second) {
		private String describe() {
			return "historical=" + raw(this.first.actualBits())
				+ " at=" + coordinates(this.first)
				+ "/" + coordinates(this.second)
				+ " plain=" + raw(this.first.expectedBits())
				+ "/" + raw(this.second.expectedBits());
		}

		private static String coordinates(Sample sample) {
			return sample.blockX() + "," + sample.blockY() + "," + sample.blockZ();
		}
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
			super(
				cellCountXZ,
				randomState,
				minBlockX,
				minBlockZ,
				noiseSettings,
				beardifier,
				settings,
				fluidPicker,
				blender
			);
			NoiseRouter mappedRouter = randomState.router().mapAll(this::wrap);
			this.finalDensity = DensityFunctions.cacheAllInCell(
				DensityFunctions.add(
					mappedRouter.finalDensity(),
					DensityFunctions.BeardifierMarker.INSTANCE
				)
			).mapAll(this::wrap);
		}

		private double finalDensity() {
			return this.finalDensity.compute(this);
		}
	}

	private static final class MutableDensityLookup
		implements HolderLookup.RegistryLookup<DensityFunction> {
		private final Map<ResourceKey<DensityFunction>, MutableReference<DensityFunction>> values =
			new LinkedHashMap<>();

		private Holder.Reference<DensityFunction> bind(
			ResourceKey<DensityFunction> key,
			DensityFunction value
		) {
			MutableReference<DensityFunction> reference = this.values.computeIfAbsent(
				key,
				missing -> new MutableReference<>(this, missing)
			);
			reference.bind(value);
			return reference;
		}

		private void assertFullyBound() {
			this.values.forEach(
				(key, reference) ->
					assertTrue(reference.isBound(), "Unbound production density holder " + key)
			);
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
			return Optional.of(
				this.values.computeIfAbsent(key, missing -> new MutableReference<>(this, missing))
			);
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
