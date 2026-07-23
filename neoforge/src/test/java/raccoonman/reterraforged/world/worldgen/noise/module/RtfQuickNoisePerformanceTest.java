package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;

class RtfQuickNoisePerformanceTest {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String BATCH_ROOTS_PROPERTY = "reterraforged.quickNoise.batchRoots";
	private static final String BATCH_ROOT_LIMIT_PROPERTY = "reterraforged.quickNoise.batchRootLimit";
	private static final String PROFILE_PROPERTY = "reterraforged.quickNoise.profile";
	private static final String VERIFY_PROPERTY = "reterraforged.quickNoise.verifyLegacy";
	private static final String PROMOTION_TILES_PROPERTY = "reterraforged.quickNoise.batchPromotionTiles";
	private static final int MEASURED_TILES = 8;
	private static final int SHORT_WORKLOAD_TILES = 4;
	private static final List<Integer> ROOT_LIMITS = List.of(0, 4, 8, 16, 32, 64);

	@Test
	void measuresColdAndShortProductionWorkloads() throws Exception {
		String externalPreset = System.getenv("RTF_PARITY_PRESET");
		Assumptions.assumeTrue(externalPreset != null && !externalPreset.isBlank());
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		String previousBatchRoots = System.getProperty(BATCH_ROOTS_PROPERTY);
		String previousBatchRootLimit = System.getProperty(BATCH_ROOT_LIMIT_PROPERTY);
		String previousProfile = System.getProperty(PROFILE_PROPERTY);
		String previousVerify = System.getProperty(VERIFY_PROPERTY);
		String previousPromotionTiles = System.getProperty(PROMOTION_TILES_PROPERTY);
		System.clearProperty(PROFILE_PROPERTY);
		System.clearProperty(VERIFY_PROPERTY);
		List<ColdVariantRun> runs = new ArrayList<>();
		try {
			int[] order = {0, 32, 32, 0, 32, 0, 0, 32};
			int independentRepetition = 0;
			int batchedRepetition = 0;
			for(int rootLimit : order) {
				int repetition = rootLimit == 0 ? ++independentRepetition : ++batchedRepetition;
				runs.add(measureCold(Path.of(externalPreset), rootLimit, repetition));
			}
		} finally {
			restore(BATCH_ROOTS_PROPERTY, previousBatchRoots);
			restore(BATCH_ROOT_LIMIT_PROPERTY, previousBatchRootLimit);
			restore(PROFILE_PROPERTY, previousProfile);
			restore(VERIFY_PROPERTY, previousVerify);
			restore(PROMOTION_TILES_PROPERTY, previousPromotionTiles);
		}
		double independentFirst = median(runs.stream().filter(run -> run.rootLimit == 0).map(ColdVariantRun::firstTileMs).toList());
		double batchedFirst = median(runs.stream().filter(run -> run.rootLimit == 32).map(ColdVariantRun::firstTileMs).toList());
		double independentTotal = median(runs.stream().filter(run -> run.rootLimit == 0).map(ColdVariantRun::totalMs).toList());
		double batchedTotal = median(runs.stream().filter(run -> run.rootLimit == 32).map(ColdVariantRun::totalMs).toList());
		ColdPerformanceReport report = new ColdPerformanceReport(
			"RTF 2D cold context plus four production Tiles; filters and root promotion included; tile close excluded",
			SHORT_WORKLOAD_TILES,
			runs,
			independentFirst,
			batchedFirst,
			(independentFirst / batchedFirst - 1.0D) * 100.0D,
			independentTotal,
			batchedTotal,
			(independentTotal / batchedTotal - 1.0D) * 100.0D
		);
		Path reportPath = RtfSemanticParityTest.outputRoot().resolve("production-root-batch-cold-performance.json");
		Files.createDirectories(reportPath.getParent());
		try(var writer = Files.newBufferedWriter(reportPath)) {
			GSON.toJson(report, writer);
		}
		System.out.println("RTF_PRODUCTION_ROOT_BATCH_COLD_PERFORMANCE " + reportPath.toAbsolutePath());
		assertTrue(runs.stream().filter(run -> run.rootLimit == 32).allMatch(run -> run.optimization.groups() > 0));
	}

