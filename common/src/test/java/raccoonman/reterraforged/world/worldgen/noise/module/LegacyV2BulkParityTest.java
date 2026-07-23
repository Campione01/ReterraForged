package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.MapCodec;

class LegacyV2BulkParityTest {
	private static final int[] SEEDS = {0, 1, -1, 0x5EED_2171, Integer.MIN_VALUE, Integer.MAX_VALUE};
	private static final Transform[] TRANSFORMS = {
		new Transform(1.0F, 0.0F, 1.0F, 0.0F),
		new Transform(0.125F, 0.375F, 0.25F, -0.625F),
		new Transform(0.37F, 11.125F, 1.41F, -7.875F),
		new Transform(-0.5F, 3.25F, -0.75F, -5.5F)
	};

	@Test
	void originalNodeBulkImplementationsAreBitExact() {
		Noise perlin = Noises.perlin(17, 480, 4, 2.35F, 0.61F);
		Noise perlin2 = Noises.perlin2(29, 360, 3, 1.85F, 0.47F);
		Noise simplex = Noises.simplex(53, 520, 4, 2.1F, 0.55F);
		Noise composite = Noises.frequency(
			Noises.clamp(
				Noises.map(Noises.add(perlin, Noises.mul(perlin2, simplex)), -0.35F, 1.65F),
				-0.2F,
				1.3F
			),
			Noises.map(simplex, 0.65F, 1.35F),
			Noises.map(perlin, 0.8F, 1.2F)
		);
		List<Noise> cases = List.of(
			perlin,
			perlin2,
			simplex,
			Noises.simplex2(67, 280, 3, 1.9F, 0.48F),
			Noises.perlinRidge(41, 410, 4, 2.35F, 1.15F),
			Noises.simplexRidge(79, 390, 4, 2.2F, 1.05F),
			Noises.billow(83, 330, 4, 2.05F, 0.92F),
			Noises.cubic(97, 260, 3, 2.0F, 0.5F),
			Noises.white(101, 48),
			Noises.powCurve(Noises.abs(Noises.add(perlin, -0.8F)), 2.35F),
			Noises.alpha(Noises.max(perlin, perlin2), Noises.min(simplex, 0.7F)),
			Noises.shiftSeed(composite, 73)
		);

		for(Noise noise : cases) {
			assertTrue(noise.supportsBulk(), noise.getClass().getName());
			for(Transform transform : TRANSFORMS) {
				NoiseBatch batch = NoiseBatch.regular(-37, 19, 17, 11, transform.xScale, transform.xOffset, transform.zScale, transform.zOffset);
				float[] actual = new float[batch.size()];
				for(int seed : SEEDS) {
					noise.fill(batch, seed, actual);
					for(int index = 0; index < actual.length; index++) {
						float expected = noise.compute(batch.xAt(index), batch.zAt(index), seed);
						assertEquals(
							Float.floatToRawIntBits(expected),
							Float.floatToRawIntBits(actual[index]),
							noise.getClass().getSimpleName() + " seed=" + seed + " index=" + index
						);
					}
				}
			}
		}
	}

	@Test
	void rootRuntimeUsesOriginalBulkPathAndPreservesAffineCoordinates() {
		Noise noise = Noises.frequency(
			Noises.add(Noises.perlin(17, 320, 4), Noises.simplex(29, 420, 3)),
			0.37F,
			1.41F
		);
		Transform transform = TRANSFORMS[2];
		try(LegacyV2Runtime.Engine engine = LegacyV2Runtime.Engine.forBatch(32);
			NoiseRootRuntime.Scope ignored = NoiseRootRuntime.bind(engine)) {
			for(int z = -32; z < 32; z++) {
				for(int x = 16; x < 80; x++) {
					NoiseRootRuntime.prepareSample(x, z, transform.xScale, transform.xOffset, transform.zScale, transform.zOffset);
					float worldX = x * transform.xScale + transform.xOffset;
					float worldZ = z * transform.zScale + transform.zOffset;
					float expected = noise.compute(worldX, worldZ, 0x5EED_2171);
					float actual = noise.computeRoot(worldX, worldZ, 0x5EED_2171);
					assertEquals(Float.floatToRawIntBits(expected), Float.floatToRawIntBits(actual));
				}
			}
			LegacyV2Runtime.Snapshot snapshot = engine.snapshot();
			assertEquals(1, snapshot.roots());
			assertEquals(4096, snapshot.sampleCalls());
			assertTrue(snapshot.tileFills() > 0);
			assertEquals(0, snapshot.fallbackCalls());
		}
	}

	@Test
	void thirdPartyAndStatefulCacheNodesRemainScalarByDefault() {
		Noise external = new Noise() {
			@Override
			public float compute(float x, float z, int seed) {
				return x - z + seed;
			}

			@Override
			public float minValue() {
				return Float.NEGATIVE_INFINITY;
			}

			@Override
			public float maxValue() {
				return Float.POSITIVE_INFINITY;
			}

			@Override
			public Noise mapAll(Visitor visitor) {
				return this;
			}

			@Override
			public MapCodec<? extends Noise> codec() {
				return null;
			}
		};
		Noise cached = Noises.cache2d(Noises.perlin(3, 120, 3));
		assertFalse(external.supportsBulk());
		assertFalse(cached.supportsBulk());

		try(LegacyV2Runtime.Engine engine = LegacyV2Runtime.Engine.forBatch(32);
			NoiseRootRuntime.Scope ignored = NoiseRootRuntime.bind(engine)) {
			NoiseRootRuntime.prepareSample(4, 8);
			assertEquals(external.compute(4.0F, 8.0F, 7), external.computeRoot(4.0F, 8.0F, 7));
			assertEquals(cached.compute(4.0F, 8.0F, 7), cached.computeRoot(4.0F, 8.0F, 7));
			assertEquals(0, engine.snapshot().roots());
			assertEquals(2, engine.snapshot().fallbackCalls());
		}
	}

	private record Transform(float xScale, float xOffset, float zScale, float zOffset) {
	}
}
