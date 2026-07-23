package raccoonman.reterraforged.world.worldgen.opencl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.Noises;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import raccoonman.reterraforged.config.OpenClConfig;
import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseRouterData;
import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings.CompatibilityMode;
import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings.DensityAlgorithm;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.ClampToNearestUnit;

class OpenClNoiseChunkParityTest {
	private static final long SEED = 0x5EED1234L;
	private static final int START_X = 32;
	private static final int START_Z = -48;
	private static final int CELL_COUNT_XZ = 2;
	private static final int SLICE_FILLS = 3;
	private static final double SECOND_OFFSET = 0.375D;
	private static final long RTF_SEED = -1_448_906_188L;
	private static final int RTF_START_X = 5312;
	private static final int RTF_START_Z = 15136;
	private static final int RTF_CHUNK_X = RTF_START_X >> 4;
	private static final int RTF_CHUNK_Z = RTF_START_Z >> 4;
	private static final int RTF_CELL_COUNT_XZ = 4;
	private static final int RTF_CANDIDATE_COUNT = 5;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@BeforeEach
	void resetOpenClState() throws ReflectiveOperationException {
		clearActiveCycle();
		OpenClManager.close();
	}

	@AfterEach
	void closeOpenClState() throws ReflectiveOperationException {
		clearActiveCycle();
		OpenClManager.close();
	}

	@Test
	void sameNoiseChunkSliceCoordinatesCanCarryDifferentCpuInputs() throws ReflectiveOperationException {
		Graph graph = graph(true);
		Fixture fixture = fixture(graph, false);
		OpenClKernelTemplate template = OpenClGraphCompiler.compile(interpolatedRoots(fixture.seededFinalDensity())).orElseThrow();
		DensityFunction.ContextProvider provider = sliceFillingProvider(fixture.chunk());
		int size = intField(fixture.chunk(), "cellCountY") + 1;

		OpenClKernelTemplate.Batch first = template.collect(provider, size);
		OpenClKernelTemplate.Batch second = template.collect(provider, size);

		assertArrayEquals(first.coordinates(), second.coordinates(), "NoiseChunk reused the same slice coordinates");
		assertFalse(rawEquals(first.inputs(), second.inputs()), "The mutable slice pass must change the CPU input");
		assertTrue(
			legacyProviderAndSizeMatch(provider, provider, first.size(), second.size()),
			"The removed Cycle predicate would accept these two batches"
		);
		assertFalse(first.matches(second), "Cycle reuse must reject equal coordinates with different CPU inputs");
		assertEquals(4, graph.input().passCount());
		assertEquals(1, graph.input().contextCount());
		assertSame(fixture.chunk(), graph.input().onlyContext());

		double staleSecondSlot = first.inputs()[0] + SECOND_OFFSET;
		double freshSecondSlot = second.inputs()[0] + SECOND_OFFSET;
		assertNotEquals(
			Double.doubleToRawLongBits(staleSecondSlot),
			Double.doubleToRawLongBits(freshSecondSlot),
			"The old provider-identity Cycle would return a stale second-slot value"
		);
	}

