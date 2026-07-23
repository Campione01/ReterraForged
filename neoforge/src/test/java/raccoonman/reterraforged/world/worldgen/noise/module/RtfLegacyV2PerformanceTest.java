package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.time.Duration;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Assumptions;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.quicknoise.QuickNoiseNative;

class RtfLegacyV2PerformanceTest {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String LEGACY_V2_TILE_SIZE = "reterraforged.legacyV2.tileSize";
	private static final String LEGACY_V2_BATCH_ROOTS = "reterraforged.legacyV2.batchRoots";
	private static final String LEGACY_V2_NATIVE_KERNELS = "reterraforged.legacyV2.nativeKernels";
	private static final String QUICK_BATCH_ROOTS = "reterraforged.quickNoise.batchRoots";
	private static final int MEASURED_TILES = 4;
	private static final int FACTOR = 3;
	private static final int BATCH_COUNT = 6;
	private static final int SEED = -828_453_099;
	private static final int BLOCK_X = 1_098_545;
	private static final int BLOCK_Z = -1_098_290;
	private static final int PROFILE_TILES = 96;
	private static volatile float primitiveSink;

	@Test
	void benchmarksExactOriginalPrimitiveNativeKernelsWhenRequested() {
		Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("RTF_LEGACY_V2_PRIMITIVE_BENCH")));
		List<PrimitiveCase> cases = List.of(
			new PrimitiveCase("perlin", Noises.perlin(17, 480, 4, 2.35F, 0.61F)),
			new PrimitiveCase("perlin2", Noises.perlin2(29, 360, 3, 1.85F, 0.47F)),
			new PrimitiveCase("perlin_ridge", Noises.perlinRidge(41, 410, 4, 2.35F, 1.15F)),
			new PrimitiveCase("simplex", Noises.simplex(53, 520, 4, 2.1F, 0.55F))
		);
		NoiseBatch batch = NoiseBatch.regular(-384, 640, 16, 16, 1.0F, 0.0F, 1.0F, 0.0F);
		float[] javaOutput = new float[batch.size()];
		float[] nativeOutput = new float[batch.size()];
		for(PrimitiveCase primitive : cases) {
			try(QuickNoiseNative.Program program = QuickNoiseNative.compileProgram(QuickNoiseGraphCompiler.compile(primitive.noise).orElseThrow().graph().encode())) {
				primitive.noise.fill(batch, SEED, javaOutput);
				program.fill(SEED, -384, 640, 16, 16, nativeOutput);
				for(int index = 0; index < batch.size(); index++) {
					if(Float.floatToRawIntBits(javaOutput[index]) != Float.floatToRawIntBits(nativeOutput[index])) {
						throw new AssertionError(primitive.name + " native mismatch at " + index);
					}
				}
				for(int warmup = 0; warmup < 300; warmup++) {
					primitive.noise.fill(batch, SEED, javaOutput);
					program.fill(SEED, -384, 640, 16, 16, nativeOutput);
				}
				long[] javaTimes = new long[7];
				long[] nativeTimes = new long[7];
				for(int round = 0; round < javaTimes.length; round++) {
					if((round & 1) == 0) {
						javaTimes[round] = measureJavaPrimitive(primitive.noise, batch, javaOutput, 2000);
						nativeTimes[round] = measureNativePrimitive(program, nativeOutput, 2000);
					} else {
						nativeTimes[round] = measureNativePrimitive(program, nativeOutput, 2000);
						javaTimes[round] = measureJavaPrimitive(primitive.noise, batch, javaOutput, 2000);
					}
				}
				Arrays.sort(javaTimes);
				Arrays.sort(nativeTimes);
				long javaMedian = javaTimes[javaTimes.length / 2];
				long nativeMedian = nativeTimes[nativeTimes.length / 2];
				System.out.printf("RTF_LEGACY_V2_PRIMITIVE name=%s java_ms=%.3f native_ms=%.3f speedup=%.3fx%n",
					primitive.name, javaMedian / 1_000_000.0D, nativeMedian / 1_000_000.0D, (double) javaMedian / nativeMedian);
			}
		}
	}

	private static long measureJavaPrimitive(Noise noise, NoiseBatch batch, float[] output, int repetitions) {
		long start = System.nanoTime();
		for(int repetition = 0; repetition < repetitions; repetition++) {
			noise.fill(batch, SEED, output);
			primitiveSink += output[repetition & (output.length - 1)];
		}
		return System.nanoTime() - start;
	}

	private static long measureNativePrimitive(QuickNoiseNative.Program program, float[] output, int repetitions) {
		long start = System.nanoTime();
		for(int repetition = 0; repetition < repetitions; repetition++) {
			program.fill(SEED, -384, 640, 16, 16, output);
			primitiveSink += output[repetition & (output.length - 1)];
		}
		return System.nanoTime() - start;
	}

	@Test
	void recordsLegacyPrimitiveProfileWhenRequested() throws Exception {
		String profile = System.getenv("RTF_LEGACY_V2_PROFILE");
		Assumptions.assumeTrue(profile != null && !profile.isBlank() && !profile.equalsIgnoreCase("false"));
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		if(!profile.equalsIgnoreCase("legacy_v2")) {
			recordProfile(WorldSettings.NoiseEngine.LEGACY, "original-rtf", "RTF Legacy original primitive profile");
		}
		if(!profile.equalsIgnoreCase("legacy")) {
			recordProfile(WorldSettings.NoiseEngine.LEGACY_V2, "legacy-v2", "RTF Legacy V2 profile");
		}
	}

	@Test
	void legacyV2SelectsTheCachedErosionImplementation() throws Exception {
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		Preset preset = Presets.makeRTFDefault();
		preset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY_V2;
		int tileSize = Size.blocks(FACTOR, 0).size();
		int tileX = Math.floorDiv(BLOCK_X, tileSize);
		int tileZ = Math.floorDiv(BLOCK_Z, tileSize);
		try(GeneratorContext context = GeneratorContext.makeUncached(preset, RtfSemanticParityTest.noiseLookup(preset), SEED, FACTOR, RtfSemanticParityTest.productionBorder(preset), BATCH_COUNT, false);
			Tile ignored = context.generator.generate(tileX, tileZ).join()) {
			Object filters = readField(context.generator, "filters");
			Object worldErosion = readField(filters, "erosion");
			Object erosion = readField(worldErosion, "value");
			assertTrue((boolean) readField(erosion, "cacheStrengthModifiers"));
		}
	}

	private static Object readField(Object owner, String name) throws ReflectiveOperationException {
		Field field = owner.getClass().getDeclaredField(name);
		field.setAccessible(true);
		return field.get(owner);
	}

	private static void recordProfile(WorldSettings.NoiseEngine engine, String fileLabel, String recordingName) throws Exception {
		Preset preset = Presets.makeRTFDefault();
		preset.world().noiseEngine = engine;
		int tileSize = Size.blocks(FACTOR, 0).size();
		int tileX = Math.floorDiv(BLOCK_X, tileSize);
		int tileZ = Math.floorDiv(BLOCK_Z, tileSize);
		Path output = RtfSemanticParityTest.outputRoot().resolve("legacy-v2-" + fileLabel + "-profile.jfr");
		Files.createDirectories(output.getParent());
		try(GeneratorContext context = GeneratorContext.makeUncached(preset, RtfSemanticParityTest.noiseLookup(preset), SEED, FACTOR, RtfSemanticParityTest.productionBorder(preset), BATCH_COUNT, false)) {
			for(int offset = 0; offset < 8; offset++) {
				try(Tile ignored = context.generator.generate(tileX + offset, tileZ).join()) {
				}
			}
			try(Recording recording = new Recording(Configuration.getConfiguration("profile"))) {
				recording.setName(recordingName);
				recording.enable("jdk.ExecutionSample").withPeriod(Duration.ofMillis(2));
				recording.start();
				for(int offset = 0; offset < PROFILE_TILES; offset++) {
					int x = tileX + offset % 16;
					int z = tileZ + 1 + offset / 16;
					try(Tile ignored = context.generator.generate(x, z).join()) {
					}
				}
				recording.stop();
				recording.dump(output);
			}
		}
		System.out.println("RTF_LEGACY_V2_PROFILE engine=" + engine + " path=" + output.toAbsolutePath());
		assertTrue(Files.size(output) > 0L);
	}

	@Test
	void scansLegacyQuickV2AndLegacyV2TileSizes() throws Exception {
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		String previousTileSize = System.getProperty(LEGACY_V2_TILE_SIZE);
		String previousLegacyBatch = System.getProperty(LEGACY_V2_BATCH_ROOTS);
		String previousNativeKernels = System.getProperty(LEGACY_V2_NATIVE_KERNELS);
		String previousQuickBatch = System.getProperty(QUICK_BATCH_ROOTS);
		System.setProperty(QUICK_BATCH_ROOTS, "false");
		List<Variant> order = List.of(
			Variant.LEGACY,
			Variant.QUICK_V2,
			Variant.LEGACY_V2_SCALAR,
			Variant.LEGACY_V2_BATCH_JAVA,
			Variant.LEGACY_V2_BATCH_NATIVE,
			Variant.LEGACY_V2_BATCH_NATIVE,
			Variant.LEGACY_V2_BATCH_JAVA,
			Variant.LEGACY_V2_SCALAR,
			Variant.QUICK_V2,
			Variant.LEGACY
		);
		List<Run> runs = new ArrayList<>();
		try {
			int[] repetitions = new int[Variant.values().length];
			for(Variant variant : order) {
				runs.add(measure(variant, ++repetitions[variant.ordinal()]));
			}
		} finally {
			restore(LEGACY_V2_TILE_SIZE, previousTileSize);
			restore(LEGACY_V2_BATCH_ROOTS, previousLegacyBatch);
			restore(LEGACY_V2_NATIVE_KERNELS, previousNativeKernels);
			restore(QUICK_BATCH_ROOTS, previousQuickBatch);
		}

		Map<String, Summary> summaries = new LinkedHashMap<>();
		double legacyMedian = median(samples(runs, Variant.LEGACY));
		for(Variant variant : Variant.values()) {
			double variantMedian = median(samples(runs, variant));
			summaries.put(variant.label, new Summary(variantMedian, (legacyMedian / variantMedian - 1.0D) * 100.0D));
		}
		Report report = new Report(
			"Preliminary same-process default-preset RTF production Tile scan; one warm Tile per context excluded; filters included; context close excluded",
			MEASURED_TILES,
			runs,
			summaries
		);
		Path output = RtfSemanticParityTest.outputRoot().resolve("legacy-v2-first-performance.json");
		Files.createDirectories(output.getParent());
		try(var writer = Files.newBufferedWriter(output)) {
			GSON.toJson(report, writer);
		}
		System.out.println("RTF_LEGACY_V2_FIRST_PERFORMANCE " + output.toAbsolutePath());
		assertTrue(runs.stream().filter(run -> run.variant.equals(Variant.LEGACY_V2_SCALAR.label)).allMatch(run -> run.batchedRoots == 0));
		assertTrue(runs.stream().filter(run -> run.variant.equals(Variant.LEGACY_V2_BATCH_JAVA.label)).allMatch(run -> run.batchedRoots > 0 && run.nativeCalls == 0));
		assertTrue(runs.stream().filter(run -> run.variant.equals(Variant.LEGACY_V2_BATCH_NATIVE.label)).allMatch(run -> run.batchedRoots > 0 && run.nativePrograms > 0 && run.nativeCalls > 0));
	}

	private static Run measure(Variant variant, int repetition) throws Exception {
		if(variant.tileSize > 0) {
			System.setProperty(LEGACY_V2_TILE_SIZE, Integer.toString(variant.tileSize));
		}
		System.setProperty(LEGACY_V2_BATCH_ROOTS, Boolean.toString(variant.batchRoots));
		System.setProperty(LEGACY_V2_NATIVE_KERNELS, Boolean.toString(variant.nativeKernels));
		Preset preset = Presets.makeRTFDefault();
		preset.world().noiseEngine = variant.engine;
		int tileSize = Size.blocks(FACTOR, 0).size();
		int tileX = Math.floorDiv(BLOCK_X, tileSize);
		int tileZ = Math.floorDiv(BLOCK_Z, tileSize);
		List<Double> milliseconds = new ArrayList<>(MEASURED_TILES);
		int batchedRoots = 0;
		int fallbackRoots = 0;
		int nativePrograms = 0;
		long nativeCalls = 0L;
		long nativeJavaFallbacks = 0L;
		List<LegacyV2Runtime.RootProfile> scalarProfiles = List.of();
		try(GeneratorContext context = GeneratorContext.makeUncached(preset, RtfSemanticParityTest.noiseLookup(preset), SEED, FACTOR, RtfSemanticParityTest.productionBorder(preset), BATCH_COUNT, false)) {
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
			if(context.legacyV2Engine != null) {
				LegacyV2Runtime.Snapshot snapshot = context.legacyV2Engine.snapshot();
				batchedRoots = snapshot.roots();
				fallbackRoots = snapshot.fallbackRoots();
				nativePrograms = snapshot.nativePrograms();
				nativeCalls = snapshot.nativeCalls();
				nativeJavaFallbacks = snapshot.nativeJavaFallbacks();
				scalarProfiles = snapshot.profiles().stream().filter(profile -> !profile.bulk()).limit(16).toList();
			}
		}
		return new Run(variant.label, repetition, List.copyOf(milliseconds), batchedRoots, fallbackRoots, nativePrograms, nativeCalls, nativeJavaFallbacks, scalarProfiles);
	}

	private static List<Double> samples(List<Run> runs, Variant variant) {
		return runs.stream().filter(run -> run.variant.equals(variant.label)).flatMap(run -> run.milliseconds.stream()).toList();
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

	private enum Variant {
		LEGACY("legacy", WorldSettings.NoiseEngine.LEGACY, 0, false, false),
		QUICK_V2("quick_v2", WorldSettings.NoiseEngine.QUICK_V2, 0, false, false),
		LEGACY_V2_SCALAR("legacy_v2_scalar", WorldSettings.NoiseEngine.LEGACY_V2, 0, false, false),
		LEGACY_V2_BATCH_JAVA("legacy_v2_batch_java", WorldSettings.NoiseEngine.LEGACY_V2, 16, true, false),
		LEGACY_V2_BATCH_NATIVE("legacy_v2_batch_native", WorldSettings.NoiseEngine.LEGACY_V2, 16, true, true);

		private final String label;
		private final WorldSettings.NoiseEngine engine;
		private final int tileSize;
		private final boolean batchRoots;
		private final boolean nativeKernels;

		Variant(String label, WorldSettings.NoiseEngine engine, int tileSize, boolean batchRoots, boolean nativeKernels) {
			this.label = label;
			this.engine = engine;
			this.tileSize = tileSize;
			this.batchRoots = batchRoots;
			this.nativeKernels = nativeKernels;
		}
	}

	private record Run(String variant, int repetition, List<Double> milliseconds, int batchedRoots, int fallbackRoots, int nativePrograms, long nativeCalls,
		long nativeJavaFallbacks, List<LegacyV2Runtime.RootProfile> scalarProfiles) {
	}

	private record Summary(double medianMs, double speedupVsLegacyPercent) {
	}

	private record PrimitiveCase(String name, Noise noise) {
	}

	private record Report(String scope, int measuredTilesPerRun, List<Run> runs, Map<String, Summary> summaries) {
	}
}
