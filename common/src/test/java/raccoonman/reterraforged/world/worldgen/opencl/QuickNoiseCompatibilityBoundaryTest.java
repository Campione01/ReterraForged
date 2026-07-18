package raccoonman.reterraforged.world.worldgen.opencl;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class QuickNoiseCompatibilityBoundaryTest {
	private static final String[] BACKEND_CLASSES = {
		"raccoonman/reterraforged/world/worldgen/quicknoise/QuickCaveDensity$Runtime.class",
		"raccoonman/reterraforged/world/worldgen/quicknoise/QuickNoiseTileCache.class",
		"raccoonman/reterraforged/world/worldgen/opencl/OpenClManager.class",
		"raccoonman/reterraforged/world/worldgen/opencl/OpenClRuntime.class"
	};

	@Test
	void backendOwnsNoC2meExecutorOrFutureTypes() throws IOException {
		for(String resource : BACKEND_CLASSES) {
			assertClassOmits(resource, "com/ishland", "GlobalExecutors", "java/util/concurrent/Executor", "java/util/concurrent/Future", "CompletableFuture", "ForkJoinPool");
		}
	}

	@Test
	void tileCacheHasNoBlockingPrimitive() throws IOException {
		assertClassOmits(BACKEND_CLASSES[1], "java/util/concurrent/locks", "Semaphore", "CountDownLatch", "Phaser");
	}

	private static void assertClassOmits(String resource, String... forbiddenSymbols) throws IOException {
		try(InputStream input = QuickNoiseCompatibilityBoundaryTest.class.getClassLoader().getResourceAsStream(resource)) {
			assertNotNull(input, resource);
			String classData = new String(input.readAllBytes(), StandardCharsets.ISO_8859_1);
			for(String forbidden : forbiddenSymbols) {
				assertFalse(classData.contains(forbidden), () -> resource + " references forbidden symbol " + forbidden);
			}
		}
	}
}
