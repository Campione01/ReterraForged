package raccoonman.reterraforged.data.worldgen.preset;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings.CompatibilityMode;
import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings.DensityAlgorithm;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;

class CaveDensityAlgorithmParityTest {
	private static final long SEED = 0x4C45474143595632L;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void legacyV2PreservesTheOriginalDensityGraph() {
		NoiseRouter legacy = createUnseededRouter(DensityAlgorithm.LEGACY, 0.73F);
		NoiseRouter legacyV2 = createUnseededRouter(DensityAlgorithm.LEGACY_V2, 0.73F);

		int legacyCaches = countCacheOnce(legacy.finalDensity());
		int legacyV2Caches = countCacheOnce(legacyV2.finalDensity());

		assertEquals(legacyCaches, legacyV2Caches);
		assertEquals(legacy.finalDensity(), legacyV2.finalDensity());
	}

	@Test
	void legacyV2MatchesLegacyByRawBitsForScalarAndBulkSampling() {
		for(float entranceProbability : new float[] { 0.0F, 0.73F, 1.0F }) {
			DensityFunction legacy = createSeededFinalDensity(DensityAlgorithm.LEGACY, entranceProbability);
			DensityFunction legacyV2 = createSeededFinalDensity(DensityAlgorithm.LEGACY_V2, entranceProbability);
			assertEquals(Double.doubleToRawLongBits(legacy.minValue()), Double.doubleToRawLongBits(legacyV2.minValue()));
			assertEquals(Double.doubleToRawLongBits(legacy.maxValue()), Double.doubleToRawLongBits(legacyV2.maxValue()));

			List<DensityFunction.SinglePointContext> contexts = contexts(20_000, Float.floatToRawIntBits(entranceProbability));
			for(int i = 0; i < contexts.size(); i++) {
				DensityFunction.SinglePointContext context = contexts.get(i);
				assertEquals(
					Double.doubleToRawLongBits(legacy.compute(context)),
					Double.doubleToRawLongBits(legacyV2.compute(context)),
					"Scalar density mismatch at index " + i + " with entrance probability " + entranceProbability
				);
			}

			DensityFunction.ContextProvider provider = provider(contexts);
			double[] expected = new double[contexts.size()];
			double[] actual = new double[contexts.size()];
			legacy.fillArray(expected, provider);
			legacyV2.fillArray(actual, provider);
			for(int i = 0; i < expected.length; i++) {
				assertEquals(
					Double.doubleToRawLongBits(expected[i]),
					Double.doubleToRawLongBits(actual[i]),
					"Bulk density mismatch at index " + i + " with entrance probability " + entranceProbability
				);
			}
		}
	}

	private static DensityFunction createSeededFinalDensity(DensityAlgorithm algorithm, float entranceProbability) {
		HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters = vanilla.lookupOrThrow(Registries.NOISE);
		NoiseRouter router = createUnseededRouter(algorithm, entranceProbability);
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
			vanillaSettings.seaLevel(),
			vanillaSettings.disableMobGeneration(),
			vanillaSettings.isAquifersEnabled(),
			vanillaSettings.oreVeinsEnabled(),
			vanillaSettings.useLegacyRandomSource()
		);
		return RandomState.create(settings, noiseParameters, SEED).router().finalDensity();
	}

	private static NoiseRouter createUnseededRouter(DensityAlgorithm algorithm, float entranceProbability) {
		HolderLookup.Provider vanilla = VanillaRegistries.createLookup();
		HolderGetter<DensityFunction> densityFunctions = vanilla.lookupOrThrow(Registries.DENSITY_FUNCTION);
		HolderGetter<NormalNoise.NoiseParameters> noiseParameters = vanilla.lookupOrThrow(Registries.NOISE);
		Preset preset = Presets.makeRTFDefault();
		preset.caves().densityAlgorithm = algorithm;
		preset.caves().compatibilityMode = CompatibilityMode.RTF;
		preset.caves().entranceCaveProbability = entranceProbability;
		return RtfRouterAccess.create(preset, densityFunctions, noiseParameters);
	}

	private static int countCacheOnce(DensityFunction density) {
		AtomicInteger count = new AtomicInteger();
		density.mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof DensityFunctions.Marker marker && marker.type() == DensityFunctions.Marker.Type.CacheOnce) {
					count.incrementAndGet();
				}
				return function;
			}
		});
		return count.get();
	}

	private static List<DensityFunction.SinglePointContext> contexts(int size, int salt) {
		Random random = new Random(SEED ^ salt);
		List<DensityFunction.SinglePointContext> contexts = new ArrayList<>(size);
		for(int i = 0; i < size; i++) {
			int x = random.nextInt(-30_000_000, 30_000_001);
			int y = random.nextInt(-64, 321);
			int z = random.nextInt(-30_000_000, 30_000_001);
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

	private static final class RtfRouterAccess extends PresetNoiseRouterData {
		private static NoiseRouter create(Preset preset, HolderGetter<DensityFunction> densityFunctions, HolderGetter<NormalNoise.NoiseParameters> noiseParameters) {
			return overworld(preset, densityFunctions, noiseParameters, null);
		}
	}
}
