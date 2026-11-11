package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import raccoonman.reterraforged.concurrent.ThreadPools;
import raccoonman.reterraforged.concurrent.cache.Cache;
import raccoonman.reterraforged.concurrent.cache.ExpiringEntry;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.TestContexts;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.TileCacheFixtures.Scripted;

/** One tile per key, whatever the callers do: together, after a failure, while it is submitted, expired or evicted. */
class TileSingleFlightTest {
	private static final int CHUNK_X = TileCacheFixtures.CHUNK_X;
	private static final int CHUNK_Z = TileCacheFixtures.CHUNK_Z;
	private static GeneratorContext source;
	private static Tile tile;
	private static ExecutorService threads;

	@BeforeAll
	static void prepare() {
		TestContexts.bootstrapMinecraft();
		source = TestContexts.cached(42);
		tile = source.cache.provideAtChunk(CHUNK_X, CHUNK_Z);
		threads = Executors.newCachedThreadPool((task) -> {
			Thread thread = new Thread(task, "single-flight-test");
			thread.setDaemon(true);
			return thread;
		});
	}

	@AfterAll
	static void release() {
		threads.shutdownNow();
		source.close();
	}

	@Test
	void manyThreadsAskingForOneTileGenerateItOnce() throws Exception {
		CompletableFuture<Tile> gate = new CompletableFuture<>();
		Scripted generator = new Scripted(source, (x, z) -> gate);
		TileCache tiles = new TileCache(TestContexts.TILE_SIZE, true, generator);
		try {
			CountDownLatch start = new CountDownLatch(1);
			List<Future<Tile>> lookups = new ArrayList<>();
			for (int n = 0; n < 16; n++) {
				lookups.add(threads.submit(() -> {
					start.await();
					return tiles.provide(9, 9);
				}));
			}
			start.countDown();
			TileCacheFixtures.await(() -> generator.calls.get() >= 1, "the tile was not requested");
			Thread.sleep(200);
			assertEquals(1, generator.calls.get(), "sixteen callers, one generation");
			gate.complete(tile);
			for (Future<Tile> lookup : lookups) {
				assertSame(tile, lookup.get(60, TimeUnit.SECONDS));
			}
			assertEquals(1, generator.calls.get());
			assertEquals(1, TileCacheFixtures.entries(tiles));
		} finally {
			gate.complete(tile);
			tiles.close();
		}
	}

	@Test
	void aFailureReachesEveryWaiterAndIsNotRepeatedForEveryRequest() throws Exception {
		CompletableFuture<Tile> gate = new CompletableFuture<>();
		Scripted generator = new Scripted(source, (x, z) -> gate);
		TileCache tiles = new TileCache(TestContexts.TILE_SIZE, true, generator);
		tiles.failureRetryMillis = TimeUnit.MINUTES.toMillis(10);
		try {
			List<Future<Tile>> waiters = new ArrayList<>();
			for (int n = 0; n < 8; n++) {
				waiters.add(threads.submit(() -> tiles.provide(1, 1)));
			}
			TileCacheFixtures.await(() -> generator.calls.get() >= 1, "the tile was not requested");
			Thread.sleep(200);
			gate.completeExceptionally(new IllegalStateException("generation fails"));
			for (Future<Tile> waiter : waiters) {
				ExecutionException failure = assertThrows(ExecutionException.class, () -> waiter.get(60, TimeUnit.SECONDS));
				assertInstanceOf(IllegalStateException.class, failure.getCause());
				assertEquals("generation fails", failure.getCause().getMessage());
			}
			for (int i = 0; i < 100; i++) {
				assertThrows(IllegalStateException.class, () -> tiles.provide(1, 1));
			}
			assertEquals(1, generator.calls.get(), "a tile that failed is not generated again for every request");
			assertNull(tiles.provideIfPresent(1, 1), "a failed tile is not a present tile");
			assertDoesNotThrow(() -> tiles.drop(1, 1));
		} finally {
			gate.complete(tile);
			tiles.close();
		}
	}

