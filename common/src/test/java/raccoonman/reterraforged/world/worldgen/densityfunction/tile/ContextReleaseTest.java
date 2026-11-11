package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import raccoonman.reterraforged.concurrent.cache.Cache;
import raccoonman.reterraforged.concurrent.cache.CacheManager;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.TestContexts;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.RiverCache;

/** Closing a context, as the level's ChunkMap does when it closes, releases its caches at once. */
class ContextReleaseTest {
	private static final int CHUNK_X = 640;
	private static final int CHUNK_Z = 1280;

	@BeforeAll
	static void bootstrapMinecraft() {
		TestContexts.bootstrapMinecraft();
	}

	@Test
	void aClosedContextLeavesNoCacheOrPollBehind() throws Exception {
		GeneratorContext context = TestContexts.cached(42);
		Cache<?> tiles = tileCacheOf(context.cache);
		Cache<?> rivers = riverCacheOf(context);
		ScheduledFuture<?> tilePoll = pollOf(tiles);
		ScheduledFuture<?> riverPoll = pollOf(rivers);
		ScheduledThreadPoolExecutor scheduler = (ScheduledThreadPoolExecutor) Cache.SCHEDULER;

		assertTrue(registered().contains(tiles), "an open tile cache is registered");
		assertTrue(scheduler.getQueue().contains(tilePoll), "an open tile cache is polled");
		assertTrue(scheduler.getQueue().contains(riverPoll), "an open river cache is polled");
		Tile tile = context.cache.provideAtChunk(CHUNK_X, CHUNK_Z);
		assertSame(tile, context.cache.provideAtChunkIfPresent(CHUNK_X, CHUNK_Z));

		context.close();

		assertFalse(registered().contains(tiles), "a closed tile cache is no longer registered");
		assertTrue(tilePoll.isCancelled() && riverPoll.isCancelled(), "both polls are cancelled");
		assertFalse(scheduler.getQueue().contains(tilePoll), "the tile poll left the scheduler at once");
		assertFalse(scheduler.getQueue().contains(riverPoll), "the river poll left the scheduler at once");
		assertNull(context.cache.provideAtChunkIfPresent(CHUNK_X, CHUNK_Z), "the tiles were released");

		// A late lookup is still answered, with the same terrain, and leaves nothing behind.
		Tile late = context.cache.provideAtChunk(CHUNK_X, CHUNK_Z);
		assertEquals(Float.floatToRawIntBits(tile.lookup(CHUNK_X << 4, CHUNK_Z << 4).height),
				Float.floatToRawIntBits(late.lookup(CHUNK_X << 4, CHUNK_Z << 4).height));
		assertNull(context.cache.provideAtChunkIfPresent(CHUNK_X, CHUNK_Z), "a closed cache holds no tile");
	}

	@Test
	void theGlobalClearIsNotReachableFromThePresetPreview() throws Exception {
		// CacheManager.clear() cancels the poll of every registered cache, the live server's included.
		for (String name : List.of("PresetEditorPage", "PresetEditorPage$Preview")) {
			String path = "/raccoonman/reterraforged/client/gui/screen/presetconfig/" + name + ".class";
			try (InputStream stream = ContextReleaseTest.class.getResourceAsStream(path)) {
				assertNotNull(stream, path);
				String bytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
				assertFalse(bytes.contains("raccoonman/reterraforged/concurrent/cache/CacheManager"), name + " refers to CacheManager");
			}
		}
	}

	@Test
	void aContextThatCannotBeReleasedDoesNotStopItsLevelFromClosing() throws Exception {
		GeneratorContext context = TestContexts.cached(42);
		ScheduledFuture<?> riverPoll = pollOf(riverCacheOf(context));
		TileCache original = context.cache;
		context.cache = new TileCache(TestContexts.TILE_SIZE, true, context.generator) {
			@Override
			public void close() {
				super.close();
				throw new IllegalStateException("this cache cannot be closed");
			}
		};
		try {
			assertDoesNotThrow(() -> GeneratorContext.closeQuietly(context), "a closing level must not see the failure");
			assertTrue(riverPoll.isCancelled(), "the rest of the context is still released");
			assertDoesNotThrow(() -> GeneratorContext.closeQuietly(null));
		} finally {
			original.close();
		}

		// The level closes its context through the guarded call.
		String path = "/raccoonman/reterraforged/mixin/MixinChunkMap.class";
		try (InputStream stream = ContextReleaseTest.class.getResourceAsStream(path)) {
			assertNotNull(stream, path);
			String bytes = new String(stream.readAllBytes(), StandardCharsets.ISO_8859_1);
			assertTrue(bytes.contains("closeQuietly"), "ChunkMap.close releases the context without the guard");
		}
	}

	@SuppressWarnings("unchecked")
	private static List<Cache<?>> registered() throws Exception {
		Field caches = CacheManager.class.getDeclaredField("CACHES");
		caches.setAccessible(true);
		return (List<Cache<?>>) caches.get(null);
	}

	private static Cache<?> tileCacheOf(TileCache cache) throws Exception {
		Field field = TileCache.class.getDeclaredField("cache");
		field.setAccessible(true);
		return (Cache<?>) field.get(cache);
	}

	private static Cache<?> riverCacheOf(GeneratorContext context) throws Exception {
		Object continent = context.generator.getHeightmap().continent();
		for (Class<?> type = continent.getClass(); type != null; type = type.getSuperclass()) {
			for (Field field : type.getDeclaredFields()) {
				if (RiverCache.class.isAssignableFrom(field.getType())) {
					field.setAccessible(true);
					Field cache = RiverCache.class.getDeclaredField("cache");
					cache.setAccessible(true);
					return (Cache<?>) cache.get(field.get(continent));
				}
			}
		}
		throw new AssertionError("no river cache in " + continent.getClass());
	}

	private static ScheduledFuture<?> pollOf(Cache<?> cache) throws Exception {
		Field field = Cache.class.getDeclaredField("poll");
		field.setAccessible(true);
		return (ScheduledFuture<?>) field.get(cache);
	}
}
