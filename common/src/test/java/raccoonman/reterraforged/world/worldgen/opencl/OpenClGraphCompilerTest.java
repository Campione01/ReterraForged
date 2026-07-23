package raccoonman.reterraforged.world.worldgen.opencl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import it.unimi.dsi.fastutil.doubles.DoubleArrayList;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import raccoonman.reterraforged.config.OpenClConfig;
import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseRouterData;
import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings.CompatibilityMode;
import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings.DensityAlgorithm;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.world.worldgen.densityfunction.ClampToNearestUnit;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.quicknoise.QuickCaveDensity;

class OpenClGraphCompilerTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void compilesSupportedGraphAndRejectsUnknownNode() {
		DensityFunction supported = supportedGraph();
		OpenClKernelTemplate template = OpenClGraphCompiler.compile(supported).orElseThrow();
		assertEquals(2, template.inputCount());
		assertTrue(template.source().contains("__kernel void rtf_density"));
		assertTrue(template.source().contains("rtf_improved"));
		assertNotNull(OpenClDensityFunctions.wrapFinalDensity(DensityFunctions.interpolated(supported)));

		DensityFunction unknown = new UnknownDensityFunction();
		assertFalse(OpenClGraphCompiler.compile(DensityFunctions.add(supported, unknown)).isPresent());
		assertEquals(null, OpenClDensityFunctions.wrapFinalDensity(DensityFunctions.interpolated(unknown)));
	}

	@Test
	void openClMatchesCpuExactlyWhenDeviceIsAvailable() {
		DensityFunction function = supportedGraph();
		OpenClKernelTemplate template = OpenClGraphCompiler.compile(function).orElseThrow();
		List<DensityFunction.SinglePointContext> contexts = contexts();
		DensityFunction.ContextProvider provider = provider(contexts);
		double[] expected = new double[contexts.size()];
		function.fillArray(expected, provider);
		OpenClKernelTemplate.Batch batch = template.collect(provider, contexts.size());

		OpenClRuntime runtime = OpenClRuntime.open(new OpenClConfig(OpenClConfig.Mode.ON, 1, 1, false));
		Assumptions.assumeTrue(runtime != null, "No compiler-enabled FP64 GPU is available");
		try(runtime) {
			double[] actual = new double[expected.length];
			assertTrue(runtime.tryExecute(template, batch, actual));
			for(int i = 0; i < expected.length; i++) {
				assertEquals(
					Double.doubleToRawLongBits(expected[i]),
					Double.doubleToRawLongBits(actual[i]),
					"Density mismatch at index " + i
				);
			}
		}
	}

	@Test
	void fusedOpenClOutputsMatchCpuExactlyWhenDeviceIsAvailable() {
		DensityFunction first = supportedGraph();
		DensityFunction second = DensityFunctions.add(first, DensityFunctions.constant(0.25));
		List<DensityFunction> functions = List.of(first, second);
		OpenClKernelTemplate template = OpenClGraphCompiler.compile(functions).orElseThrow();
		List<DensityFunction.SinglePointContext> contexts = contexts();
		DensityFunction.ContextProvider provider = provider(contexts);
		double[] expected = new double[contexts.size() * functions.size()];
		for(int outputIndex = 0; outputIndex < functions.size(); outputIndex++) {
			double[] values = new double[contexts.size()];
			functions.get(outputIndex).fillArray(values, provider);
			System.arraycopy(values, 0, expected, outputIndex * contexts.size(), values.length);
		}

		OpenClRuntime runtime = OpenClRuntime.open(new OpenClConfig(OpenClConfig.Mode.ON, 1, 1, false));
		Assumptions.assumeTrue(runtime != null, "No compiler-enabled FP64 GPU is available");
		try(runtime) {
			double[] actual = new double[expected.length];
			assertTrue(runtime.tryExecute(template, template.collect(provider, contexts.size()), actual));
			for(int i = 0; i < expected.length; i++) {
				assertEquals(
					Double.doubleToRawLongBits(expected[i]),
					Double.doubleToRawLongBits(actual[i]),
					"Fused density mismatch at index " + i
				);
			}
		}
	}

	@Test
	void discoversCandidatesInTheSeededRtfCaveGraph() {
		HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
		HolderGetter<DensityFunction> densityFunctions = vanilla.lookupOrThrow(Registries.DENSITY_FUNCTION);
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters = vanilla.lookupOrThrow(Registries.NOISE);
		Preset preset = Presets.makeRTFDefault();
		preset.caves().densityAlgorithm = DensityAlgorithm.LEGACY;
		preset.caves().compatibilityMode = CompatibilityMode.RTF;
		preset.caves().entranceCaveProbability = 1.0F;

		NoiseRouter router = RtfRouterAccess.create(preset, densityFunctions, noiseParameters).mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof CellSampler.Marker marker) {
					return new CellSampler(() -> null, marker.field());
				}
				return function;
			}
		});
		NoiseGeneratorSettings vanillaSettings = vanilla.lookupOrThrow(Registries.NOISE_SETTINGS)
			.getOrThrow(NoiseGeneratorSettings.OVERWORLD)
			.value();
		NoiseGeneratorSettings rtfSettings = new NoiseGeneratorSettings(
			vanillaSettings.noiseSettings(),
			vanillaSettings.defaultBlock(),
			vanillaSettings.defaultFluid(),
			router,
			vanillaSettings.surfaceRule(),
			vanillaSettings.spawnTarget(),
			preset.world().properties.seaLevel,
			vanillaSettings.disableMobGeneration(),
			vanillaSettings.isAquifersEnabled(),
			vanillaSettings.oreVeinsEnabled(),
			vanillaSettings.useLegacyRandomSource()
		);
		DensityFunction seededFinalDensity = RandomState.create(rtfSettings, noiseParameters, 0x5EED1234L).router().finalDensity();
		DensityFunction wrapped = OpenClDensityFunctions.wrapFinalDensity(seededFinalDensity);
		assertNotNull(wrapped);

		AtomicInteger candidates = new AtomicInteger();
		wrapped.mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof OpenClDensityFunction) {
					candidates.incrementAndGet();
				}
				return function;
			}
		});
		assertEquals(6, candidates.get());
	}

	@Test
	void quickV1GraphIsOwnedByTheTileBackendOnly() {
		HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
		HolderGetter<DensityFunction> densityFunctions = vanilla.lookupOrThrow(Registries.DENSITY_FUNCTION);
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters = vanilla.lookupOrThrow(Registries.NOISE);
		Preset preset = Presets.makeRTFDefault();
		preset.caves().densityAlgorithm = DensityAlgorithm.QUICK_V1;
		preset.caves().compatibilityMode = CompatibilityMode.RTF;
		NoiseRouter router = RtfRouterAccess.create(preset, densityFunctions, noiseParameters);
		AtomicInteger quickNodes = new AtomicInteger();
		router.finalDensity().mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof QuickCaveDensity.Marker) {
					quickNodes.incrementAndGet();
				}
				return function;
			}
		});

		assertEquals(1, quickNodes.get());
		assertNull(OpenClDensityFunctions.wrapFinalDensity(router.finalDensity()));
	}

	private static DensityFunction supportedGraph() {
		NormalNoise.NoiseParameters parameters = new NormalNoise.NoiseParameters(-7, new DoubleArrayList(new double[] {1.0, 0.5, 1.0, 0.0, 0.25}));
		NormalNoise normalNoise = NormalNoise.create(RandomSource.create(0x5EED1234L), parameters);
		DensityFunction.Visitor noiseWiring = new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				return function;
			}

			@Override
			public DensityFunction.NoiseHolder visitNoise(DensityFunction.NoiseHolder holder) {
				return new DensityFunction.NoiseHolder(holder.noiseData(), normalNoise);
			}
		};
		DensityFunction noise = DensityFunctions.noise(Holder.direct(parameters), 0.75, 1.25).mapAll(noiseWiring);
		DensityFunction shiftA = DensityFunctions.shiftA(Holder.direct(parameters)).mapAll(noiseWiring);
		DensityFunction shiftB = DensityFunctions.shiftB(Holder.direct(parameters)).mapAll(noiseWiring);
		DensityFunction shifted = DensityFunctions.shiftedNoise2d(shiftA, shiftB, 0.375, Holder.direct(parameters)).mapAll(noiseWiring);
		DensityFunction shift3d = DensityFunctions.shift(Holder.direct(parameters)).mapAll(noiseWiring);
		DensityFunction cpuInput = new ClampToNearestUnit(DensityFunctions.yClampedGradient(-64, 320, -1.0, 1.0), 16);
		DensityFunction interpolatedCpuInput = DensityFunctions.interpolated(DensityFunctions.add(noise, DensityFunctions.yClampedGradient(-64, 320, -0.5, 0.75)));
		return DensityFunctions.max(
			DensityFunctions.add(
				DensityFunctions.add(noise.square(), shifted),
				DensityFunctions.add(shift3d, DensityFunctions.add(cpuInput, interpolatedCpuInput))
			),
			DensityFunctions.constant(-0.4)
		).squeeze();
	}

	private static List<DensityFunction.SinglePointContext> contexts() {
		return contexts(64);
	}

	private static List<DensityFunction.SinglePointContext> contexts(int size) {
		List<DensityFunction.SinglePointContext> contexts = new ArrayList<>();
		for(int i = 0; i < size; i++) {
			int x = -250000 + i * 7919;
			int y = -64 + (i * 37) % 385;
			int z = 180000 - i * 6151;
			contexts.add(new DensityFunction.SinglePointContext(x, y, z));
		}
		return contexts;
	}

	private static DensityFunction.ContextProvider provider(List<DensityFunction.SinglePointContext> contexts) {
		return new DensityFunction.ContextProvider() {
			@Override
			public DensityFunction.FunctionContext forIndex(int index) {
				return contexts.get(index);
			}

			@Override
			public void fillAllDirectly(double[] values, DensityFunction function) {
				for(int i = 0; i < values.length; i++) {
					values[i] = function.compute(this.forIndex(i));
				}
			}
		};
	}

	private static final class UnknownDensityFunction implements DensityFunction {
		@Override
		public double compute(FunctionContext context) {
			return context.blockX() * 0.001;
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
			return -1000000.0;
		}

		@Override
		public double maxValue() {
			return 1000000.0;
		}

		@Override
		public KeyDispatchDataCodec<? extends DensityFunction> codec() {
			return DensityFunctions.zero().codec();
		}
	}

	private static final class RtfRouterAccess extends PresetNoiseRouterData {
		private static NoiseRouter create(Preset preset, HolderGetter<DensityFunction> densityFunctions, HolderGetter<NormalNoise.NoiseParameters> noiseParameters) {
			return overworld(preset, densityFunctions, noiseParameters, null);
		}
	}
}