	@Test
	void measuresPromotedRootsAgainstIndependentPrograms() throws Exception {
		String externalPreset = System.getenv("RTF_PARITY_PRESET");
		Assumptions.assumeTrue(externalPreset != null && !externalPreset.isBlank());
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		String previousBatchRoots = System.getProperty(BATCH_ROOTS_PROPERTY);
		String previousBatchRootLimit = System.getProperty(BATCH_ROOT_LIMIT_PROPERTY);
		String previousProfile = System.getProperty(PROFILE_PROPERTY);
		String previousVerify = System.getProperty(VERIFY_PROPERTY);
		String previousPromotionTiles = System.getProperty(PROMOTION_TILES_PROPERTY);
		System.clearProperty(PROFILE_PROPERTY);
		System.clearProperty(VERIFY_PROPERTY);
		List<VariantRun> runs = new ArrayList<>();
		try {
			for(int rootLimit : ROOT_LIMITS) {
				warm(Path.of(externalPreset), rootLimit);
			}
			for(int rootLimit : ROOT_LIMITS) {
				runs.add(measure(Path.of(externalPreset), rootLimit, 1));
			}
			for(int index = ROOT_LIMITS.size() - 1; index >= 0; index--) {
				runs.add(measure(Path.of(externalPreset), ROOT_LIMITS.get(index), 2));
			}
		} finally {
			restore(BATCH_ROOTS_PROPERTY, previousBatchRoots);
			restore(BATCH_ROOT_LIMIT_PROPERTY, previousBatchRootLimit);
			restore(PROFILE_PROPERTY, previousProfile);
			restore(VERIFY_PROPERTY, previousVerify);
			restore(PROMOTION_TILES_PROPERTY, previousPromotionTiles);
		}

		double independentMedian = median(samples(runs, 0));
		List<VariantSummary> summaries = ROOT_LIMITS.stream().map(rootLimit -> {
			double variantMedian = median(samples(runs, rootLimit));
			return new VariantSummary(rootLimit, variantMedian, (independentMedian / variantMedian - 1.0D) * 100.0D);
		}).toList();
		PerformanceReport report = new PerformanceReport(
			"RTF 2D production Tile root-limit scan only; filters included; tile close excluded",
			MEASURED_TILES,
			runs,
			summaries
		);
		Path reportPath = RtfSemanticParityTest.outputRoot().resolve("production-root-batch-performance.json");
		Files.createDirectories(reportPath.getParent());
		try(var writer = Files.newBufferedWriter(reportPath)) {
			GSON.toJson(report, writer);
		}
		System.out.println("RTF_PRODUCTION_ROOT_BATCH_PERFORMANCE " + reportPath.toAbsolutePath());
		assertTrue(runs.stream().filter(run -> run.rootLimit > 0).allMatch(run -> run.optimization.groups() > 0));
	}

