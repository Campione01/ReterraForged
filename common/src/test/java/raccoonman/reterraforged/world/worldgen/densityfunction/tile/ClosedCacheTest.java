package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import raccoonman.reterraforged.concurrent.ThreadPools;
import raccoonman.reterraforged.concurrent.cache.Cache;
import raccoonman.reterraforged.concurrent.cache.CacheEntry;
import raccoonman.reterraforged.concurrent.cache.CacheManager;
import raccoonman.reterraforged.concurrent.cache.map.LongMap;
import raccoonman.reterraforged.concurrent.cache.map.StampedBoundLongMap;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.TestContexts;
import raccoonman.reterraforged.world.worldgen.WorldErosion;
import raccoonman.reterraforged.world.worldgen.WorldFilters;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.RiverCache;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.TileCacheFixtures.HeldInsertMap;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.TileCacheFixtures.Scripted;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator;

/** A closed context still answers, but it must cost little and keep nothing for the level that is gone. */
class ClosedCacheTest {
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
			Thread thread = new Thread(task, "closed-cache-test");
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
	void aClosedContextStartsNoQueuedTileAndSharesOneTileBetweenRepeatedLookups() throws Exception {
		GeneratorContext context = TestContexts.cached(42);
		ThreadPoolExecutor shared = ThreadPools.WORLD_GEN;
		int batches = TestContexts.BATCH_COUNT * TestContexts.BATCH_COUNT;
		context.close();

		long before = TileCacheFixtures.settledCompleted(shared);
		Tile first = null;
		for (int i = 0; i < 10; i++) {
			long beforeQueue = TileCacheFixtures.settledCompleted(shared);
			context.cache.queueAtChunk(CHUNK_X, CHUNK_Z);
			assertEquals(beforeQueue, TileCacheFixtures.settledCompleted(shared), "a closed cache starts no tile that nobody waits for");
			Tile late = context.cache.provideAtChunk(CHUNK_X, CHUNK_Z);
			assertNotNull(late);
			if (first == null) {
				first = late;
			}
			assertSame(first, late, "repeated lookups of one tile share it");
		}
		assertEquals(before + batches, TileCacheFixtures.settledCompleted(shared), "ten lookups of one tile cost one tile");
		assertEquals(0, TileCacheFixtures.entries(context.cache), "the closed cache itself stays empty");
		assertNull(context.cache.provideAtChunkIfPresent(CHUNK_X, CHUNK_Z));
	}

