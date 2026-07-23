package raccoonman.reterraforged.concurrent;

import java.util.concurrent.Executor;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ThreadPools {
	private static final AtomicInteger THREAD_COUNTER = new AtomicInteger(0);
	public static final ThreadGroup RTF_GROUP = new ThreadGroup("rtf-worldgen");

	public static final ThreadPoolExecutor WORLD_GEN = new ThreadPoolExecutor(
		defaultWorkerThreads(),
		defaultWorkerThreads(),
		0L,
		TimeUnit.MILLISECONDS,
		new LinkedBlockingQueue<>(),
		ThreadPools::newWorkerThread
	);
	
	public static final Executor INVOKING = r -> {
		if(Thread.currentThread().getThreadGroup() == RTF_GROUP) {
			r.run();
		} else {
			WORLD_GEN.execute(r);
		}
	};

	private static Thread newWorkerThread(Runnable r) {
		WorkerThread thread = new WorkerThread(r, "RTF worker #" + THREAD_COUNTER.getAndIncrement());
		thread.setDaemon(true);
		thread.setPriority(Thread.NORM_PRIORITY - 1);
		return thread;
	}

	public static final class WorkerThread extends Thread {
		private Object rootNoiseSession;
		private Object worldgenScratchOwner;
		private Object worldgenScratch;

		private WorkerThread(Runnable task, String name) {
			super(RTF_GROUP, task, name);
		}

		public Object rootNoiseSession() {
			return this.rootNoiseSession;
		}

		public void rootNoiseSession(Object rootNoiseSession) {
			this.rootNoiseSession = rootNoiseSession;
		}

		public Object worldgenScratch(Object owner) {
			return this.worldgenScratchOwner == owner ? this.worldgenScratch : null;
		}

		public void worldgenScratch(Object owner, Object scratch) {
			this.worldgenScratchOwner = owner;
			this.worldgenScratch = scratch;
		}

		public void clearWorldgenScratch() {
			this.worldgenScratchOwner = null;
			this.worldgenScratch = null;
		}
	}

	public static void clearWorldgenScratch() {
		Thread thread = Thread.currentThread();
		if(thread instanceof WorkerThread worker) {
			worker.clearWorldgenScratch();
		}
	}

	public static synchronized void configureWorldGenThreads(int threadCount) {
		int count = Math.max(1, Math.min(threadCount, availableProcessors() * 2));
		if(WORLD_GEN.getCorePoolSize() == count && WORLD_GEN.getMaximumPoolSize() == count) {
			return;
		}
		if(count > WORLD_GEN.getMaximumPoolSize()) {
			WORLD_GEN.setMaximumPoolSize(count);
			WORLD_GEN.setCorePoolSize(count);
		} else {
			WORLD_GEN.setCorePoolSize(count);
			WORLD_GEN.setMaximumPoolSize(count);
		}
	}
	
	public static int defaultWorkerThreads() {
		return Math.max(2, availableProcessors() - 2);
	}
	
	public static int availableProcessors() {
		return Math.max(2, Runtime.getRuntime().availableProcessors());
	}
}
