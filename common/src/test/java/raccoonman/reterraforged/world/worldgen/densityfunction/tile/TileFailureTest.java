package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.TestContexts;
import raccoonman.reterraforged.world.worldgen.WorldFilters;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator;

/** A tile whose generation failed must not stay in the cache and fail every later request. */
class TileFailureTest {
	private static final int CHUNK_X = 640;
	private static final int CHUNK_Z = 1280;

	@BeforeAll
	static void bootstrapMinecraft() {
		TestContexts.bootstrapMinecraft();
	}

	@Test
	void aFailedTileIsNotServedAgain() {
		GeneratorContext context = TestContexts.cached(42);
		AtomicInteger calls = new AtomicInteger();
		TileGenerator flaky = new TileGenerator(context.generator.getHeightmap(), new WorldFilters(context), TestContexts.TILE_SIZE, 1, TestContexts.BATCH_COUNT) {
			@Override
			public CompletableFuture<Tile> generate(int tileX, int tileZ) {
				if (calls.getAndIncrement() == 0) {
					return CompletableFuture.failedFuture(new IllegalStateException("first attempt fails"));
				}
				return super.generate(tileX, tileZ);
			}
		};
		TileCache cache = new TileCache(TestContexts.TILE_SIZE, true, flaky);
		try {
			IllegalStateException failure = assertThrows(IllegalStateException.class, () -> cache.provideAtChunk(CHUNK_X, CHUNK_Z));
			assertEquals("first attempt fails", failure.getMessage());
			assertNull(cache.provideAtChunkIfPresent(CHUNK_X, CHUNK_Z), "the failed tile is not kept");

			Tile tile = cache.provideAtChunk(CHUNK_X, CHUNK_Z);
			assertNotNull(tile, "the next request generates the tile");
			assertEquals(2, calls.get());
			assertSame(tile, cache.provideAtChunk(CHUNK_X, CHUNK_Z), "the good tile is cached");
			assertEquals(2, calls.get());
		} finally {
			cache.close();
			context.cache.close();
		}
	}
}