	@Test
	void mappedNoiseChunkMatchesCpuAcrossSlicesCellsAndSlots() throws ReflectiveOperationException {
		OpenClRuntime runtime = OpenClRuntime.open(new OpenClConfig(OpenClConfig.Mode.ON, 1, 1, false));
		Assumptions.assumeTrue(runtime != null, "No compatible OpenCL GPU is available");
		installRuntime(runtime);

		Graph cpuGraph = graph(false);
		Graph gpuGraph = graph(false);
		Fixture cpu = fixture(cpuGraph, false);
		Fixture gpu = fixture(gpuGraph, true);
		MappingStats mapping = markMappedTemplatesVerified(gpu.chunk());
		assertEquals(2, interpolators(cpu.chunk()).size());
		assertEquals(4, mapping.interpolators(), "Expected both slots in both NoiseChunk mapping passes");
		assertEquals(2, mapping.groups(), "Expected separate router and final-density mapped groups");

		long dispatchesBefore = longField(runtime, "dispatchCount");
		boolean cpuStarted = false;
		boolean gpuStarted = false;
		try {
			cpu.chunk().initializeForFirstCellX();
			cpuStarted = true;
			gpu.chunk().initializeForFirstCellX();
			gpuStarted = true;
			assertSlicesEqual(cpu.chunk(), gpu.chunk(), "initial slice");

			cpu.chunk().advanceCellX(0);
			gpu.chunk().advanceCellX(0);
			assertSlicesEqual(cpu.chunk(), gpu.chunk(), "cell x=0");
			assertCellsEqual(cpu, gpu, 0);

			cpu.chunk().swapSlices();
			gpu.chunk().swapSlices();
			cpu.chunk().advanceCellX(1);
			gpu.chunk().advanceCellX(1);
			assertSlicesEqual(cpu.chunk(), gpu.chunk(), "cell x=1");
			assertCellsEqual(cpu, gpu, 1);
		} finally {
			if(cpuStarted) {
				cpu.chunk().stopInterpolation();
			}
			if(gpuStarted) {
				gpu.chunk().stopInterpolation();
			}
		}

		long dispatches = longField(runtime, "dispatchCount") - dispatchesBefore;
		assertEquals(
			(long) mapping.groups() * (CELL_COUNT_XZ + 1) * SLICE_FILLS,
			dispatches,
			"Equal coordinate/input batches should fuse both slots once per mapped group"
		);
		assertEquals(1, cpuGraph.input().contextCount());
		assertEquals(1, gpuGraph.input().contextCount());
		assertSame(cpu.chunk(), cpuGraph.input().onlyContext());
		assertSame(gpu.chunk(), gpuGraph.input().onlyContext());
		assertTrue(cpuGraph.input().distinctX() >= SLICE_FILLS);
		assertTrue(cpuGraph.input().distinctZ() >= CELL_COUNT_XZ + 1);
	}

	@Test
	void legacyRtfNoiseChunkMatchesCpuAtArtifactCoordinates() throws ReflectiveOperationException {
		this.assertRtfNoiseChunkMatchesCpuAtArtifactCoordinates(DensityAlgorithm.LEGACY);
	}

	@Test
	void legacyV2RtfNoiseChunkMatchesCpuAtArtifactCoordinates() throws ReflectiveOperationException {
		this.assertRtfNoiseChunkMatchesCpuAtArtifactCoordinates(DensityAlgorithm.LEGACY_V2);
	}

	private void assertRtfNoiseChunkMatchesCpuAtArtifactCoordinates(DensityAlgorithm densityAlgorithm) throws ReflectiveOperationException {
		OpenClRuntime runtime = OpenClRuntime.open(new OpenClConfig(OpenClConfig.Mode.ON, 1, 1, false));
		Assumptions.assumeTrue(runtime != null, "No compatible OpenCL GPU is available");
		installRuntime(runtime);

		HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
		Preset preset = Presets.makeRTFDefault();
		preset.caves().densityAlgorithm = densityAlgorithm;
		preset.caves().compatibilityMode = CompatibilityMode.RTF;
		RtfFixture cpu = rtfFixture(preset, vanilla, false);
		RtfFixture gpu = rtfFixture(preset, vanilla, true);
		RtfMapping mapping = mapRtfInterpolators(cpu.chunk(), gpu.chunk());
		assertEquals(RTF_CANDIDATE_COUNT, mapping.slotsToCpuInterpolator().size(), "Expected all default " + densityAlgorithm + " density candidates");
		assertEquals(2, mapping.groups(), "Expected router and final-density NoiseChunk mapping groups");
		assertTrue(gpu.cellSamplers() > 0, "RTF markers must bind to production CellSampler.CacheChunk wrappers");
		assertFalse(mapping.cpuInputClasses().isEmpty(), "Mapped RTF cave kernels must retain their CPU input boundaries");

		long dispatchesBefore = longField(runtime, "dispatchCount");
		boolean cpuStarted = false;
		boolean gpuStarted = false;
		try {
			cpu.chunk().initializeForFirstCellX();
			cpuStarted = true;
			gpu.chunk().initializeForFirstCellX();
			gpuStarted = true;
			assertRtfSlicesEqual(cpu.chunk(), gpu.chunk(), mapping, "RTF initial slice");

			cpu.chunk().advanceCellX(0);
			gpu.chunk().advanceCellX(0);
			assertRtfSlicesEqual(cpu.chunk(), gpu.chunk(), mapping, "RTF cell x=0");
			assertRtfCellsEqual(cpu, gpu, mapping, 0);

			cpu.chunk().swapSlices();
			gpu.chunk().swapSlices();
			cpu.chunk().advanceCellX(1);
			gpu.chunk().advanceCellX(1);
			assertRtfSlicesEqual(cpu.chunk(), gpu.chunk(), mapping, "RTF cell x=1");
			assertRtfCellsEqual(cpu, gpu, mapping, 1);
		} finally {
			if(cpuStarted) {
				cpu.chunk().stopInterpolation();
			}
			if(gpuStarted) {
				gpu.chunk().stopInterpolation();
			}
		}

		long dispatches = longField(runtime, "dispatchCount") - dispatchesBefore;
		assertEquals(
			(long) mapping.groups() * (RTF_CELL_COUNT_XZ + 1) * SLICE_FILLS,
			dispatches,
			"Equal coordinate/input batches should fuse every default LEGACY candidate once per mapped group"
		);
		System.out.printf(
			"RTF_OPENCL_NOISE_CHUNK candidates=%d groups=%d cell_samplers=%d cpu_interpolators=%d gpu_interpolators=%d dispatches=%d inputs=%s%n",
			mapping.slotsToCpuInterpolator().size(),
			mapping.groups(),
			gpu.cellSamplers(),
			mapping.cpuInterpolatorCount(),
			mapping.gpuInterpolatorCount(),
			dispatches,
			mapping.cpuInputClasses()
		);
	}

