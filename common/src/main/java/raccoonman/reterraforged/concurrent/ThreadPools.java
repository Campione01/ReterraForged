package raccoonman.reterraforged.concurrent;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPools {
	private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);
	private static final AtomicInteger BACKGROUND_THREAD_COUNTER = new AtomicInteger(0);
	public static final ThreadGroup RTF_GROUP = new ThreadGroup("rtf-worldgen");
	// Background threads run at the priority of the shared workers unless told otherwise: at a lower priority a thread
	// that is descheduled while it holds a lock of another mod would stall the workers that generate the world.
	private static final int BACKGROUND_PRIORITY = Math.max(Thread.MIN_PRIORITY,
		Math.min(Thread.NORM_PRIORITY - 1, Integer.getInteger("reterraforged.backgroundPriority", Thread.NORM_PRIORITY - 1)));

	public static final ThreadPoolExecutor WORLD_GEN = new ThreadPoolExecutor(
		defaultWorkerThreads(),
		defaultWorkerThreads(),
		0L,
		TimeUnit.MILLISECONDS,
		new LinkedBlockingQueue<>(),
		ThreadPools::newWorkerThread
	);
	
	// Tiles of contexts that only predict terrain run here, so they never stand in the queue of the tiles the world waits for.
	public static final ThreadPoolExecutor BACKGROUND = createBackgroundPool();
	
	public static final Executor INVOKING = r -> {
		if(Thread.currentThread().getThreadGroup() == RTF_GROUP) {
			r.run();
		} else {
			WORLD_GEN.execute(r);
		}
	};

	private static Thread newWorkerThread(Runnable r) {
		Thread thread = new Thread(RTF_GROUP, r, "RTF worker #" + THREAD_COUNTER.getAndIncrement());
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	}

	private static Thread newBackgroundThread(Runnable r) {
		Thread thread = new Thread(RTF_GROUP, r, "RTF background worker #" + BACKGROUND_THREAD_COUNTER.getAndIncrement());
		thread.setDaemon(true);
		thread.setPriority(BACKGROUND_PRIORITY);
		return thread;
	}

	private static ThreadPoolExecutor createBackgroundPool() {
		ThreadPoolExecutor pool = new ThreadPoolExecutor(
			defaultWorkerThreads(),
			defaultWorkerThreads(),
			60L,
			TimeUnit.SECONDS,
			new LinkedBlockingQueue<>(),
			ThreadPools::newBackgroundThread
		);
		pool.allowCoreThreadTimeOut(true);
		return pool;
	}

	public static synchronized void configureWorldGenThreads(int threadCount) {
		int count = Math.max(1, Math.min(threadCount, availableProcessors() * 2));
		resize(WORLD_GEN, count);
		resize(BACKGROUND, count);
	}

	private static void resize(ThreadPoolExecutor pool, int count) {
		if(pool.getCorePoolSize() == count && pool.getMaximumPoolSize() == count) {
			return;
		}
		if(count > pool.getMaximumPoolSize()) {
			pool.setMaximumPoolSize(count);
			pool.setCorePoolSize(count);
		} else {
			pool.setCorePoolSize(count);
			pool.setMaximumPoolSize(count);
		}
	}
	
	public static int defaultWorkerThreads() {
		return Math.max(2, availableProcessors() - 2);
	}
	
	public static int availableProcessors() {
		return Math.max(2, Runtime.getRuntime().availableProcessors());
	}
}
