package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.Predicate;

import raccoonman.reterraforged.concurrent.cache.Cache;
import raccoonman.reterraforged.concurrent.cache.ExpiringEntry;
import raccoonman.reterraforged.concurrent.cache.map.LongMap;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.TestContexts;
import raccoonman.reterraforged.world.worldgen.WorldFilters;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;

/** Shared pieces of the tile cache tests: a generator that follows a script, and a look inside the cache. */
final class TileCacheFixtures {
	static final int CHUNK_X = 640;
	static final int CHUNK_Z = 1280;

	private TileCacheFixtures() {}

	interface Script {
		CompletableFuture<Tile> generate(int tileX, int tileZ);
	}

	/** Counts the tiles it is asked for and answers each request the way the test says. */
	static final class Scripted extends TileGenerator {
		final AtomicInteger calls = new AtomicInteger();
		private final Script script;

		Scripted(GeneratorContext context, Script script) {
			super(context.generator.getHeightmap(), new WorldFilters(context), TestContexts.TILE_SIZE, 1, TestContexts.BATCH_COUNT);
			this.script = script;
		}

		@Override
		public CompletableFuture<Tile> generate(int tileX, int tileZ) {
			this.calls.incrementAndGet();
			return this.script.generate(tileX, tileZ);
		}
	}

	/** A map that lets a test hold an insert just before it happens. */
	static final class HeldInsertMap<T> implements LongMap<T> {
		private final LongMap<T> map;
		private final Runnable beforeInsert;

		HeldInsertMap(LongMap<T> map, Runnable beforeInsert) {
			this.map = map;
			this.beforeInsert = beforeInsert;
		}

		@Override
		public int size() {
			return this.map.size();
		}

		@Override
		public void clear() {
			this.map.clear();
		}

		@Override
		public void remove(long key) {
			this.map.remove(key);
		}

		@Override
		public void remove(long key, Consumer<T> ifPresent) {
			this.map.remove(key, ifPresent);
		}

		@Override
		public int removeIf(Predicate<T> predicate) {
			return this.map.removeIf(predicate);
		}

		@Override
		public void put(long key, T value) {
			this.map.put(key, value);
		}

		@Override
		public T get(long key) {
			return this.map.get(key);
		}

		@Override
		public T computeIfAbsent(long key, LongFunction<T> computer) {
			this.beforeInsert.run();
			return this.map.computeIfAbsent(key, computer);
		}
	}

	static Cache<?> cacheOf(TileCache tiles) throws Exception {
		return (Cache<?>) field(TileCache.class, "cache").get(tiles);
	}

	static void replaceCache(TileCache tiles, Cache<?> cache) throws Exception {
		field(TileCache.class, "cache").set(tiles, cache);
	}

	static int entries(TileCache tiles) throws Exception {
		return ((LongMap<?>) field(Cache.class, "map").get(cacheOf(tiles))).size();
	}

	static ExpiringEntry entryAt(TileCache tiles, int tileX, int tileZ) throws Exception {
		return cacheOf(tiles).get(PosUtil.pack(tileX, tileZ));
	}

	static Field field(Class<?> owner, String name) throws Exception {
		Field field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field;
	}

	static void await(BooleanSupplier condition, String what) throws InterruptedException {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
		while (!condition.getAsBoolean()) {
			assertTrue(System.nanoTime() < deadline, what);
			Thread.sleep(2);
		}
	}

	/** The number of tasks a pool has finished, read when it is idle. */
	static long settledCompleted(ThreadPoolExecutor pool) throws InterruptedException {
		// A worker counts a task as completed just after the task's future completes.
		await(() -> pool.getActiveCount() == 0 && pool.getQueue().isEmpty(), "pool did not settle");
		return pool.getCompletedTaskCount();
	}
}
