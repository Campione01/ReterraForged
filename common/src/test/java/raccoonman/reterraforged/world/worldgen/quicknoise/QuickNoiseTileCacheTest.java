package raccoonman.reterraforged.world.worldgen.quicknoise;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

class QuickNoiseTileCacheTest {
	private static final float CHAMBER_BIAS = 0.32F;
	private static final float SPAGHETTI_WIDTH = 0.085F;
	private static final float NOODLE_WIDTH = 0.055F;

	@Test
	void mapsNegativeBlockCoordinatesToTheCanonicalTileIndex() {
		QuickNoiseTileCache cache = new QuickNoiseTileCache(1234L, CHAMBER_BIAS, SPAGHETTI_WIDTH, NOODLE_WIDTH);
		float[] tile = new float[QuickNoiseNative.TILE_SAMPLES];
		assertTrue(QuickNoiseNative.fillTile(1234L, -1, -1, -1, CHAMBER_BIAS, SPAGHETTI_WIDTH, NOODLE_WIDTH, tile));

		int last = QuickNoiseNative.TILE_SIZE - 1;
		int expectedIndex = (last * QuickNoiseNative.TILE_SIZE + last) * QuickNoiseNative.TILE_SIZE + last;
		assertEquals(tile[expectedIndex], cache.sample(-1, -1, -1));
		assertEquals(1, cache.cachedTileCount());
	}

	@Test
	void evictsOldTilesWithinTheConfiguredBound() {
		QuickNoiseTileCache cache = new QuickNoiseTileCache(9L, CHAMBER_BIAS, SPAGHETTI_WIDTH, NOODLE_WIDTH, 2);
		int tileWidthInBlocks = QuickNoiseNative.TILE_SIZE * QuickNoiseTileCache.NOISE_CELL_WIDTH;
		cache.sample(0, 0, 0);
		cache.sample(tileWidthInBlocks, 0, 0);
		cache.sample(tileWidthInBlocks * 2, 0, 0);

		assertTrue(cache.cachedTileCount() <= 2);
		assertEquals(3, cache.generatedTileCount());
		assertTrue(cache.evictedTileCount() >= 1);
	}

	@Test
	void concurrentMissesReturnTheSameDeterministicValueWithoutAnExecutor() throws Exception {
		QuickNoiseTileCache cache = new QuickNoiseTileCache(77L, CHAMBER_BIAS, SPAGHETTI_WIDTH, NOODLE_WIDTH);
		int workers = 8;
		CountDownLatch ready = new CountDownLatch(workers);
		CountDownLatch start = new CountDownLatch(1);
		List<Float> results = new ArrayList<>();
		List<Thread> threads = new ArrayList<>();
		for(int i = 0; i < workers; i++) {
			Thread thread = new Thread(() -> {
				ready.countDown();
				try {
					start.await();
					synchronized(results) {
						results.add(cache.sample(320, -32, -448));
					}
				} catch(InterruptedException exception) {
					Thread.currentThread().interrupt();
					throw new AssertionError(exception);
				}
			});
			threads.add(thread);
			thread.start();
		}

		assertTrue(ready.await(5, TimeUnit.SECONDS));
		start.countDown();
		for(Thread thread : threads) {
			thread.join(TimeUnit.SECONDS.toMillis(5));
			assertTrue(!thread.isAlive());
		}

		assertEquals(workers, results.size());
		for(float value : results) {
			assertEquals(results.getFirst(), value);
		}
		assertEquals(1, cache.cachedTileCount());
	}
}
