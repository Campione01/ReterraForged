package raccoonman.reterraforged.concurrent.cache;

import java.lang.ref.WeakReference;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;
import java.util.function.LongFunction;

import raccoonman.reterraforged.concurrent.cache.map.LongMap;

public class Cache<V extends ExpiringEntry> implements AutoCloseable {
	public static final ScheduledExecutorService SCHEDULER = createScheduler();

    private LongMap<V> map;
    private long lifetimeMS;
    private volatile long timeout;
    private ScheduledFuture<?> poll;

    public Cache(int capacity, long expireTime, long pollInterval, TimeUnit unit, IntFunction<LongMap<V>> mapFunc) {
        this.timeout = 0L;
        this.map = mapFunc.apply(capacity);
        this.lifetimeMS = unit.toMillis(expireTime);

        long intervalMillis = unit.toMillis(pollInterval);
        Poll task = new Poll(this);
        this.poll = SCHEDULER.scheduleAtFixedRate(task, intervalMillis, intervalMillis, TimeUnit.MILLISECONDS);
        task.self = this.poll;
    }

    public void remove(long key) {
        this.map.remove(key, ExpiringEntry::close);
    }

    public void removeEntry(V entry) {
        this.map.removeIf((mapped) -> mapped == entry);
    }

    public V get(long key) {
        return this.map.get(key);
    }

    public V computeIfAbsent(long key, LongFunction<V> func) {
        return this.map.computeIfAbsent(key, func);
    }

    public void poll() {
        this.timeout = System.currentTimeMillis() - this.lifetimeMS;
        this.map.removeIf((entry) -> entry.getTimestamp() < this.timeout);
    }

	@Override
	public void close() throws Exception {
		this.poll.cancel(false);
	}

	public void clear() {
		this.map.clear();
	}

	private static ScheduledExecutorService createScheduler() {
		ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, (r) -> {
			Thread thread = new Thread(r);
			thread.setName("CacheScheduler");
			thread.setDaemon(true);
			return thread;
		});
		scheduler.setRemoveOnCancelPolicy(true);
		return scheduler;
	}

	// The scheduler must not keep a cache alive: an abandoned cache ends its own poll.
	private static final class Poll implements Runnable {
		private final WeakReference<Cache<?>> cache;
		private volatile ScheduledFuture<?> self;

		private Poll(Cache<?> cache) {
			this.cache = new WeakReference<>(cache);
		}

		@Override
		public void run() {
			Cache<?> cache = this.cache.get();
			if(cache != null) {
				cache.poll();
				return;
			}
			ScheduledFuture<?> self = this.self;
			if(self != null) {
				self.cancel(false);
			}
		}
	}
}
