package raccoonman.reterraforged.concurrent.cache;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ForkJoinTask;
import java.util.concurrent.Future;

/**
 * Cache entry that wraps a CompletableFuture and exposes the same surface the
 * rest of ReTerraForged expects (get / isDone / timestamp / close).
 *
 * <p>Historical note: this used to extend {@code LazyCallable}, which held a
 * StampedLock write lock across {@code ForkJoinTask.join()}. Under the vanilla
 * chunk scheduler that runs world-gen on {@code Util.backgroundExecutor()} (a
 * ForkJoinPool), the join triggered {@code managedBlock} compensation, spawning
 * fresh workers that hit the same cache entry and parked on the StampedLock
 * read path — a full ForkJoinPool-wide deadlock (observed: 80+ Worker-Main
 * threads on the same {@code StampedLock.acquireRead}).
 *
 * <p>The new implementation delegates directly to a {@code CompletableFuture},
 * which is safe under concurrent {@code join()} and cannot deadlock on itself.
 * The upstream {@code TileCache.computeIfAbsent} already guarantees a single
 * {@code CacheEntry} per key, so no extra single-flight lock is needed here.
 */
public class CacheEntry<T> implements ExpiringEntry {
	private final CompletableFuture<T> future;
	private volatile long timestamp;

	public CacheEntry(Future<T> task) {
		this.future = toCompletableFuture(task);
		this.timestamp = System.currentTimeMillis();
	}

	public T get() {
		this.timestamp = System.currentTimeMillis();
		try {
			return this.future.join();
		} catch (CompletionException e) {
			Throwable c = e.getCause();
			if (c instanceof RuntimeException r) throw r;
			if (c instanceof Error err) throw err;
			throw e;
		}
	}

	public boolean isDone() {
		return this.future.isDone();
	}

	@Override
	public long getTimestamp() {
		return this.timestamp;
	}

	@Override
	public void close() {
		if (!this.future.isDone()) {
			return;
		}
		T value;
		try {
			value = this.future.getNow(null);
		} catch (Throwable t) {
			return;
		}
		if (value instanceof SafeCloseable safe) {
			safe.close();
			return;
		}
		if (value instanceof AutoCloseable ac) {
			try {
				ac.close();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}
	}

	public static <T> CacheEntry<T> supply(Future<T> task) {
		return new CacheEntry<>(task);
	}

	private static <T> CompletableFuture<T> toCompletableFuture(Future<T> task) {
		if (task instanceof CompletableFuture<T> cf) {
			return cf;
		}
		if (task instanceof CompletionStage<?> stage) {
			@SuppressWarnings("unchecked")
			CompletionStage<T> typed = (CompletionStage<T>) stage;
			return typed.toCompletableFuture();
		}
		if (task instanceof ForkJoinTask<T> fjt) {
			return CompletableFuture.supplyAsync(fjt::join);
		}
		return CompletableFuture.supplyAsync(() -> {
			try {
				return task.get();
			} catch (Throwable t) {
				throw new RuntimeException(t);
			}
		});
	}
}
