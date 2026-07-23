package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Random;
import java.util.function.IntToDoubleFunction;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.MapCodec;

import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.function.Interpolation;

class NoiseInvariantMetadataTest {
	@Test
	void mapPreservesOriginalScalarBoundsCallsAndBits() {
		CountingNoise alpha = new CountingNoise(-0.75F, 1.25F, Float::intBitsToFloat);
		CountingNoise from = new CountingNoise(-2.0F, 2.0F, seed -> seed * 0.25D);
		CountingNoise to = new CountingNoise(-4.0F, 4.0F, seed -> seed * -0.5D);
		Map map = new Map(alpha, from, to);
		assertEquals(0, alpha.minCalls);
		assertEquals(0, alpha.maxCalls);

		Random random = new Random(0x6D61705F626F756EL);
		for(int index = 0; index < 500_000; index++) {
			float x = Float.intBitsToFloat(random.nextInt());
			float z = Float.intBitsToFloat(random.nextInt());
			int seed = random.nextInt();
			float value = alpha.compute(x, z, seed);
			float factor = (value - -0.75F) / (1.25F - -0.75F);
			float min = from.compute(x, z, seed);
			float max = to.compute(x, z, seed);
			assertBitsEqual(min + factor * (max - min), map.compute(x, z, seed));
		}
		assertEquals(500_000, alpha.minCalls);
		assertEquals(500_000, alpha.maxCalls);
	}

	@Test
	void mapConstantBoundsPreserveOriginalScalarCallsAndBits() {
		CountingNoise alpha = new CountingNoise(-0.75F, 1.25F, Float::intBitsToFloat);
		Constant from = new Constant(-2.25F);
		Constant to = new Constant(4.5F);
		Map map = new Map(alpha, from, to);
		assertEquals(0, alpha.minCalls);
		assertEquals(0, alpha.maxCalls);

		Random random = new Random(0x6D61705F636F6E73L);
		for(int index = 0; index < 500_000; index++) {
			float x = Float.intBitsToFloat(random.nextInt());
			float z = Float.intBitsToFloat(random.nextInt());
			int seed = random.nextInt();
			float value = alpha.compute(x, z, seed);
			float factor = (value - -0.75F) / (1.25F - -0.75F);
			float min = from.compute(x, z, seed);
			float max = to.compute(x, z, seed);
			assertBitsEqual(min + factor * (max - min), map.compute(x, z, seed));
		}
		assertEquals(500_000, alpha.minCalls);
		assertEquals(500_000, alpha.maxCalls);
	}

	@Test
	void blendPreservesOriginalThresholdCallsAndBits() {
		CountingNoise alpha = new CountingNoise(-1.5F, 2.5F, Float::intBitsToFloat);
		CountingNoise lower = new CountingNoise(-2.0F, 0.5F, seed -> seed * 0.125D);
		CountingNoise upper = new CountingNoise(0.25F, 3.0F, seed -> seed * -0.25D);
		float position = 0.575F;
		float range = 0.8F;
		Blend blend = new Blend(alpha, lower, upper, position, range, Interpolation.CURVE3);
		assertEquals(0, alpha.minCalls);
		assertEquals(0, alpha.maxCalls);

		Random random = new Random(0x626C656E645F626FL);
		for(int index = 0; index < 500_000; index++) {
			float x = Float.intBitsToFloat(random.nextInt());
			float z = Float.intBitsToFloat(random.nextInt());
			int seed = random.nextInt();
			float expected = originalBlend(alpha, lower, upper, position, range, x, z, seed);
			assertBitsEqual(expected, blend.compute(x, z, seed));
		}
		assertEquals(3_000_000, alpha.minCalls);
		assertEquals(2_000_000, alpha.maxCalls);
	}

	private static float originalBlend(Noise alphaNoise, Noise lower, Noise upper, float position, float range, float x, float z, int seed) {
		float mid = alphaNoise.minValue() + (alphaNoise.maxValue() - alphaNoise.minValue()) * position;
		float blendLower = Math.max(alphaNoise.minValue(), mid - range / 2.0F);
		float blendUpper = Math.min(alphaNoise.maxValue(), mid + range / 2.0F);
		float blendRange = blendUpper - blendLower;
		float alpha = alphaNoise.compute(x, z, seed);
		if(alpha < blendLower) {
			return lower.compute(x, z, seed);
		}
		if(alpha > blendUpper) {
			return upper.compute(x, z, seed);
		}
		return NoiseUtil.lerp(lower.compute(x, z, seed), upper.compute(x, z, seed), Interpolation.CURVE3.apply((alpha - blendLower) / blendRange));
	}

	private static void assertBitsEqual(float expected, float actual) {
		assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
	}

	private static final class CountingNoise implements Noise {
		private final float min;
		private final float max;
		private final IntToDoubleFunction values;
		private int minCalls;
		private int maxCalls;

		private CountingNoise(float min, float max, IntToDoubleFunction values) {
			this.min = min;
			this.max = max;
			this.values = values;
		}

		@Override
		public float compute(float x, float z, int seed) {
			return (float) this.values.applyAsDouble(seed);
		}

		@Override
		public float minValue() {
			this.minCalls++;
			return this.min;
		}

		@Override
		public float maxValue() {
			this.maxCalls++;
			return this.max;
		}

		@Override
		public Noise mapAll(Visitor visitor) {
			return visitor.apply(this);
		}

		@Override
		public MapCodec<? extends Noise> codec() {
			throw new UnsupportedOperationException();
		}
	}
}
