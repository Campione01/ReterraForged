package raccoonman.reterraforged.world.worldgen.quicknoise;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

import raccoonman.reterraforged.world.worldgen.opencl.OpenClManager;

final class QuickNoiseTileCache {
	static final int NOISE_CELL_WIDTH = 4;
	static final int NOISE_CELL_HEIGHT = 8;
	static final int DEFAULT_MAX_TILES = 128;

	private final long seed;
	private final float chamberBias;
	private final float spaghettiWidth;
	private final float noodleWidth;
	private final int[] octaveSeeds;
	private final int maxTiles;
	private final ConcurrentHashMap<TileKey, float[]> tiles = new ConcurrentHashMap<>();
	private final ConcurrentLinkedQueue<TileKey> insertionOrder = new ConcurrentLinkedQueue<>();
	private final ThreadLocal<LastTile> lastTile = ThreadLocal.withInitial(LastTile::new);
	private final LongAdder generatedTiles = new LongAdder();
	private final LongAdder racedTiles = new LongAdder();
	private final LongAdder evictedTiles = new LongAdder();

	QuickNoiseTileCache(long seed, float chamberBias, float spaghettiWidth, float noodleWidth) {
		this(seed, chamberBias, spaghettiWidth, noodleWidth, DEFAULT_MAX_TILES);
	}

	QuickNoiseTileCache(long seed, float chamberBias, float spaghettiWidth, float noodleWidth, int maxTiles) {
		if(maxTiles < 1) {
			throw new IllegalArgumentException("maxTiles must be positive");
		}
		QuickNoiseNative.requireAvailable();
		this.seed = seed;
		this.chamberBias = chamberBias;
		this.spaghettiWidth = spaghettiWidth;
		this.noodleWidth = noodleWidth;
		this.octaveSeeds = QuickNoiseSeeds.create(seed);
		this.maxTiles = maxTiles;
	}

	float sample(int blockX, int blockY, int blockZ) {
		int cellX = Math.floorDiv(blockX, NOISE_CELL_WIDTH);
		int cellY = Math.floorDiv(blockY, NOISE_CELL_HEIGHT);
		int cellZ = Math.floorDiv(blockZ, NOISE_CELL_WIDTH);
		int tileX = Math.floorDiv(cellX, QuickNoiseNative.TILE_SIZE);
		int tileY = Math.floorDiv(cellY, QuickNoiseNative.TILE_SIZE);
		int tileZ = Math.floorDiv(cellZ, QuickNoiseNative.TILE_SIZE);

		LastTile last = this.lastTile.get();
		float[] values;
		if(last.matches(tileX, tileY, tileZ)) {
			values = last.values;
		} else {
			values = this.tile(tileX, tileY, tileZ);
			last.set(tileX, tileY, tileZ, values);
		}

		int localX = Math.floorMod(cellX, QuickNoiseNative.TILE_SIZE);
		int localY = Math.floorMod(cellY, QuickNoiseNative.TILE_SIZE);
		int localZ = Math.floorMod(cellZ, QuickNoiseNative.TILE_SIZE);
		int index = (localZ * QuickNoiseNative.TILE_SIZE + localY) * QuickNoiseNative.TILE_SIZE + localX;
		return values[index];
	}

	int cachedTileCount() {
		return this.tiles.size();
	}

	long generatedTileCount() {
		return this.generatedTiles.sum();
	}

	long racedTileCount() {
		return this.racedTiles.sum();
	}

	long evictedTileCount() {
		return this.evictedTiles.sum();
	}

	private float[] tile(int tileX, int tileY, int tileZ) {
		TileKey key = new TileKey(tileX, tileY, tileZ);
		float[] cached = this.tiles.get(key);
		if(cached != null) {
			return cached;
		}

		float[] generated = new float[QuickNoiseNative.TILE_SAMPLES];
		boolean filled = OpenClManager.tryFillQuickNoiseTile(this.seed, this.octaveSeeds, tileX, tileY, tileZ, this.chamberBias, this.spaghettiWidth, this.noodleWidth, generated);
		if(!filled && !QuickNoiseNative.fillTile(this.seed, tileX, tileY, tileZ, this.chamberBias, this.spaghettiWidth, this.noodleWidth, generated)) {
			throw new IllegalStateException("RTF QUICK_V1 failed to generate cave density tile " + key);
		}
		this.generatedTiles.increment();

		float[] raced = this.tiles.putIfAbsent(key, generated);
		if(raced != null) {
			this.racedTiles.increment();
			return raced;
		}

		this.insertionOrder.add(key);
		this.evictOldestTiles();
		return generated;
	}

	private void evictOldestTiles() {
		while(this.tiles.size() > this.maxTiles) {
			TileKey oldest = this.insertionOrder.poll();
			if(oldest == null) {
				return;
			}
			if(this.tiles.remove(oldest) != null) {
				this.evictedTiles.increment();
			}
		}
	}

	private record TileKey(int x, int y, int z) {
	}

	private static final class LastTile {
		private int x;
		private int y;
		private int z;
		private float[] values;

		boolean matches(int x, int y, int z) {
			return this.values != null && this.x == x && this.y == y && this.z == z;
		}

		void set(int x, int y, int z, float[] values) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.values = values;
		}
	}
}
