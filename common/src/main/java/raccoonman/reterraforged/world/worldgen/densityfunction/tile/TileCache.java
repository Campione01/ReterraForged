package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

import java.lang.ref.SoftReference;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;

import raccoonman.reterraforged.concurrent.cache.Cache;
import raccoonman.reterraforged.concurrent.cache.CacheEntry;
import raccoonman.reterraforged.concurrent.cache.CacheManager;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;

public class TileCache implements TileFactory {
	private int tileSize;
	private boolean queue;
	private Cache<CacheEntry<Entry>> cache;
	private TileGenerator generator;
	private volatile boolean closed;
	// A failed tile fails at once for this long, then the next request generates it again.
	long failureRetryMillis = 5000L;
	private final Object lateLock = new Object();
	private long lateKey;
	private SoftReference<Pending> late;

	public TileCache(int tileSize, boolean queue, TileGenerator generator) {
		this.tileSize = tileSize;
		this.queue = queue;
		this.cache = CacheManager.createCache(256, 60L, 20L, TimeUnit.SECONDS);
		this.generator = generator;
	}

	public TileGenerator getGenerator() {
		return this.generator;
	}

	@Nullable
	public Tile provideIfPresent(int tileX, int tileZ) {
		@Nullable
		CacheEntry<Entry> entry = this.cache.get(PosUtil.pack(tileX, tileZ));
		return entry != null && !failed(entry) ? entry.get().tile : null;
	}

	@Nullable
	public Tile provideAtChunkIfPresent(int chunkX, int chunkZ) {
		return this.provideIfPresent(this.chunkToTile(chunkX), this.chunkToTile(chunkZ));
	}

	@Override
	public Tile provide(int tileX, int tileZ) {
		return this.computeEntry(tileX, tileZ).get().tile;
	}

	@Override
	public void queue(int tileX, int tileZ) {
		// Nobody waits for a queued tile, so a closed cache does not start one.
		if(this.queue && !this.closed) {
			this.computeEntry(tileX, tileZ);
		}
	}

	@Override
	public void drop(int tileX, int tileZ) {
		long packedTilePos = PosUtil.pack(tileX, tileZ);
		//TODO i dont think get should be able to return null here
		CacheEntry<Entry> entry = this.cache.get(packedTilePos);
		if(entry != null && !failed(entry) && entry.get().drop()) {
			this.cache.remove(packedTilePos);
		}
	}

	@Override
	public int chunkToTile(int chunkCoord) {
		return chunkCoord >> this.tileSize;
	}

	public void close() {
		this.closed = true;
		try {
			this.cache.close();
		} catch (Exception e) {
			e.printStackTrace();
		}
		this.cache.clear();
		CacheManager.remove(this.cache);
	}

	private CacheEntry<Entry> computeEntry(int tileX, int tileZ) {
		long packedTilePos = PosUtil.pack(tileX, tileZ);
		CacheEntry<Entry> entry = this.cache.get(packedTilePos);
		if(entry != null) {
			if(!retryDue(entry)) {
				return entry;
			}
			this.cache.removeEntry(entry);
		}
		if(this.closed) {
			return this.computeLate(tileX, tileZ, packedTilePos);
		}
		Pending created = new Pending();
		entry = this.cache.computeIfAbsent(packedTilePos, (k) -> created);
		if(entry == created) {
			// The cache may have been closed since the check above: it must stay empty, and this caller still gets its tile.
			if(this.closed) {
				this.cache.removeEntry(created);
			}
			this.generate(tileX, tileZ, created);
		}
		return entry;
	}

	// A closed cache holds nothing for the level that is gone, but callers that keep asking for one tile share one tile.
	private CacheEntry<Entry> computeLate(int tileX, int tileZ, long packedTilePos) {
		Pending created;
		synchronized(this.lateLock) {
			Pending late = this.late != null && this.lateKey == packedTilePos ? this.late.get() : null;
			if(late != null && !retryDue(late)) {
				return late;
			}
			created = new Pending();
			this.lateKey = packedTilePos;
			this.late = new SoftReference<>(created);
		}
		this.generate(tileX, tileZ, created);
		return created;
	}

	// Runs outside the map lock: allocating and submitting a tile must not block other tile lookups.
	private void generate(int tileX, int tileZ, Pending entry) {
		CompletableFuture<Tile> tile;
		try {
			tile = this.generator.generate(tileX, tileZ);
		} catch (Throwable failure) {
			this.fail(entry, failure);
			throw failure;
		}
		tile.whenComplete((value, failure) -> {
			Throwable cause = failure;
			if(cause == null) {
				try {
					Entry ready = new Entry(value);
					entry.settled = System.currentTimeMillis();
					entry.result.complete(ready);
				} catch (Throwable unexpected) {
					cause = unexpected;
				}
			}
			if(cause != null) {
				this.fail(entry, cause);
			}
		});
	}

	// A failed tile keeps its place for a short while, so a failure that repeats is not paid for by every request.
	private void fail(Pending entry, Throwable failure) {
		long now = System.currentTimeMillis();
		entry.settled = now;
		entry.retryAt = Math.max(1L, now + this.failureRetryMillis);
		entry.result.completeExceptionally(failure);
	}

	private static boolean failed(CacheEntry<?> entry) {
		return entry instanceof Pending pending && pending.retryAt != 0L;
	}

	private static boolean retryDue(CacheEntry<?> entry) {
		if(entry instanceof Pending pending) {
			long retryAt = pending.retryAt;
			return retryAt != 0L && System.currentTimeMillis() >= retryAt;
		}
		return false;
	}

	private static final class Pending extends CacheEntry<Entry> {
		private final CompletableFuture<Entry> result;
		private volatile long settled;
		private volatile long retryAt;

		private Pending() {
			this(new CompletableFuture<>());
		}

		private Pending(CompletableFuture<Entry> result) {
			super(result);
			this.result = result;
		}

		// A tile still being generated never expires, and a finished one counts from the moment it was ready.
		@Override
		public long getTimestamp() {
			return this.isDone() ? Math.max(super.getTimestamp(), this.settled) : Long.MAX_VALUE;
		}
	}

	private class Entry {
		private AtomicInteger refCount;
		private int chunkCount;
		private Tile tile;

		public Entry(Tile tile) {
			int tileSize = tile.getChunksSize().size();
			this.refCount = new AtomicInteger();
			this.chunkCount = tileSize * tileSize;
			this.tile = tile;
		}

		// The tile is only forgotten here. Readers may still hold it, so its arrays are never reset or reused.
		public boolean drop() {
			return this.refCount.incrementAndGet() >= this.chunkCount;
		}
	}
}