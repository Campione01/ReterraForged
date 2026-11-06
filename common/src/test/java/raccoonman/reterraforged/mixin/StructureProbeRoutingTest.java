package raccoonman.reterraforged.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.DensityFunction;
import org.junit.jupiter.api.Test;
import raccoonman.reterraforged.concurrent.cache.Cache;
import raccoonman.reterraforged.concurrent.cache.CacheEntry;
import raccoonman.reterraforged.concurrent.cache.map.LongMap;
import raccoonman.reterraforged.concurrent.cache.map.StampedBoundLongMap;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.TileCache;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.structure.StructureProbe;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;
import sun.misc.Unsafe;

class StructureProbeRoutingTest {

	@Test
	void bypassesOnlyAnUncachedSingleColumnWithinThisThreadsProbe() throws Exception {
		var tiles = tileCache(TileCache.class);
		var state = state(tiles);
		assertFalse(StructureProbe.bypass(state, 1, 1000, 2000));
		StructureProbe.enter();
		try {
			assertFalse(StructureProbe.bypass(state, 4, 1000, 2000));
			assertFalse(StructureProbe.bypass(state, 0, 1000, 2000));
			assertFalse(StructureProbe.bypass(new Object(), 1, 1000, 2000));
			assertFalse(StructureProbe.bypass(new State(null), 1, 1000, 2000));
			assertFalse(StructureProbe.bypass(state(null), 1, 1000, 2000));
			assertTrue(StructureProbe.bypass(state, 1, 1000, 2000));

			// The queried negative column and the positive edge each use their containing tile.
			put(tiles, -1, -2, CompletableFuture.completedFuture(tileEntry()));
			assertFalse(StructureProbe.bypass(state, 1, -1, -17));
			assertTrue(StructureProbe.bypass(state, 1, 0, -17));
			put(tiles, 62, 125, CompletableFuture.completedFuture(tileEntry()));
			assertFalse(StructureProbe.bypass(state, 1, 63 << 4, 2000));
			assertTrue(StructureProbe.bypass(state, 1, 64 << 4, 2000));

			StructureProbe.enter();
			try {
				assertTrue(StructureProbe.bypass(state, 1, 64 << 4, 2000));
			} finally {
				StructureProbe.exit();
			}
			assertTrue(StructureProbe.bypass(state, 1, 64 << 4, 2000));
			try(var executor = Executors.newSingleThreadExecutor()) {
				assertFalse(executor.submit(() -> StructureProbe.bypass(state, 1, 64 << 4, 2000)).get(5, TimeUnit.SECONDS));
			}
		} finally {
			StructureProbe.exit();
		}
		StructureProbe.exit();
		assertFalse(StructureProbe.bypass(state, 1, 64 << 4, 2000));
	}

	@Test
	void waitsForAnInProgressTileAndKeepsTheNormalRoute() throws Exception {
		var tiles = tileCache(TileCache.class);
		var state = state(tiles);
		var started = new CountDownLatch(1);
		var pending = new CompletableFuture<Object>() {
			@Override
			public Object join() {
				started.countDown();
				return super.join();
			}
		};
		Object tile = tileEntry();
		put(tiles, 62, 125, pending);
		try(var executor = Executors.newSingleThreadExecutor()) {
			var route = executor.submit(() -> {
				StructureProbe.enter();
				try {
					return StructureProbe.bypass(state, 1, 1000, 2000);
				} finally {
					StructureProbe.exit();
				}
			});
			try {
				assertTrue(started.await(5, TimeUnit.SECONDS));
				assertFalse(route.isDone());
			} finally {
				pending.complete(tile);
			}
			assertFalse(route.get(5, TimeUnit.SECONDS));
		}
	}

	@Test
	void optionalLookupFailuresDisableTheBypassForTheRestOfTheSession() throws Exception {
		var failureFlag = StructureProbe.class.getDeclaredField("LOOKUP_FAILED");
		failureFlag.setAccessible(true);
		var failed = (AtomicBoolean) failureFlag.get(null);
		var tiles = tileCache(FailingTileCache.class);
		var broken = state(tiles);
		var cold = state(tileCache(TileCache.class));
		StructureProbe.enter();
		try {
			for(boolean linkage : new boolean[] {false, true}) {
				failed.set(false);
				tiles.linkage = linkage;
				int previous = tiles.lookups;
				assertFalse(StructureProbe.bypass(broken, 1, 1000, 2000));
				assertFalse(StructureProbe.bypass(broken, 1, 1000, 2000));
				assertFalse(StructureProbe.bypass(cold, 1, 1000, 2000));
				assertEquals(previous + 1, tiles.lookups);
			}
		} finally {
			StructureProbe.exit();
			failed.set(false);
		}
	}

	private record State(GeneratorContext generatorContext) implements RTFRandomState {
		@Override public void initialize(ServerLevel level) { }
		@Override public Preset preset() { return null; }
		@Override public DensityFunction wrap(DensityFunction function) { return function; }
		@Override public Noise seed(Noise noise) { return noise; }
	}

	private static final class FailingTileCache extends TileCache {
		int lookups;
		boolean linkage;

		private FailingTileCache() {
			super(3, false, null);
		}

		@Override
		public Tile provideAtChunkIfPresent(int x, int z) {
			lookups++;
			if(linkage) throw new NoSuchMethodError("fixture lookup failure");
			throw new IllegalStateException("fixture lookup failure");
		}
	}

	private static State state(TileCache tiles) throws Exception {
		var context = allocate(GeneratorContext.class);
		context.cache = tiles;
		return new State(context);
	}

	private static <T extends TileCache> T tileCache(Class<T> type) throws Exception {
		// Use real cache methods without starting the cache scheduler or generating terrain.
		T tiles = allocate(type);
		var cache = allocate(Cache.class);
		set(Cache.class, cache, "map", new StampedBoundLongMap<>(16));
		set(TileCache.class, tiles, "cache", cache);
		set(TileCache.class, tiles, "tileSize", 3);
		return tiles;
	}

	@SuppressWarnings("unchecked")
	private static void put(TileCache tiles, int x, int z, CompletableFuture<Object> tile) throws Exception {
		Object cache = get(TileCache.class, tiles, "cache");
		var map = (LongMap<CacheEntry<Object>>) get(Cache.class, cache, "map");
		map.put(PosUtil.pack(tiles.chunkToTile(x), tiles.chunkToTile(z)), CacheEntry.supply(tile));
	}

	private static Object tileEntry() throws Exception {
		var entry = Class.forName(TileCache.class.getName() + "$Entry");
		Object result = allocate(entry);
		set(entry, result, "tile", allocate(Tile.class));
		return result;
	}

	static <T> T allocate(Class<T> type) throws Exception {
		return type.cast(((Unsafe) get(Unsafe.class, null, "theUnsafe")).allocateInstance(type));
	}

	static Object get(Class<?> owner, Object target, String name) throws Exception {
		var field = owner.getDeclaredField(name);
		field.setAccessible(true);
		return field.get(target);
	}

	static void set(Class<?> owner, Object target, String name, Object value) throws Exception {
		var field = owner.getDeclaredField(name);
		field.setAccessible(true);
		field.set(target, value);
	}
}
