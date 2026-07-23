package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.mojang.serialization.MapCodec;

import raccoonman.reterraforged.concurrent.ThreadPools;

class NoiseRootRuntimeCarrierTest {
	private static final Noise DIRECT = new Noise() {
		@Override
		public float compute(float x, float z, int seed) {
			return x + z + seed;
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
			return visitor.apply(this);
		}

		@Override
		public MapCodec<? extends Noise> codec() {
			throw new UnsupportedOperationException();
		}
	};

	@Test
	void workerCarrierPreservesNestedRootScopes() throws Exception {
		float result = CompletableFuture.supplyAsync(() -> {
			assertInstanceOf(ThreadPools.WorkerThread.class, Thread.currentThread());
			assertEquals(6.0F, DIRECT.computeRoot(1.0F, 2.0F, 3));
			try(NoiseRootRuntime.Scope ignored = NoiseRootRuntime.bind(engine(40.0F))) {
				assertEquals(46.0F, DIRECT.computeRoot(1.0F, 2.0F, 3));
				try(NoiseRootRuntime.Scope direct = NoiseRootRuntime.bind(null)) {
					assertEquals(6.0F, DIRECT.computeRoot(1.0F, 2.0F, 3));
				}
				assertEquals(46.0F, DIRECT.computeRoot(1.0F, 2.0F, 3));
			}
			return DIRECT.computeRoot(1.0F, 2.0F, 3);
		}, ThreadPools.WORLD_GEN).get(30, TimeUnit.SECONDS);
		assertEquals(6.0F, result);
	}

	@Test
	void ordinaryThreadsRetainThreadLocalFallback() throws Exception {
		CompletableFuture<Float> result = new CompletableFuture<>();
		Thread thread = new Thread(() -> {
			try(NoiseRootRuntime.Scope ignored = NoiseRootRuntime.bind(engine(80.0F))) {
				result.complete(DIRECT.computeRoot(1.0F, 2.0F, 3));
			} catch(Throwable throwable) {
				result.completeExceptionally(throwable);
			}
		}, "rtf-root-runtime-test");
		thread.start();
		assertEquals(86.0F, result.get(30, TimeUnit.SECONDS));
		thread.join(30_000L);
	}

	private static NoiseRootRuntime.Engine engine(float offset) {
		return new NoiseRootRuntime.Engine() {
			@Override
			public NoiseRootRuntime.Session openSession() {
				return new NoiseRootRuntime.Session() {
					@Override
					public float computeRoot(Noise noise, float x, float z, int seed) {
						return noise.compute(x, z, seed) + offset;
					}

					@Override
					public void prepareSample(int sampleX, int sampleZ, float xScale, float xOffset, float zScale, float zOffset) {
					}

					@Override
					public AutoCloseable scaleCoordinates(float scale) {
						return null;
					}

					@Override
					public AutoCloseable legacyCoordinates() {
						return null;
					}
				};
			}

			@Override
			public void close() {
			}
		};
	}
}
