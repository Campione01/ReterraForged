package raccoonman.reterraforged.concurrent.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import raccoonman.reterraforged.concurrent.cache.map.StampedLongMap;

class CacheSchedulerTest {

	/** Creates one cache and returns from main, as an offline fixture does. */
	public static final class Probe {
		public static void main(String[] args) {
			Cache<ExpiringEntry> cache = new Cache<>(4, 1L, 1L, TimeUnit.SECONDS, StampedLongMap::new);
			cache.computeIfAbsent(1L, (key) -> System::currentTimeMillis);
			System.out.println("cache created");
		}
	}

	@Test
	void aJvmThatOnlyCreatesACacheExitsByItself(@TempDir Path temp) throws Exception {
		// The class path goes through an argument file: it is longer than a Windows command line allows.
		Path arguments = temp.resolve("probe.args");
		String classPath = System.getProperty("java.class.path").replace("\\", "\\\\");
		Files.writeString(arguments, "-cp\n\"" + classPath + "\"\n");
		Path java = Path.of(System.getProperty("java.home"), "bin", "java");
		Path output = temp.resolve("probe.out");
		Process process = new ProcessBuilder(List.of(java.toString(), "@" + arguments, Probe.class.getName()))
				.redirectErrorStream(true)
				.redirectOutput(output.toFile())
				.start();
		boolean exited = process.waitFor(30, TimeUnit.SECONDS);
		if (!exited) {
			process.destroyForcibly().waitFor();
		}
		String log = Files.readString(output);
		assertTrue(log.contains("cache created"), "probe ran: " + log);
		assertTrue(exited, "the JVM stayed alive after main returned: " + log);
		assertEquals(0, process.exitValue(), log);
	}

	@Test
	void anAbandonedCacheEndsItsOwnPoll() throws Exception {
		ScheduledThreadPoolExecutor scheduler = (ScheduledThreadPoolExecutor) Cache.SCHEDULER;
		ScheduledFuture<?> poll = pollOf(new Cache<ExpiringEntry>(4, 50L, 50L, TimeUnit.MILLISECONDS, StampedLongMap::new));
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (!poll.isCancelled()) {
			assertTrue(System.nanoTime() < deadline, "the scheduler still polls a cache nobody holds");
			System.gc();
			Thread.sleep(50);
		}
		assertFalse(scheduler.getQueue().contains(poll), "the cancelled poll left the scheduler");
	}

	@Test
	void aCacheInUseKeepsItsPoll() throws Exception {
		Cache<ExpiringEntry> cache = new Cache<>(4, 50L, 50L, TimeUnit.MILLISECONDS, StampedLongMap::new);
		ScheduledFuture<?> poll = pollOf(cache);
		long[] stamp = { System.currentTimeMillis() };
		cache.computeIfAbsent(7L, (key) -> () -> stamp[0]);
		for (int i = 0; i < 5; i++) {
			System.gc();
			Thread.sleep(50);
		}
		assertFalse(poll.isCancelled(), "a referenced cache is still polled");
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
		while (cache.get(7L) != null) {
			assertTrue(System.nanoTime() < deadline, "the entry never expired");
			Thread.sleep(50);
		}
		cache.close();
		assertTrue(poll.isCancelled());
	}

	private static ScheduledFuture<?> pollOf(Cache<?> cache) throws Exception {
		Field field = Cache.class.getDeclaredField("poll");
		field.setAccessible(true);
		return (ScheduledFuture<?>) field.get(cache);
	}
}
