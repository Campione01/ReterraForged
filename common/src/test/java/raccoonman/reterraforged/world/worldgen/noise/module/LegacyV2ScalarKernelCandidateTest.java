package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.Arrays;
import java.util.Random;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.function.Interpolation;

class LegacyV2ScalarKernelCandidateTest {
	private static volatile float sink;

	@Test
	void reusedCoordinatePrimesPreserveOriginalRtfBits() {
		Random random = new Random(0x6C65676163795632L);
		for(Interpolation interpolation : Interpolation.values()) {
			for(int index = 0; index < 100_000; index++) {
				float x = (random.nextInt(4_000_001) - 2_000_000) / 64.0F;
				float z = (random.nextInt(4_000_001) - 2_000_000) / 64.0F;
				int seed = random.nextInt();
				assertEquals(Float.floatToRawIntBits(legacySample8(x, z, seed, interpolation)), Float.floatToRawIntBits(Perlin.sample(x, z, seed, interpolation)));
				assertEquals(Float.floatToRawIntBits(legacySample24(x, z, seed, interpolation)), Float.floatToRawIntBits(Perlin2.sample(x, z, seed, interpolation)));
			}
		}
	}

	@Test
	void primedCellHashesPreserveOriginalRtfVectors() {
		Random random = new Random(0x63656C6C5F707269L);
		for(int index = 0; index < 1_000_000; index++) {
			int x = random.nextInt();
			int z = random.nextInt();
			int seed = random.nextInt();
			assertSame(
				NoiseUtil.cell(seed, x, z),
				NoiseUtil.cellPrimed(seed, NoiseUtil.X_PRIME * x, NoiseUtil.Y_PRIME * z)
			);
		}
	}

	@Test
	void benchmarksReusedCoordinatePrimesWhenRequested() {
		Assumptions.assumeTrue(Boolean.parseBoolean(System.getenv("RTF_LEGACY_V2_SCALAR_BENCH")));
		int size = 1 << 16;
		float[] xs = new float[size];
		float[] zs = new float[size];
		Random random = new Random(0x717569636B6E6F69L);
		for(int index = 0; index < size; index++) {
			xs[index] = (random.nextInt(4_000_001) - 2_000_000) / 64.0F;
			zs[index] = (random.nextInt(4_000_001) - 2_000_000) / 64.0F;
		}
		for(int warmup = 0; warmup < 12; warmup++) {
			measureOriginal8(xs, zs, 4);
			measureCandidate8(xs, zs, 4);
			measureOriginal24(xs, zs, 4);
			measureCandidate24(xs, zs, 4);
		}
		long[] original8 = new long[9];
		long[] candidate8 = new long[9];
		long[] original24 = new long[9];
		long[] candidate24 = new long[9];
		for(int round = 0; round < original8.length; round++) {
			if((round & 1) == 0) {
				original8[round] = measureOriginal8(xs, zs, 16);
				candidate8[round] = measureCandidate8(xs, zs, 16);
				original24[round] = measureOriginal24(xs, zs, 16);
				candidate24[round] = measureCandidate24(xs, zs, 16);
			} else {
				candidate24[round] = measureCandidate24(xs, zs, 16);
				original24[round] = measureOriginal24(xs, zs, 16);
				candidate8[round] = measureCandidate8(xs, zs, 16);
				original8[round] = measureOriginal8(xs, zs, 16);
			}
		}
		Arrays.sort(original8);
		Arrays.sort(candidate8);
		Arrays.sort(original24);
		Arrays.sort(candidate24);
		long original8Median = original8[original8.length / 2];
		long candidate8Median = candidate8[candidate8.length / 2];
		long original24Median = original24[original24.length / 2];
		long candidate24Median = candidate24[candidate24.length / 2];
		System.out.printf("RTF_LEGACY_V2_SCALAR_CANDIDATE type=perlin8 original_ms=%.3f candidate_ms=%.3f speedup=%.3fx%n",
			original8Median / 1_000_000.0D, candidate8Median / 1_000_000.0D, (double) original8Median / candidate8Median);
		System.out.printf("RTF_LEGACY_V2_SCALAR_CANDIDATE type=perlin24 original_ms=%.3f candidate_ms=%.3f speedup=%.3fx%n",
			original24Median / 1_000_000.0D, candidate24Median / 1_000_000.0D, (double) original24Median / candidate24Median);
	}