	@Test
	void aTileStillBeingGeneratedDoesNotExpire() throws Exception {
		CompletableFuture<Tile> gate = new CompletableFuture<>();
		Scripted generator = new Scripted(source, (x, z) -> gate);
		TileCache tiles = new TileCache(TestContexts.TILE_SIZE, true, generator);
		try {
			Future<Tile> lookup = threads.submit(() -> tiles.provide(5, 6));
			TileCacheFixtures.await(() -> generator.calls.get() == 1, "the tile was not requested");
			// From here on every entry that can expire is an hour overdue.
			Cache<?> cache = TileCacheFixtures.cacheOf(tiles);
			TileCacheFixtures.field(Cache.class, "lifetimeMS").setLong(cache, -TimeUnit.HOURS.toMillis(1));
			cache.poll();
			assertNotNull(TileCacheFixtures.entryAt(tiles, 5, 6), "a tile in flight is not expired under its waiters");

			long ready = System.currentTimeMillis();
			gate.complete(tile);
			assertSame(tile, lookup.get(60, TimeUnit.SECONDS));
			ExpiringEntry entry = TileCacheFixtures.entryAt(tiles, 5, 6);
			assertNotNull(entry);
			assertTrue(entry.getTimestamp() >= ready, "a finished tile counts from the moment it was ready");
			cache.poll();
			assertNull(TileCacheFixtures.entryAt(tiles, 5, 6), "a finished tile expires as before");
		} finally {
			gate.complete(tile);
			tiles.close();
		}
	}

	@Test
	void aTileEvictedWhileInFlightStillReachesItsWaiter() throws Exception {
		CompletableFuture<Tile> gate = new CompletableFuture<>();
		Scripted generator = new Scripted(source, (x, z) -> x == 1000 ? gate : CompletableFuture.completedFuture(tile));
		TileCache tiles = new TileCache(TestContexts.TILE_SIZE, true, generator);
		try {
			Future<Tile> lookup = threads.submit(() -> tiles.provide(1000, 1000));
			TileCacheFixtures.await(() -> generator.calls.get() == 1, "the tile was not requested");
			for (int x = 0; x < 256; x++) {
				assertSame(tile, tiles.provide(x, 0));
			}
			assertNull(TileCacheFixtures.entryAt(tiles, 1000, 1000), "the oldest entry made room");
			gate.complete(tile);
			assertSame(tile, lookup.get(60, TimeUnit.SECONDS), "its waiter still gets the tile");
			assertNull(TileCacheFixtures.entryAt(tiles, 1000, 1000), "an evicted tile does not come back");
			assertEquals(256, TileCacheFixtures.entries(tiles));
		} finally {
			gate.complete(tile);
			tiles.close();
		}
	}

	@Test
	void anotherTileIsServedWhileOneIsBeingSubmitted() throws Exception {
		CountDownLatch entered = new CountDownLatch(1);
		CountDownLatch leave = new CountDownLatch(1);
		Scripted generator = new Scripted(source, (x, z) -> {
			if (x == 1) {
				entered.countDown();
				try {
					leave.await(120, TimeUnit.SECONDS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
			return CompletableFuture.completedFuture(tile);
		});
		TileCache tiles = new TileCache(TestContexts.TILE_SIZE, true, generator);
		try {
			Future<Tile> slow = threads.submit(() -> tiles.provide(1, 1));
			assertTrue(entered.await(60, TimeUnit.SECONDS), "the slow tile is being submitted");
			Future<Tile> other = threads.submit(() -> tiles.provide(2, 2));
			assertSame(tile, other.get(30, TimeUnit.SECONDS), "a lookup of another tile does not wait for the submission");
			leave.countDown();
			assertSame(tile, slow.get(60, TimeUnit.SECONDS));
		} finally {
			leave.countDown();
			tiles.close();
		}
	}

	@Test
	void aRejectedSubmissionFailsItsCallerAndIsTriedAgain() throws Exception {
		GeneratorContext context = TestContexts.cached(42);
		context.cache.failureRetryMillis = 1L;
		try {
			context.generator.setExecutor((task) -> {
				throw new RejectedExecutionException("the pool is stopped");
			});
			assertThrows(RejectedExecutionException.class, () -> context.cache.provideAtChunk(CHUNK_X, CHUNK_Z));

			context.generator.setExecutor(ThreadPools.WORLD_GEN);
			Thread.sleep(20);
			Future<Tile> retry = threads.submit(() -> context.cache.provideAtChunk(CHUNK_X, CHUNK_Z));
			assertNotNull(retry.get(60, TimeUnit.SECONDS), "the tile is generated once the pool takes work again");
		} finally {
			context.close();
		}
	}
}