	@Test
	void anInsertThatRacesWithCloseDoesNotSurvive() throws Exception {
		Scripted generator = new Scripted(source, (x, z) -> CompletableFuture.completedFuture(tile));
		TileCache tiles = new TileCache(TestContexts.TILE_SIZE, true, generator);
		Cache<?> original = TileCacheFixtures.cacheOf(tiles);
		original.close();
		CacheManager.remove(original);

		CountDownLatch reached = new CountDownLatch(1);
		CountDownLatch proceed = new CountDownLatch(1);
		LongMap<CacheEntry<?>> held = new HeldInsertMap<>(new StampedBoundLongMap<>(256), () -> {
			reached.countDown();
			try {
				proceed.await(60, TimeUnit.SECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		});
		TileCacheFixtures.replaceCache(tiles, new Cache<>(256, 60L, 20L, TimeUnit.SECONDS, (capacity) -> held));
		try {
			// The lookup has seen an open cache and is about to insert when the cache closes.
			Future<Tile> lookup = threads.submit(() -> tiles.provide(7, 9));
			assertTrue(reached.await(60, TimeUnit.SECONDS), "the lookup reached its insert");
			tiles.close();
			proceed.countDown();
			assertSame(tile, lookup.get(60, TimeUnit.SECONDS), "the caller still gets its tile");
			assertEquals(0, held.size(), "nothing is left in a cache that is no longer polled");
		} finally {
			proceed.countDown();
			tiles.close();
		}
	}

	@Test
	void closingUnderConcurrentLookupsLeavesTheCacheEmpty() throws Exception {
		Scripted generator = new Scripted(source, (x, z) -> CompletableFuture.completedFuture(tile));
		for (int round = 0; round < 40; round++) {
			TileCache tiles = new TileCache(TestContexts.TILE_SIZE, true, generator);
			AtomicBoolean stop = new AtomicBoolean();
			AtomicInteger next = new AtomicInteger();
			int row = round;
			List<Future<?>> lookups = new ArrayList<>();
			for (int n = 0; n < 4; n++) {
				lookups.add(threads.submit(() -> {
					while (!stop.get()) {
						assertSame(tile, tiles.provide(next.getAndIncrement(), row));
					}
				}));
			}
			TileCacheFixtures.await(() -> next.get() > 200, "lookups did not start");
			tiles.close();
			stop.set(true);
			for (Future<?> lookup : lookups) {
				lookup.get(60, TimeUnit.SECONDS);
			}
			assertEquals(0, TileCacheFixtures.entries(tiles), "round " + round + ": an entry survived the close");
		}
	}

	@Test
	void aLookupInFlightWhenTheCacheClosesStillGetsItsTile() throws Exception {
		CompletableFuture<Tile> gate = new CompletableFuture<>();
		Scripted generator = new Scripted(source, (x, z) -> gate);
		TileCache tiles = new TileCache(TestContexts.TILE_SIZE, true, generator);
		try {
			Future<Tile> lookup = threads.submit(() -> tiles.provide(3, 4));
			TileCacheFixtures.await(() -> generator.calls.get() == 1, "the tile was not requested");
			assertEquals(1, TileCacheFixtures.entries(tiles));
			tiles.close();
			assertEquals(0, TileCacheFixtures.entries(tiles), "close empties the cache, tiles in flight included");
			gate.complete(tile);
			assertSame(tile, lookup.get(60, TimeUnit.SECONDS), "the waiting caller still gets its tile");
			assertEquals(0, TileCacheFixtures.entries(tiles), "the finished tile does not come back");
		} finally {
			gate.complete(tile);
			tiles.close();
		}
	}

	@Test
	void aClosedContextKeepsNoRiverMapsOrErosionTablesForGood() throws Exception {
		GeneratorContext context = TestContexts.cached(42);
		try {
			context.cache.provideAtChunk(CHUNK_X, CHUNK_Z);
			assertTrue(riverMaps(context) > 0, "an open context caches its river maps");
			assertNotNull(erosionTables(context), "an open context keeps its erosion tables");

			context.close();
			assertEquals(0, riverMaps(context));
			assertNull(erosionTables(context));

			for (int i = 1; i <= 3; i++) {
				assertNotNull(context.cache.provideAtChunk(CHUNK_X + i * 4096, CHUNK_Z));
			}
			assertEquals(0, riverMaps(context), "the cache that is no longer polled stays empty");
			int late = lateRiverMaps(context);
			assertTrue(late >= 1 && late <= 8, "late river maps are few and bounded: " + late);
			assertNull(erosionTables(context), "late erosion tables are not kept for good");
		} finally {
			context.close();
		}
	}

	private static RiverCache riverCacheOf(GeneratorContext context) throws Exception {
		Object continent = context.generator.getHeightmap().continent();
		for (Class<?> type = continent.getClass(); type != null; type = type.getSuperclass()) {
			for (var field : type.getDeclaredFields()) {
				if (RiverCache.class.isAssignableFrom(field.getType())) {
					field.setAccessible(true);
					return (RiverCache) field.get(continent);
				}
			}
		}
		throw new AssertionError("no river cache in " + continent.getClass());
	}

	private static int riverMaps(GeneratorContext context) throws Exception {
		Object cache = TileCacheFixtures.field(RiverCache.class, "cache").get(riverCacheOf(context));
		return ((LongMap<?>) TileCacheFixtures.field(Cache.class, "map").get(cache)).size();
	}

	private static int lateRiverMaps(GeneratorContext context) throws Exception {
		Object late = TileCacheFixtures.field(RiverCache.class, "late").get(riverCacheOf(context));
		return late == null ? -1 : ((LongMap<?>) late).size();
	}

	private static Object erosionTables(GeneratorContext context) throws Exception {
		Object filters = TileCacheFixtures.field(TileGenerator.class, "filters").get(context.generator);
		Object erosion = TileCacheFixtures.field(WorldFilters.class, "erosion").get(filters);
		return TileCacheFixtures.field(WorldErosion.class, "value").get(erosion);
	}
}