	@Test
	void measuresSelectedThirtyTwoRootLimitWithBalancedOrdering() throws Exception {
		String externalPreset = System.getenv("RTF_PARITY_PRESET");
		Assumptions.assumeTrue(externalPreset != null && !externalPreset.isBlank());
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), java.util.Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		String previousBatchRoots = System.getProperty(BATCH_ROOTS_PROPERTY);
		String previousBatchRootLimit = System.getProperty(BATCH_ROOT_LIMIT_PROPERTY);
		String previousProfile = System.getProperty(PROFILE_PROPERTY);
		String previousVerify = System.getProperty(VERIFY_PROPERTY);
		String previousPromotionTiles = System.getProperty(PROMOTION_TILES_PROPERTY);
		System.clearProperty(PROFILE_PROPERTY);
		System.clearProperty(VERIFY_PROPERTY);
		List<VariantRun> runs = new ArrayList<>();
		try {
			warm(Path.of(externalPreset), 0);
			warm(Path.of(externalPreset), 32);
			int[] order = {0, 32, 32, 0, 32, 0, 0, 32};
			int independentRepetition = 0;
			int batchedRepetition = 0;
			for(int rootLimit : order) {
				int repetition = rootLimit == 0 ? ++independentRepetition : ++batchedRepetition;
				runs.add(measure(Path.of(externalPreset), rootLimit, repetition));
			}
		} finally {
			restore(BATCH_ROOTS_PROPERTY, previousBatchRoots);
			restore(BATCH_ROOT_LIMIT_PROPERTY, previousBatchRootLimit);
			restore(PROFILE_PROPERTY, previousProfile);
			restore(VERIFY_PROPERTY, previousVerify);
			restore(PROMOTION_TILES_PROPERTY, previousPromotionTiles);
		}
		double independentMedian = median(samples(runs, 0));
		double batchedMedian = median(samples(runs, 32));
		SelectedPerformanceReport report = new SelectedPerformanceReport(
			"RTF 2D production Tile selected root limit; filters included; tile close excluded",
			MEASURED_TILES,
			runs,
			independentMedian,
			batchedMedian,
			(independentMedian / batchedMedian - 1.0D) * 100.0D
		);
		Path reportPath = RtfSemanticParityTest.outputRoot().resolve("production-root-batch-selected-performance.json");
		Files.createDirectories(reportPath.getParent());
		try(var writer = Files.newBufferedWriter(reportPath)) {
			GSON.toJson(report, writer);
		}
		System.out.println("RTF_PRODUCTION_ROOT_BATCH_SELECTED_PERFORMANCE " + reportPath.toAbsolutePath());
		assertTrue(runs.stream().filter(run -> run.rootLimit == 32).allMatch(run -> run.optimization.groups() > 0));
	}

	private static void warm(Path presetPath, int rootLimit) throws Exception {
		configureRootBatch(rootLimit);
		Preset preset = RtfSemanticParityTest.loadPreset(presetPath);
		preset.world().noiseEngine = WorldSettings.NoiseEngine.QUICK_V2;
		int seed = -828_453_099;
		int tileX = Math.floorDiv(1_098_545, Size.blocks(3, 0).size());
		int tileZ = Math.floorDiv(-1_098_290, Size.blocks(3, 0).size());
		try(GeneratorContext context = GeneratorContext.makeUncached(preset, RtfSemanticParityTest.noiseLookup(preset), seed, 3, RtfSemanticParityTest.productionBorder(preset), 6, rootLimit > 0)) {
			for(int offset = 0; offset < 3; offset++) {
				try(Tile ignored = context.generator.generate(tileX + offset, tileZ).join()) {
				}
			}
		}
	}

	private static VariantRun measure(Path presetPath, int rootLimit, int repetition) throws Exception {
		configureRootBatch(rootLimit);
		Preset preset = RtfSemanticParityTest.loadPreset(presetPath);
		preset.world().noiseEngine = WorldSettings.NoiseEngine.QUICK_V2;
		int seed = -828_453_099;
		int tileX = Math.floorDiv(1_098_545, Size.blocks(3, 0).size());
		int tileZ = Math.floorDiv(-1_098_290, Size.blocks(3, 0).size());
		List<Double> milliseconds = new ArrayList<>(MEASURED_TILES);
		try(GeneratorContext context = GeneratorContext.makeUncached(preset, RtfSemanticParityTest.noiseLookup(preset), seed, 3, RtfSemanticParityTest.productionBorder(preset), 6, rootLimit > 0)) {
			try(Tile ignored = context.generator.generate(tileX, tileZ).join()) {
			}
			for(int offset = 1; offset <= MEASURED_TILES; offset++) {
				long start = System.nanoTime();
				Tile generated = context.generator.generate(tileX + offset, tileZ).join();
				long elapsed = System.nanoTime() - start;
				try(Tile ignored = generated) {
				}
				milliseconds.add(elapsed / 1_000_000.0D);
			}
			return new VariantRun(rootLimit, repetition, List.copyOf(milliseconds), context.noiseEngine.batchOptimizationSnapshot());
		}
	}

	private static ColdVariantRun measureCold(Path presetPath, int rootLimit, int repetition) throws Exception {
		configureRootBatch(rootLimit);
		Preset preset = RtfSemanticParityTest.loadPreset(presetPath);
		preset.world().noiseEngine = WorldSettings.NoiseEngine.QUICK_V2;
		int seed = -828_453_099;
		int tileX = Math.floorDiv(1_098_545, Size.blocks(3, 0).size());
		int tileZ = Math.floorDiv(-1_098_290, Size.blocks(3, 0).size());
		long totalStart = System.nanoTime();
		double firstTileMs = 0.0D;
		QuickNoiseRuntime.BatchOptimization optimization;
		try(GeneratorContext context = GeneratorContext.makeUncached(preset, RtfSemanticParityTest.noiseLookup(preset), seed, 3, RtfSemanticParityTest.productionBorder(preset), 6, rootLimit > 0)) {
			for(int offset = 0; offset < SHORT_WORKLOAD_TILES; offset++) {
				long tileStart = System.nanoTime();
				Tile generated = context.generator.generate(tileX + offset, tileZ).join();
				long tileElapsed = System.nanoTime() - tileStart;
				try(Tile ignored = generated) {
				}
				if(offset == 0) {
					firstTileMs = tileElapsed / 1_000_000.0D;
				}
			}
			optimization = context.noiseEngine.batchOptimizationSnapshot();
		}
		return new ColdVariantRun(rootLimit, repetition, firstTileMs, (System.nanoTime() - totalStart) / 1_000_000.0D, optimization);
	}

	private static void configureRootBatch(int rootLimit) {
		System.setProperty(BATCH_ROOTS_PROPERTY, Boolean.toString(rootLimit > 0));
		System.setProperty(BATCH_ROOT_LIMIT_PROPERTY, Integer.toString(rootLimit > 0 ? rootLimit : 64));
		System.setProperty(PROMOTION_TILES_PROPERTY, "0");
	}

	private static List<Double> samples(List<VariantRun> runs, int rootLimit) {
		return runs.stream().filter(run -> run.rootLimit == rootLimit).flatMap(run -> run.milliseconds.stream()).toList();
	}

	private static double median(List<Double> values) {
		List<Double> sorted = values.stream().sorted().toList();
		int middle = sorted.size() / 2;
		return sorted.size() % 2 == 0 ? (sorted.get(middle - 1) + sorted.get(middle)) / 2.0D : sorted.get(middle);
	}

	private static void restore(String property, String value) {
		if(value == null) {
			System.clearProperty(property);
		} else {
			System.setProperty(property, value);
		}
	}

	private record VariantRun(int rootLimit, int repetition, List<Double> milliseconds, QuickNoiseRuntime.BatchOptimization optimization) {
	}

	private record ColdVariantRun(int rootLimit, int repetition, double firstTileMs, double totalMs, QuickNoiseRuntime.BatchOptimization optimization) {
	}

	private record VariantSummary(int rootLimit, double medianMs, double speedupPercent) {
	}

	private record PerformanceReport(String scope, int measuredTilesPerRun, List<VariantRun> runs, List<VariantSummary> summaries) {
	}

	private record SelectedPerformanceReport(String scope, int measuredTilesPerRun, List<VariantRun> runs, double independentMedianMs, double batchedMedianMs, double speedupPercent) {
	}

	private record ColdPerformanceReport(String scope, int measuredTilesPerRun, List<ColdVariantRun> runs, double independentFirstTileMedianMs, double batchedFirstTileMedianMs, double firstTileSpeedupPercent, double independentTotalMedianMs, double batchedTotalMedianMs, double totalSpeedupPercent) {
	}
}