	private static long measureOriginal8(float[] xs, float[] zs, int repetitions) {
		long start = System.nanoTime();
		float value = 0.0F;
		for(int repetition = 0; repetition < repetitions; repetition++) {
			for(int index = 0; index < xs.length; index++) {
				value += legacySample8(xs[index], zs[index], index + repetition, Interpolation.CURVE3);
			}
		}
		sink = value;
		return System.nanoTime() - start;
	}

	private static long measureCandidate8(float[] xs, float[] zs, int repetitions) {
		long start = System.nanoTime();
		float value = 0.0F;
		for(int repetition = 0; repetition < repetitions; repetition++) {
			for(int index = 0; index < xs.length; index++) {
				value += Perlin.sample(xs[index], zs[index], index + repetition, Interpolation.CURVE3);
			}
		}
		sink = value;
		return System.nanoTime() - start;
	}

	private static long measureOriginal24(float[] xs, float[] zs, int repetitions) {
		long start = System.nanoTime();
		float value = 0.0F;
		for(int repetition = 0; repetition < repetitions; repetition++) {
			for(int index = 0; index < xs.length; index++) {
				value += legacySample24(xs[index], zs[index], index + repetition, Interpolation.CURVE3);
			}
		}
		sink = value;
		return System.nanoTime() - start;
	}

	private static long measureCandidate24(float[] xs, float[] zs, int repetitions) {
		long start = System.nanoTime();
		float value = 0.0F;
		for(int repetition = 0; repetition < repetitions; repetition++) {
			for(int index = 0; index < xs.length; index++) {
				value += Perlin2.sample(xs[index], zs[index], index + repetition, Interpolation.CURVE3);
			}
		}
		sink = value;
		return System.nanoTime() - start;
	}

	private static float legacySample8(float x, float y, int seed, Interpolation interpolation) {
		int x0 = NoiseUtil.floor(x);
		int y0 = NoiseUtil.floor(y);
		int x1 = x0 + 1;
		int y1 = y0 + 1;
		float xs = interpolation.apply(x - x0);
		float ys = interpolation.apply(y - y0);
		float xd0 = x - x0;
		float yd0 = y - y0;
		float xd1 = xd0 - 1.0F;
		float yd1 = yd0 - 1.0F;
		float xf0 = NoiseUtil.lerp(NoiseUtil.gradCoord2D(seed, x0, y0, xd0, yd0), NoiseUtil.gradCoord2D(seed, x1, y0, xd1, yd0), xs);
		float xf1 = NoiseUtil.lerp(NoiseUtil.gradCoord2D(seed, x0, y1, xd0, yd1), NoiseUtil.gradCoord2D(seed, x1, y1, xd1, yd1), xs);
		return NoiseUtil.lerp(xf0, xf1, ys);
	}

	private static float legacySample24(float x, float y, int seed, Interpolation interpolation) {
		int x0 = NoiseUtil.floor(x);
		int y0 = NoiseUtil.floor(y);
		int x1 = x0 + 1;
		int y1 = y0 + 1;
		float xs = interpolation.apply(x - x0);
		float ys = interpolation.apply(y - y0);
		float xd0 = x - x0;
		float yd0 = y - y0;
		float xd1 = xd0 - 1.0F;
		float yd1 = yd0 - 1.0F;
		float xf0 = NoiseUtil.lerp(NoiseUtil.gradCoord2D_24(seed, x0, y0, xd0, yd0), NoiseUtil.gradCoord2D_24(seed, x1, y0, xd1, yd0), xs);
		float xf1 = NoiseUtil.lerp(NoiseUtil.gradCoord2D_24(seed, x0, y1, xd0, yd1), NoiseUtil.gradCoord2D_24(seed, x1, y1, xd1, yd1), xs);
		return NoiseUtil.lerp(xf0, xf1, ys);
	}

}