	private static Graph graph(boolean changesPerPass) {
		HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters = vanilla.lookupOrThrow(Registries.NOISE);
		MutableSliceInput input = new MutableSliceInput(changesPerPass);
		DensityFunction cpuInput = new ClampToNearestUnit(input, 1024);
		DensityFunction noise = DensityFunctions.noise(noiseParameters.getOrThrow(Noises.CAVE_CHEESE), 0.75D, 1.25D);
		DensityFunction first = DensityFunctions.add(
			cpuInput,
			DensityFunctions.add(noise, DensityFunctions.yClampedGradient(-64, 320, -0.4D, 0.6D))
		);
		DensityFunction second = DensityFunctions.add(
			cpuInput,
			DensityFunctions.add(noise, DensityFunctions.constant(SECOND_OFFSET))
		);
		DensityFunction finalDensity = DensityFunctions.add(
			DensityFunctions.interpolated(first),
			DensityFunctions.interpolated(second)
		);
		return new Graph(input, first, second, finalDensity);
	}

	private static Fixture fixture(Graph graph, boolean openCl) throws ReflectiveOperationException {
		HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters = vanilla.lookupOrThrow(Registries.NOISE);
		NoiseGeneratorSettings vanillaSettings = vanilla.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD)
			.value();
		DensityFunction zero = DensityFunctions.zero();
		NoiseRouter router = new NoiseRouter(
			zero, zero, zero, zero, zero, zero, zero, zero, zero, zero, zero,
			graph.finalDensity(),
			zero, zero, zero
		);
		NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
			vanillaSettings.noiseSettings(),
			vanillaSettings.defaultBlock(),
			vanillaSettings.defaultFluid(),
			router,
			vanillaSettings.surfaceRule(),
			vanillaSettings.spawnTarget(),
			vanillaSettings.seaLevel(),
			vanillaSettings.disableMobGeneration(),
			false,
			false,
			vanillaSettings.useLegacyRandomSource()
		);
		RandomState randomState = RandomState.create(settings, noiseParameters, SEED);
		DensityFunction seededFinalDensity = randomState.router().finalDensity();
		if(openCl) {
			DensityFunction wrapped = OpenClDensityFunctions.wrapFinalDensityAllowingCpuInputs(seededFinalDensity);
			assertNotNull(wrapped);
			setField(randomState, "router", OpenClDensityFunctions.withFinalDensity(randomState.router(), wrapped));
		}
		Aquifer.FluidStatus fluid = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
		NoiseChunk chunk = new NoiseChunk(
			CELL_COUNT_XZ,
			randomState,
			START_X,
			START_Z,
			settings.noiseSettings(),
			DensityFunctions.BeardifierMarker.INSTANCE,
			settings,
			(x, y, z) -> fluid,
			Blender.empty()
		);
		return new Fixture(chunk, settings, seededFinalDensity);
	}

	private static RtfFixture rtfFixture(
		Preset preset,
		HolderLookup.Provider vanilla,
		boolean openCl
	) throws ReflectiveOperationException {
		HolderGetter<DensityFunction> densityFunctions = vanilla.lookupOrThrow(Registries.DENSITY_FUNCTION);
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters = vanilla.lookupOrThrow(Registries.NOISE);
		AtomicInteger cellSamplers = new AtomicInteger();
		CellSampler.Cache2d cache2d = new CellSampler.Cache2d();
		NoiseRouter router = RtfRouterAccess.create(preset, densityFunctions, noiseParameters).mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof CellSampler.Marker marker) {
					cellSamplers.incrementAndGet();
					// common:test leaves RegistryUtil.createRegistry() @ExpectPlatform untransformed, so RTFBuiltInRegistries
					// cannot create the GeneratorContext needed for a real Tile.Chunk lookup.
					CellSampler sampler = new CellSampler(() -> null, marker.field());
					return sampler.new CacheChunk(null, cache2d, RTF_CHUNK_X, RTF_CHUNK_Z);
				}
				return function;
			}
		});
		assertTrue(cellSamplers.get() > 0, "RTF router did not expose CellSampler markers");

		NoiseGeneratorSettings vanillaSettings = vanilla.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD)
			.value();
		NoiseGeneratorSettings settings = new NoiseGeneratorSettings(
			vanillaSettings.noiseSettings(),
			vanillaSettings.defaultBlock(),
			vanillaSettings.defaultFluid(),
			router,
			vanillaSettings.surfaceRule(),
			vanillaSettings.spawnTarget(),
			preset.world().properties.seaLevel,
			vanillaSettings.disableMobGeneration(),
			false,
			false,
			vanillaSettings.useLegacyRandomSource()
		);
		RandomState randomState = RandomState.create(settings, noiseParameters, RTF_SEED);
		if(openCl) {
			DensityFunction wrapped = OpenClDensityFunctions.wrapFinalDensityAllowingCpuInputs(randomState.router().finalDensity());
			assertNotNull(wrapped, "Full seeded RTF LEGACY graph produced no OpenCL candidates");
			setField(randomState, "router", OpenClDensityFunctions.withFinalDensity(randomState.router(), wrapped));
		}
		Aquifer.FluidStatus fluid = new Aquifer.FluidStatus(settings.seaLevel(), settings.defaultFluid());
		NoiseChunk chunk = new NoiseChunk(
			RTF_CELL_COUNT_XZ,
			randomState,
			RTF_START_X,
			RTF_START_Z,
			settings.noiseSettings(),
			DensityFunctions.BeardifierMarker.INSTANCE,
			settings,
			(x, y, z) -> fluid,
			Blender.empty()
		);
		return new RtfFixture(chunk, settings, cellSamplers.get());
	}

	private static List<DensityFunction> interpolatedRoots(DensityFunction density) {
		List<DensityFunction> roots = new ArrayList<>();
		density.mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof DensityFunctions.Marker marker
					&& marker.type() == DensityFunctions.Marker.Type.Interpolated) {
					roots.add(marker.wrapped());
				}
				return function;
			}
		});
		return roots;
	}

	private static void assertCellsEqual(Fixture cpu, Fixture gpu, int cellX) throws ReflectiveOperationException {
		int cellCountY = intField(cpu.chunk(), "cellCountY");
		int cellWidth = intField(cpu.chunk(), "cellWidth");
		int cellHeight = intField(cpu.chunk(), "cellHeight");
		int[] cellYs = { 0, cellCountY / 2, cellCountY - 1 };
		for(int cellZ = 0; cellZ < CELL_COUNT_XZ; cellZ++) {
			for(int cellY : cellYs) {
				cpu.chunk().selectCellYZ(cellY, cellZ);
				gpu.chunk().selectCellYZ(cellY, cellZ);
				assertCellCachesEqual(cpu.chunk(), gpu.chunk(), "cell " + cellX + "," + cellY + "," + cellZ);

				for(int inY : new int[] { 0, cellHeight - 1 }) {
					int blockY = cpu.settings().noiseSettings().minY() + cellY * cellHeight + inY;
					double yFraction = (double) inY / cellHeight;
					cpu.chunk().updateForY(blockY, yFraction);
					gpu.chunk().updateForY(blockY, yFraction);
					for(int inX : new int[] { 0, cellWidth - 1 }) {
						int blockX = START_X + cellX * cellWidth + inX;
						double xFraction = (double) inX / cellWidth;
						cpu.chunk().updateForX(blockX, xFraction);
						gpu.chunk().updateForX(blockX, xFraction);
						for(int inZ : new int[] { 0, cellWidth - 1 }) {
							int blockZ = START_Z + cellZ * cellWidth + inZ;
							double zFraction = (double) inZ / cellWidth;
							cpu.chunk().updateForZ(blockZ, zFraction);
							gpu.chunk().updateForZ(blockZ, zFraction);
							assertInterpolatorValuesEqual(
								cpu.chunk(),
								gpu.chunk(),
								"point " + blockX + "," + blockY + "," + blockZ
							);
						}
					}
				}
			}
		}
	}

	private static void assertSlicesEqual(NoiseChunk cpu, NoiseChunk gpu, String location) throws ReflectiveOperationException {
		List<?> cpuInterpolators = interpolators(cpu);
		List<?> gpuInterpolators = interpolators(gpu);
		assertEquals(cpuInterpolators.size() * 2, gpuInterpolators.size(), location);
		for(int i = 0; i < gpuInterpolators.size(); i++) {
			int slot = openClSlot(gpuInterpolators.get(i));
			assertRawMatrixEquals(
				(double[][]) objectField(cpuInterpolators.get(slot), "slice0"),
				(double[][]) objectField(gpuInterpolators.get(i), "slice0"),
				location + " interpolator " + i + " slice0"
			);
			assertRawMatrixEquals(
				(double[][]) objectField(cpuInterpolators.get(slot), "slice1"),
				(double[][]) objectField(gpuInterpolators.get(i), "slice1"),
				location + " interpolator " + i + " slice1"
			);
		}
	}

	private static void assertCellCachesEqual(NoiseChunk cpu, NoiseChunk gpu, String location) throws ReflectiveOperationException {
		List<?> cpuCaches = listField(cpu, "cellCaches");
		List<?> gpuCaches = listField(gpu, "cellCaches");
		assertEquals(cpuCaches.size(), gpuCaches.size(), location);
		for(int i = 0; i < cpuCaches.size(); i++) {
			assertRawArrayEquals(
				(double[]) objectField(cpuCaches.get(i), "values"),
				(double[]) objectField(gpuCaches.get(i), "values"),
				location + " cache " + i
			);
		}
	}

	private static void assertInterpolatorValuesEqual(NoiseChunk cpu, NoiseChunk gpu, String location) throws ReflectiveOperationException {
		List<?> cpuInterpolators = interpolators(cpu);
		List<?> gpuInterpolators = interpolators(gpu);
		for(int i = 0; i < gpuInterpolators.size(); i++) {
			int slot = openClSlot(gpuInterpolators.get(i));
			double cpuValue = doubleField(cpuInterpolators.get(slot), "value");
			double gpuValue = doubleField(gpuInterpolators.get(i), "value");
			assertEquals(
				Double.doubleToRawLongBits(cpuValue),
				Double.doubleToRawLongBits(gpuValue),
				location + " interpolator " + i
			);
		}
	}

	private static RtfMapping mapRtfInterpolators(NoiseChunk cpu, NoiseChunk gpu) throws ReflectiveOperationException {
		List<?> cpuInterpolators = interpolators(cpu);
		List<?> gpuInterpolators = interpolators(gpu);
		assertTrue(gpuInterpolators.size() >= cpuInterpolators.size());
		Map<Integer, Integer> slotsToCpuInterpolator = new HashMap<>();
		Set<OpenClDensityGroup> groups = Collections.newSetFromMap(new IdentityHashMap<>());
		Set<String> cpuInputClasses = new HashSet<>();
		int openClInterpolators = 0;

		for(int i = 0; i < gpuInterpolators.size(); i++) {
			Object noiseFiller = objectField(gpuInterpolators.get(i), "noiseFiller");
			if(!(noiseFiller instanceof OpenClDensityFunction)) {
				continue;
			}
			int slot = intField(noiseFiller, "slot");
			if(i < cpuInterpolators.size()) {
				slotsToCpuInterpolator.putIfAbsent(slot, i);
			}
			OpenClDensityGroup group = (OpenClDensityGroup) objectField(noiseFiller, "group");
			groups.add(group);
			openClInterpolators++;
		}
		for(OpenClDensityGroup group : groups) {
			OpenClKernelTemplate template = (OpenClKernelTemplate) objectField(group, "template");
			assertNotNull(template, "Mapped full-RTF OpenCL group was not sealed");
			assertEquals(RTF_CANDIDATE_COUNT, template.outputCount());
			for(Object input : listField(template, "cpuInputs")) {
				cpuInputClasses.add(input.getClass().getName());
			}
			OpenClManager.markVerified(template);
		}
		assertEquals(RTF_CANDIDATE_COUNT, slotsToCpuInterpolator.size());
		for(int i = cpuInterpolators.size(); i < gpuInterpolators.size(); i++) {
			int slot = openClSlot(gpuInterpolators.get(i));
			assertTrue(slotsToCpuInterpolator.containsKey(slot), "Missing CPU interpolator for duplicate slot " + slot);
		}
		return new RtfMapping(
			cpuInterpolators.size(),
			gpuInterpolators.size(),
			openClInterpolators,
			groups.size(),
			Map.copyOf(slotsToCpuInterpolator),
			Set.copyOf(cpuInputClasses)
		);
	}

	private static void assertRtfSlicesEqual(
		NoiseChunk cpu,
		NoiseChunk gpu,
		RtfMapping mapping,
		String location
	) throws ReflectiveOperationException {
		List<?> cpuInterpolators = interpolators(cpu);
		List<?> gpuInterpolators = interpolators(gpu);
		for(int i = 0; i < gpuInterpolators.size(); i++) {
			int cpuIndex = rtfCpuInterpolatorIndex(gpuInterpolators.get(i), i, mapping);
			assertRawMatrixEquals(
				(double[][]) objectField(cpuInterpolators.get(cpuIndex), "slice0"),
				(double[][]) objectField(gpuInterpolators.get(i), "slice0"),
				location + " interpolator " + i + " slice0"
			);
			assertRawMatrixEquals(
				(double[][]) objectField(cpuInterpolators.get(cpuIndex), "slice1"),
				(double[][]) objectField(gpuInterpolators.get(i), "slice1"),
				location + " interpolator " + i + " slice1"
			);
		}
	}

	private static void assertRtfCellsEqual(
		RtfFixture cpu,
		RtfFixture gpu,
		RtfMapping mapping,
		int cellX
	) throws ReflectiveOperationException {
		int cellCountY = intField(cpu.chunk(), "cellCountY");
		int cellWidth = intField(cpu.chunk(), "cellWidth");
		int cellHeight = intField(cpu.chunk(), "cellHeight");
		int[] cellYs = { 0, cellCountY / 2, cellCountY - 1 };
		int[] cellZs = { 0, RTF_CELL_COUNT_XZ / 2, RTF_CELL_COUNT_XZ - 1 };
		for(int cellZ : cellZs) {
			for(int cellY : cellYs) {
				cpu.chunk().selectCellYZ(cellY, cellZ);
				gpu.chunk().selectCellYZ(cellY, cellZ);
				assertFinalCellCacheEqual(cpu.chunk(), gpu.chunk(), "RTF cell " + cellX + "," + cellY + "," + cellZ);

				for(int inY : new int[] { 0, cellHeight - 1 }) {
					int blockY = cpu.settings().noiseSettings().minY() + cellY * cellHeight + inY;
					double yFraction = (double) inY / cellHeight;
					cpu.chunk().updateForY(blockY, yFraction);
					gpu.chunk().updateForY(blockY, yFraction);
					for(int inX : new int[] { 0, cellWidth - 1 }) {
						int blockX = RTF_START_X + cellX * cellWidth + inX;
						double xFraction = (double) inX / cellWidth;
						cpu.chunk().updateForX(blockX, xFraction);
						gpu.chunk().updateForX(blockX, xFraction);
						for(int inZ : new int[] { 0, cellWidth - 1 }) {
							int blockZ = RTF_START_Z + cellZ * cellWidth + inZ;
							double zFraction = (double) inZ / cellWidth;
							cpu.chunk().updateForZ(blockZ, zFraction);
							gpu.chunk().updateForZ(blockZ, zFraction);
							assertRtfInterpolatorValuesEqual(
								cpu.chunk(),
								gpu.chunk(),
								mapping,
								"RTF point " + blockX + "," + blockY + "," + blockZ
							);
						}
					}
				}
			}
		}
	}

	private static void assertFinalCellCacheEqual(NoiseChunk cpu, NoiseChunk gpu, String location) throws ReflectiveOperationException {
		List<?> cpuCaches = listField(cpu, "cellCaches");
		List<?> gpuCaches = listField(gpu, "cellCaches");
		assertFalse(cpuCaches.isEmpty(), location);
		assertFalse(gpuCaches.isEmpty(), location);
		assertRawArrayEquals(
			(double[]) objectField(cpuCaches.getLast(), "values"),
			(double[]) objectField(gpuCaches.getLast(), "values"),
			location + " final density"
		);
	}

	private static void assertRtfInterpolatorValuesEqual(
		NoiseChunk cpu,
		NoiseChunk gpu,
		RtfMapping mapping,
		String location
	) throws ReflectiveOperationException {
		List<?> cpuInterpolators = interpolators(cpu);
		List<?> gpuInterpolators = interpolators(gpu);
		for(int i = 0; i < gpuInterpolators.size(); i++) {
			int cpuIndex = rtfCpuInterpolatorIndex(gpuInterpolators.get(i), i, mapping);
			double cpuValue = doubleField(cpuInterpolators.get(cpuIndex), "value");
			double gpuValue = doubleField(gpuInterpolators.get(i), "value");
			assertEquals(
				Double.doubleToRawLongBits(cpuValue),
				Double.doubleToRawLongBits(gpuValue),
				location + " interpolator " + i
			);
		}
	}

	private static int rtfCpuInterpolatorIndex(Object gpuInterpolator, int gpuIndex, RtfMapping mapping) throws ReflectiveOperationException {
		if(gpuIndex < mapping.cpuInterpolatorCount()) {
			return gpuIndex;
		}
		int slot = openClSlot(gpuInterpolator);
		Integer cpuIndex = mapping.slotsToCpuInterpolator().get(slot);
		assertNotNull(cpuIndex, "Missing CPU slot " + slot);
		return cpuIndex;
	}

	private static int openClSlot(Object interpolator) throws ReflectiveOperationException {
		Object noiseFiller = objectField(interpolator, "noiseFiller");
		assertTrue(noiseFiller instanceof OpenClDensityFunction, "Expected an OpenCL-mapped NoiseInterpolator");
		return intField(noiseFiller, "slot");
	}

	private static MappingStats markMappedTemplatesVerified(NoiseChunk chunk) throws ReflectiveOperationException {
		Set<OpenClDensityGroup> groups = Collections.newSetFromMap(new IdentityHashMap<>());
		int count = 0;
		for(Object interpolator : interpolators(chunk)) {
			Object noiseFiller = objectField(interpolator, "noiseFiller");
			if(noiseFiller instanceof OpenClDensityFunction) {
				OpenClDensityGroup group = (OpenClDensityGroup) objectField(noiseFiller, "group");
				groups.add(group);
				count++;
			}
		}
		for(OpenClDensityGroup group : groups) {
			OpenClKernelTemplate template = (OpenClKernelTemplate) objectField(group, "template");
			assertNotNull(template, "Mapped OpenCL group was not sealed");
			assertEquals(2, template.outputCount());
			OpenClManager.markVerified(template);
		}
		assertTrue(groups.size() >= 2, "Expected separate NoiseChunk mapping passes");
		return new MappingStats(count, groups.size());
	}

	@SuppressWarnings("unchecked")
	private static List<?> interpolators(NoiseChunk chunk) throws ReflectiveOperationException {
		return (List<?>) objectField(chunk, "interpolators");
	}

	private static DensityFunction.ContextProvider sliceFillingProvider(NoiseChunk chunk) throws ReflectiveOperationException {
		return (DensityFunction.ContextProvider) objectField(chunk, "sliceFillingContextProvider");
	}

	private static void installRuntime(OpenClRuntime runtime) throws ReflectiveOperationException {
		setStaticField(OpenClManager.class, "config", new OpenClConfig(OpenClConfig.Mode.ON, 1, 1, false));
		setStaticField(OpenClManager.class, "runtime", runtime);
		setStaticField(OpenClManager.class, "initializationAttempted", true);
	}

	private static void clearActiveCycle() throws ReflectiveOperationException {
		Field field = field(OpenClDensityGroup.class, "ACTIVE_CYCLE");
		((ThreadLocal<?>) field.get(null)).remove();
	}

	private static void setStaticField(Class<?> owner, String name, Object value) throws ReflectiveOperationException {
		field(owner, name).set(null, value);
	}

	private static void setField(Object target, String name, Object value) throws ReflectiveOperationException {
		field(target.getClass(), name).set(target, value);
	}

	private static Object objectField(Object target, String name) throws ReflectiveOperationException {
		return field(target.getClass(), name).get(target);
	}

	private static int intField(Object target, String name) throws ReflectiveOperationException {
		return field(target.getClass(), name).getInt(target);
	}

	private static long longField(Object target, String name) throws ReflectiveOperationException {
		return field(target.getClass(), name).getLong(target);
	}

	private static double doubleField(Object target, String name) throws ReflectiveOperationException {
		return field(target.getClass(), name).getDouble(target);
	}

	@SuppressWarnings("unchecked")
	private static List<?> listField(Object target, String name) throws ReflectiveOperationException {
		return (List<?>) objectField(target, name);
	}

	private static Field field(Class<?> owner, String name) throws NoSuchFieldException {
		Class<?> current = owner;
		while(current != null) {
			try {
				Field field = current.getDeclaredField(name);
				field.setAccessible(true);
				return field;
			} catch(NoSuchFieldException ignored) {
				current = current.getSuperclass();
			}
		}
		throw new NoSuchFieldException(owner.getName() + "." + name);
	}

	private static void assertRawMatrixEquals(double[][] expected, double[][] actual, String message) {
		assertEquals(expected.length, actual.length, message);
		for(int i = 0; i < expected.length; i++) {
			assertRawArrayEquals(expected[i], actual[i], message + " row " + i);
		}
	}

	private static void assertRawArrayEquals(double[] expected, double[] actual, String message) {
		assertEquals(expected.length, actual.length, message);
		for(int i = 0; i < expected.length; i++) {
			assertEquals(
				Double.doubleToRawLongBits(expected[i]),
				Double.doubleToRawLongBits(actual[i]),
				message + " index " + i
			);
		}
	}

	private static boolean rawEquals(double[] first, double[] second) {
		if(first.length != second.length) {
			return false;
		}
		for(int i = 0; i < first.length; i++) {
			if(Double.doubleToRawLongBits(first[i]) != Double.doubleToRawLongBits(second[i])) {
				return false;
			}
		}
		return true;
	}

	private static boolean legacyProviderAndSizeMatch(
		DensityFunction.ContextProvider activeProvider,
		DensityFunction.ContextProvider nextProvider,
		int activeSize,
		int nextSize
	) {
		return activeProvider == nextProvider && activeSize == nextSize;
	}

	private record Graph(
		MutableSliceInput input,
		DensityFunction first,
		DensityFunction second,
		DensityFunction finalDensity
	) {
	}

	private record Fixture(
		NoiseChunk chunk,
		NoiseGeneratorSettings settings,
		DensityFunction seededFinalDensity
	) {
	}

	private record MappingStats(int interpolators, int groups) {
	}

	private record RtfFixture(NoiseChunk chunk, NoiseGeneratorSettings settings, int cellSamplers) {
	}

	private record RtfMapping(
		int cpuInterpolatorCount,
		int gpuInterpolatorCount,
		int openClInterpolators,
		int groups,
		Map<Integer, Integer> slotsToCpuInterpolator,
		Set<String> cpuInputClasses
	) {
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

	private static final class MutableSliceInput implements DensityFunction {
		private final Set<FunctionContext> contexts = Collections.newSetFromMap(new IdentityHashMap<>());
		private final Set<Integer> xs = new java.util.HashSet<>();
		private final Set<Integer> zs = new java.util.HashSet<>();
		private final boolean changesPerPass;
		private int pass;

		private MutableSliceInput(boolean changesPerPass) {
			this.changesPerPass = changesPerPass;
		}

		@Override
		public double compute(FunctionContext context) {
			this.contexts.add(context);
			this.xs.add(context.blockX());
			this.zs.add(context.blockZ());
			if(context.blockY() == -64) {
				this.pass++;
			}
			double passValue = this.changesPerPass ? this.pass * 0.03125D : 0.0D;
			return passValue + context.blockX() * 0.00001D + context.blockZ() * 0.00002D;
		}

		@Override
		public void fillArray(double[] values, ContextProvider contextProvider) {
			contextProvider.fillAllDirectly(values, this);
		}

		@Override
		public DensityFunction mapAll(Visitor visitor) {
			return visitor.apply(this);
		}

		@Override
		public double minValue() {
			return -100.0D;
		}

		@Override
		public double maxValue() {
			return 100.0D;
		}

		@Override
		public KeyDispatchDataCodec<? extends DensityFunction> codec() {
			return DensityFunctions.zero().codec();
		}

		int passCount() {
			return this.pass;
		}

		int contextCount() {
			return this.contexts.size();
		}

		FunctionContext onlyContext() {
			return this.contexts.iterator().next();
		}

		int distinctX() {
			return this.xs.size();
		}

		int distinctZ() {
			return this.zs.size();
		}
	}
}
