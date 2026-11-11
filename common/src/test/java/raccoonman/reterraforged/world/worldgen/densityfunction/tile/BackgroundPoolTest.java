package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import raccoonman.reterraforged.concurrent.ThreadPools;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.TestContexts;

/** Contexts that only predict terrain must not queue their tiles ahead of the tiles world generation waits for. */
class BackgroundPoolTest {
	private static final int CHUNK_X = 640;
	private static final int CHUNK_Z = 1280;

	@BeforeAll
	static void bootstrapMinecraft() {
		TestContexts.bootstrapMinecraft();
	}

	@Test
	void backgroundContextsGenerateOnTheirOwnPool() throws Exception {
		Preset preset = TestContexts.preset();
		GeneratorContext server = GeneratorContext.makeCached(preset, TestContexts.noise(preset), 42, TestContexts.TILE_SIZE, TestContexts.BATCH_COUNT, true);
		GeneratorContext prediction = GeneratorContext.makeBackground(preset, TestContexts.noise(preset), 42, TestContexts.TILE_SIZE, TestContexts.BATCH_COUNT, true);
		int batches = TestContexts.BATCH_COUNT * TestContexts.BATCH_COUNT;
		ThreadPoolExecutor shared = ThreadPools.WORLD_GEN;
		ThreadPoolExecutor background = ThreadPools.BACKGROUND;
		try {
			assertSame(shared, server.generator.getExecutor(), "makeCached stays on the shared pool");
			assertSame(background, prediction.generator.getExecutor());

			long sharedBefore = settledCompleted(shared);
			long backgroundBefore = settledCompleted(background);
			Tile real = server.cache.provideAtChunk(CHUNK_X, CHUNK_Z);
			assertEquals(sharedBefore + batches, settledCompleted(shared), "a server tile runs its batches on the shared pool");
			assertEquals(backgroundBefore, settledCompleted(background), "a server tile runs nothing on the background pool");

			Tile predicted = prediction.cache.provideAtChunk(CHUNK_X, CHUNK_Z);
			assertEquals(sharedBefore + batches, settledCompleted(shared), "a prediction tile runs nothing on the shared pool");
			assertEquals(backgroundBefore + batches, settledCompleted(background), "a prediction tile runs its batches on the background pool");
			assertEquals(Float.floatToRawIntBits(real.lookup(CHUNK_X << 4, CHUNK_Z << 4).height),
					Float.floatToRawIntBits(predicted.lookup(CHUNK_X << 4, CHUNK_Z << 4).height), "both pools compute the same tile");

			// With every background thread busy and prediction tiles waiting behind them, a real tile still completes.
			CountDownLatch release = new CountDownLatch(1);
			int threads = background.getMaximumPoolSize();
			CountDownLatch occupied = new CountDownLatch(threads);
			for (int i = 0; i < threads; i++) {
				background.execute(() -> {
					occupied.countDown();
					try {
						release.await();
					} catch (InterruptedException e) {
						Thread.currentThread().interrupt();
					}
				});
			}
			try {
				assertTrue(occupied.await(30, TimeUnit.SECONDS), "background pool occupied");
				prediction.cache.queueAtChunk(CHUNK_X + 64, CHUNK_Z);
				assertEquals(batches, background.getQueue().size(), "the prediction tile waits in the background queue");
				Tile ahead = CompletableFuture.supplyAsync(() -> server.cache.provideAtChunk(CHUNK_X + 64, CHUNK_Z)).get(120, TimeUnit.SECONDS);
				assertNotNull(ahead, "the real tile was generated while prediction was blocked");
				assertEquals(batches, background.getQueue().size(), "the prediction tile is still waiting");
			} finally {
				release.countDown();
			}
			assertNotNull(prediction.cache.provideAtChunk(CHUNK_X + 64, CHUNK_Z));
		} finally {
			server.close();
			prediction.close();
		}
	}

	private static long settledCompleted(ThreadPoolExecutor pool) throws InterruptedException {
		// A worker counts a task as completed just after the task's future completes.
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (pool.getActiveCount() > 0 || !pool.getQueue().isEmpty()) {
			assertTrue(System.nanoTime() < deadline, "pool did not settle");
			Thread.sleep(5);
		}
		return pool.getCompletedTaskCount();
	}
}
