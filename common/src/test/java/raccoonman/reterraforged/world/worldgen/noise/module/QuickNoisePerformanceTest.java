package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

class QuickNoisePerformanceTest {
	private static final int BENCHMARK_ROUNDS = 7;
	private static volatile float sink;

	@Test
	void quickV2RepresentativeGraphIsAtLeastOnePointFiveTimesFaster() {
		Assumptions.assumeTrue("1".equals(System.getenv("RTF_QUICK_V2_BENCHMARK")));
		Noise source = Noises.perlin(17, 480, 4);
		Noise gradient = Noises.gradient(source, 0.0F, 0.6F, 0.45F);
		Noise terrace = Noises.terrace(gradient, 0.9F, 0.15F, 0.35F, 0.4F, 6);
		Noise graph = Noises.advancedTerrace(terrace, 0.04F, 0.8F, 0.6F, 0.1F, 0.45F, 12, 2);

		for(int index = 0; index < 3; index++) {
			sampleLegacy(graph, 2);
			try(QuickNoiseRuntime.Engine engine = new QuickNoiseRuntime.Engine(); QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
				sampleQuick(graph, 2);
			}
		}

		long[] legacySamples = new long[BENCHMARK_ROUNDS];
		long[] quickSamples = new long[BENCHMARK_ROUNDS];
		float legacy = 0.0F;
		float quick = 0.0F;
		try(QuickNoiseRuntime.Engine engine = new QuickNoiseRuntime.Engine(); QuickNoiseRuntime.Scope ignored = QuickNoiseRuntime.bind(engine)) {
			QuickNoiseRuntime.prepareSample(4096, -4096);
			graph.computeRoot(4096.0F, -4096.0F, 0);
			for(int round = 0; round < BENCHMARK_ROUNDS; round++) {
				if((round & 1) == 0) {
					long start = System.nanoTime();
					legacy = sampleLegacy(graph, 8);
					legacySamples[round] = System.nanoTime() - start;
					start = System.nanoTime();
					quick = sampleQuick(graph, 8);
					quickSamples[round] = System.nanoTime() - start;
				} else {
					long start = System.nanoTime();
					quick = sampleQuick(graph, 8);
					quickSamples[round] = System.nanoTime() - start;
					start = System.nanoTime();
					legacy = sampleLegacy(graph, 8);
					legacySamples[round] = System.nanoTime() - start;
				}
			}
		}
		sink = legacy + quick;
		Arrays.sort(legacySamples);
		Arrays.sort(quickSamples);
		long legacyNanos = legacySamples[BENCHMARK_ROUNDS / 2];
		long quickNanos = quickSamples[BENCHMARK_ROUNDS / 2];
		double speedup = (double) legacyNanos / quickNanos;
		System.out.printf("RTF_QUICK_V2_BENCH legacy_ms=%.3f quick_ms=%.3f speedup=%.3fx legacy_range_ms=%.3f..%.3f quick_range_ms=%.3f..%.3f sink=%f%n", legacyNanos / 1_000_000.0D, quickNanos / 1_000_000.0D, speedup, legacySamples[0] / 1_000_000.0D, legacySamples[BENCHMARK_ROUNDS - 1] / 1_000_000.0D, quickSamples[0] / 1_000_000.0D, quickSamples[BENCHMARK_ROUNDS - 1] / 1_000_000.0D, sink);
		assertTrue(speedup >= 1.5D, () -> "QUICK_V2 median speedup was only " + speedup + "x");
	}

	private static float sampleLegacy(Noise noise, int regions) {
		float sum = 0.0F;
		for(int region = 0; region < regions; region++) {
			int originX = region * 256 - 512;
			int originZ = region * -256 + 256;
			for(int chunkZ = 0; chunkZ < 8; chunkZ++) {
				for(int chunkX = 0; chunkX < 8; chunkX++) {
					for(int dz = 0; dz < 16; dz++) {
						for(int dx = 0; dx < 16; dx++) {
							int x = originX + chunkX * 16 + dx;
							int z = originZ + chunkZ * 16 + dz;
							sum += noise.compute(x, z, 0);
						}
					}
				}
			}
		}
		return sum;
	}

	private static float sampleQuick(Noise noise, int regions) {
		float sum = 0.0F;
		for(int region = 0; region < regions; region++) {
			int originX = region * 256 - 512;
			int originZ = region * -256 + 256;
			for(int chunkZ = 0; chunkZ < 8; chunkZ++) {
				for(int chunkX = 0; chunkX < 8; chunkX++) {
					for(int dz = 0; dz < 16; dz++) {
						for(int dx = 0; dx < 16; dx++) {
							int x = originX + chunkX * 16 + dx;
							int z = originZ + chunkZ * 16 + dz;
							QuickNoiseRuntime.prepareSample(x, z);
							sum += noise.computeRoot(x, z, 0);
						}
					}
				}
			}
		}
		return sum;
	}
}
