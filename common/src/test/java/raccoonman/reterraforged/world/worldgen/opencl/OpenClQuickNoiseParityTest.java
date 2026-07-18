package raccoonman.reterraforged.world.worldgen.opencl;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.config.OpenClConfig;
import raccoonman.reterraforged.world.worldgen.quicknoise.QuickNoiseNative;
import raccoonman.reterraforged.world.worldgen.quicknoise.QuickNoiseSeeds;

class OpenClQuickNoiseParityTest {
	@Test
	void fusedKernelMatchesCompleteNativeTilesExactly() {
		OpenClRuntime runtime = OpenClRuntime.open(new OpenClConfig(OpenClConfig.Mode.ON, 1, 1, false));
		Assumptions.assumeTrue(runtime != null, "No compatible OpenCL GPU is available");
		try(runtime) {
			long seed = -0x123456789ABCDEFL;
			int[] octaveSeeds = QuickNoiseSeeds.create(seed);
			int[][] tiles = {
				{ 0, 0, 0 },
				{ -3, -1, 5 },
				{ 17, 0, -11 }
			};
			for(int[] tile : tiles) {
				float[] expected = new float[QuickNoiseNative.TILE_SAMPLES];
				float[] actual = new float[QuickNoiseNative.TILE_SAMPLES];
				assertTrue(QuickNoiseNative.fillTile(seed, tile[0], tile[1], tile[2], 0.32F, 0.085F, 0.055F, expected));
				assertTrue(runtime.tryFillQuickNoiseTile(octaveSeeds, tile[0], tile[1], tile[2], 0.32F, 0.085F, 0.055F, actual));
				assertArrayEquals(expected, actual, "Mismatch in tile " + tile[0] + "," + tile[1] + "," + tile[2]);
			}
		}
	}

	@Test
	void benchmarkFusedTileWhenRequested() {
		Assumptions.assumeTrue(Boolean.getBoolean("rtf.quickNoise.benchmark"));
		OpenClRuntime runtime = OpenClRuntime.open(new OpenClConfig(OpenClConfig.Mode.ON, 1, 1, false));
		Assumptions.assumeTrue(runtime != null, "No compatible OpenCL GPU is available");
		try(runtime) {
			long seed = 0x5EED1234L;
			int[] octaveSeeds = QuickNoiseSeeds.create(seed);
			float[] output = new float[QuickNoiseNative.TILE_SAMPLES];
			for(int i = 0; i < 8; i++) {
				QuickNoiseNative.fillTile(seed, i, -1, 7, 0.32F, 0.085F, 0.055F, output);
				assertTrue(runtime.tryFillQuickNoiseTile(octaveSeeds, i, -1, 7, 0.32F, 0.085F, 0.055F, output));
			}

			int iterations = 64;
			long cpuStart = System.nanoTime();
			for(int i = 0; i < iterations; i++) {
				assertTrue(QuickNoiseNative.fillTile(seed, i, -1, 7, 0.32F, 0.085F, 0.055F, output));
			}
			long cpuNanos = System.nanoTime() - cpuStart;
			long gpuStart = System.nanoTime();
			for(int i = 0; i < iterations; i++) {
				assertTrue(runtime.tryFillQuickNoiseTile(octaveSeeds, i, -1, 7, 0.32F, 0.085F, 0.055F, output));
			}
			long gpuNanos = System.nanoTime() - gpuStart;
			System.out.printf(
				"RTF_QUICK_NOISE_BENCH tiles=%d cpu_ms_per_tile=%.4f gpu_ms_per_tile=%.4f%n",
				iterations,
				cpuNanos / 1_000_000.0D / iterations,
				gpuNanos / 1_000_000.0D / iterations
			);
		}
	}
}
