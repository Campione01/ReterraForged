package raccoonman.reterraforged.world.worldgen.densityfunction.tile;

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
		return entry != null ? entry.get().tile : null;
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
		if(this.queue) {
			this.computeEntry(tileX, tileZ);
		}
	}

	@Override
	public void drop(int tileX, int tileZ) {
		long packedTilePos = PosUtil.pack(tileX, tileZ);
		//TODO i dont think get should be able to return null here
		CacheEntry<Entry> entry = this.cache.get(packedTilePos);
		if(entry != null && entry.get().drop()) {
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
			return entry;
		}
		CompletableFuture<Entry> result = new CompletableFuture<>();
		CacheEntry<Entry> created = CacheEntry.supply(result);
		// A closed cache keeps answering, but must not hold tiles for a level that is gone.
		entry = this.closed ? created : this.cache.computeIfAbsent(packedTilePos, (k) -> created);
		if(entry == created) {
			this.generate(tileX, tileZ, created, result);
		}
		return entry;
	}

	// Runs outside the map lock: allocating and submitting a tile must not block other tile lookups.
	private void generate(int tileX, int tileZ, CacheEntry<Entry> entry, CompletableFuture<Entry> result) {
		CompletableFuture<Tile> tile;
		try {
			tile = this.generator.generate(tileX, tileZ);
		} catch (Throwable failure) {
			this.fail(entry, result, failure);
			throw failure;
		}
		tile.whenComplete((value, failure) -> {
			Throwable cause = failure;
			if(cause == null) {
				try {
					result.complete(new Entry(value));
				} catch (Throwable unexpected) {
					cause = unexpected;
				}
			}
			if(cause != null) {
				this.fail(entry, result, cause);
			}
		});
	}

	// A failed tile is forgotten so that the next request generates it again.
	private void fail(CacheEntry<Entry> entry, CompletableFuture<Entry> result, Throwable failure) {
		this.cache.removeEntry(entry);
		result.completeExceptionally(failure);
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