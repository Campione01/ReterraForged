package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.TestContexts;
import raccoonman.reterraforged.world.worldgen.cell.Cell;

/**
 * A tile whose 64 chunks have all finished features leaves the cache, but a reader that took it earlier may still
 * use it. Its arrays must therefore never be reset or handed to another tile.
 */
class TileRecycleTest {
	// Far from the origin, where a chunk handle built from world coordinates cannot index the tile by accident.
	private static final int CHUNK_X = 1024;
	private static final int CHUNK_Z = -2048;

	@BeforeAll
	static void bootstrapMinecraft() {
		TestContexts.bootstrapMinecraft();
	}

	@Test
	void aTileHeldByAReaderSurvivesTheLastDrop() {
		GeneratorContext context = TestContexts.cached(42);
		TileCache cache = context.cache;
		int chunks = 1 << TestContexts.TILE_SIZE;

		Tile held = cache.provideAtChunk(CHUNK_X, CHUNK_Z);
		int blockX = (CHUNK_X << 4) + 5;
		int blockZ = (CHUNK_Z << 4) + 9;
		float[] before = heights(held, chunks);
		float cellBefore = held.getChunkReader(CHUNK_X, CHUNK_Z).getCell(blockX, blockZ).height;

		for (int dz = 0; dz < chunks; dz++) {
			for (int dx = 0; dx < chunks; dx++) {
				cache.dropAtChunk(CHUNK_X + dx, CHUNK_Z + dz);
			}
		}
		assertNull(cache.provideAtChunkIfPresent(CHUNK_X, CHUNK_Z), "the finished tile left the cache");

		// The reader that took the tile before the last drop keeps reading it.
		assertEquals(cellBefore, held.getChunkReader(CHUNK_X, CHUNK_Z).getCell(blockX, blockZ).height,
				"a held tile still answers through a chunk handle");
		float[] after = heights(held, chunks);
		for (int i = 0; i < before.length; i++) {
			assertEquals(Float.floatToRawIntBits(before[i]), Float.floatToRawIntBits(after[i]), "held tile cell " + i);
		}

		// The next tile is built on arrays of its own and equals the same tile of an untouched context.
		Tile next = cache.provideAtChunk(CHUNK_X + chunks, CHUNK_Z);
		assertNotSame(held.getBacking(), next.getBacking(), "a published cell array is not handed to another tile");
		Tile reference = TestContexts.cached(42).cache.provideAtChunk(CHUNK_X + chunks, CHUNK_Z);
		float[] expected = heights(reference, chunks);
		float[] actual = heights(next, chunks);
		for (int i = 0; i < expected.length; i++) {
			assertEquals(Float.floatToRawIntBits(expected[i]), Float.floatToRawIntBits(actual[i]), "next tile cell " + i);
		}
		// Still intact after another tile was generated.
		after = heights(held, chunks);
		for (int i = 0; i < before.length; i++) {
			assertEquals(Float.floatToRawIntBits(before[i]), Float.floatToRawIntBits(after[i]), "held tile cell " + i + " after the next tile");
		}
	}

	private static float[] heights(Tile tile, int chunks) {
		int blocks = chunks << 4;
		float[] heights = new float[blocks * blocks];
		tile.iterate((Cell cell, int x, int z) -> heights[z * blocks + x] = cell.height);
		return heights;
	}
}
