package raccoonman.reterraforged.world.worldgen.noise.module;

import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.LongAdder;

import org.jetbrains.annotations.Nullable;

/**
 * Batch/cache layer owned by the original RTF nodes. It does not compile or
 * replace the node graph; each node's own {@link Noise#fill} remains authoritative.
 */
public final class LegacyV2Runtime {
	private static final int DEFAULT_TILE_SIZE = 16;
	private static final int TILE_CACHE_SIZE = 2;
	private static final String TILE_SIZE_PROPERTY = "reterraforged.legacyV2.tileSize";
	private static final String BATCH_ROOTS_PROPERTY = "reterraforged.legacyV2.batchRoots";

	private LegacyV2Runtime() {
	}

	public static boolean batchRootsEnabled() {
		return Boolean.getBoolean(BATCH_ROOTS_PROPERTY);
	}

	public static final class Engine implements NoiseRootRuntime.Engine {
		private final TileGeometry geometry;
		@Nullable
		private final LegacyV2NativeKernels nativeKernels = LegacyV2NativeKernels.createIfEnabled();
		private final Map<Noise, Map<Transform, Entry>> entries = Collections.synchronizedMap(new IdentityHashMap<>());
		private final ThreadLocal<Map<Transform, IdentityHashMap<Noise, Entry>>> localEntries = ThreadLocal.withInitial(HashMap::new);
		private final boolean verify = Boolean.getBoolean("reterraforged.legacyV2.verifyLegacy");
		private final LongAdder sampleCalls = new LongAdder();
		private final LongAdder tileFills = new LongAdder();
		private final LongAdder fallbackCalls = new LongAdder();
		private final LongAdder fillNanos = new LongAdder();
		private volatile boolean closed;

		public static Engine forBatch(int batchBlocks) {
			if(batchBlocks <= 0) {
				throw new IllegalArgumentException("RTF LEGACY_V2 batch width must be positive");
			}
			return new Engine(TileGeometry.of(configuredTileSize()));
		}

		private Engine(TileGeometry geometry) {
			this.geometry = geometry;
		}

		@Override
		public NoiseRootRuntime.Session openSession() {
			if(this.closed) {
				throw new IllegalStateException("The LEGACY_V2 noise engine is closed");
			}
			return new Session(this);
		}

		private float compute(Noise noise, float x, float z, int seed, Session session) {
			if(this.closed) {
				throw new IllegalStateException("The LEGACY_V2 noise engine is closed");
			}
			if(!session.matches(x, z)) {
				this.fallbackCalls.increment();
				return session.computeScalar(noise, x, z, seed);
			}
			IdentityHashMap<Noise, Entry> local = this.localEntries.get().computeIfAbsent(session.transform, ignored -> new IdentityHashMap<>());
			Entry entry = local.get(noise);
			if(entry == null) {
				synchronized(this.entries) {
					Map<Transform, Entry> transforms = this.entries.computeIfAbsent(noise, ignored -> new HashMap<>());
					entry = transforms.computeIfAbsent(session.transform, transform -> noise.supportsBulk()
						? new Sampler(noise, transform, this, this.geometry)
						: new Scalar());
				}
				local.put(noise, entry);
			}
			if(entry instanceof Scalar scalar) {
				scalar.calls.increment();
				this.fallbackCalls.increment();
				return session.computeScalar(noise, x, z, seed);
			}
			Sampler sampler = (Sampler) entry;
			float actual = sampler.sample(seed, session.sampleX, session.sampleZ);
			this.sampleCalls.increment();
			if(this.verify) {
				float expected = session.computeScalar(noise, x, z, seed);
				if(Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(actual)) {
					throw new IllegalStateException("LEGACY_V2 batch mismatch for " + noise.getClass().getName()
						+ " at " + x + "," + z + ": expected=" + expected + ", actual=" + actual);
				}
			}
			return actual;
		}

		public Snapshot snapshot() {
			int roots;
			int fallbackRoots;
			List<RootProfile> profiles;
			synchronized(this.entries) {
				roots = (int) this.entries.values().stream().flatMap(entries -> entries.values().stream()).filter(Sampler.class::isInstance).count();
				fallbackRoots = (int) this.entries.values().stream().flatMap(entries -> entries.values().stream()).filter(Scalar.class::isInstance).count();
				profiles = this.entries.entrySet().stream().flatMap(noiseEntry -> noiseEntry.getValue().values().stream().map(entry -> RootProfile.create(noiseEntry.getKey(), entry))).sorted((left, right) -> Long.compare(right.calls, left.calls)).toList();
			}
			LegacyV2NativeKernels.Snapshot nativeKernels = this.nativeKernels == null
				? new LegacyV2NativeKernels.Snapshot(false, false, 0, 0L, 0L)
				: this.nativeKernels.snapshot();
			return new Snapshot(this.geometry.size, roots, fallbackRoots, this.sampleCalls.sum(), this.tileFills.sum(), this.fallbackCalls.sum(), this.fillNanos.sum(),
				nativeKernels.enabled(), nativeKernels.available(), nativeKernels.programs(), nativeKernels.nativeCalls(), nativeKernels.javaFallbacks(), profiles);
		}

		@Override
		public void close() {
			if(this.closed) {
				return;
			}
			this.closed = true;
			synchronized(this.entries) {
				this.entries.values().forEach(transforms -> transforms.values().forEach(Entry::close));
				this.entries.clear();
			}
			this.localEntries.remove();
			if(this.nativeKernels != null) {
				this.nativeKernels.close();
			}
		}
	}

	public record Snapshot(int tileSize, int roots, int fallbackRoots, long sampleCalls, long tileFills, long fallbackCalls, long fillNanos,
		boolean nativeKernelsEnabled, boolean nativeKernelsAvailable, int nativePrograms, long nativeCalls, long nativeJavaFallbacks, List<RootProfile> profiles) {
	}

	public record RootProfile(String type, String expression, boolean bulk, long calls) {
		private static final int MAX_EXPRESSION_LENGTH = 512;

		private static RootProfile create(Noise noise, Entry entry) {
			String expression = String.valueOf(noise);
			if(expression.length() > MAX_EXPRESSION_LENGTH) {
				expression = expression.substring(0, MAX_EXPRESSION_LENGTH) + "...";
			}
			long calls = entry instanceof Sampler sampler ? sampler.calls.sum() : ((Scalar) entry).calls.sum();
			return new RootProfile(noise.getClass().getName(), expression, entry instanceof Sampler, calls);
		}
	}

	private interface Entry {
		void close();
	}

	private static final class Scalar implements Entry {
		private final LongAdder calls = new LongAdder();

		private Scalar() {
		}

		@Override
		public void close() {
		}
	}

	private static final class Sampler implements Entry {
		private final Noise noise;
		private final Transform transform;
		private final Engine engine;
		private final TileGeometry geometry;
		private final ThreadLocal<TileSet> tiles;
		private final LongAdder calls = new LongAdder();

		private Sampler(Noise noise, Transform transform, Engine engine, TileGeometry geometry) {
			this.noise = noise;
			this.transform = transform;
			this.engine = engine;
			this.geometry = geometry;
			this.tiles = ThreadLocal.withInitial(() -> new TileSet(geometry));
		}

		private float sample(int seed, int sampleX, int sampleZ) {
			this.calls.increment();
			int tileX = sampleX >> this.geometry.bits;
			int tileZ = sampleZ >> this.geometry.bits;
			FieldTile tile = this.tiles.get().get(this.noise, this.transform, seed, tileX, tileZ, this.engine);
			int localX = sampleX & this.geometry.mask;
			int localZ = sampleZ & this.geometry.mask;
			return tile.values[localZ * this.geometry.size + localX];
		}

		@Override
		public void close() {
			this.tiles.remove();
		}
	}

	private static final class TileSet {
		private final TileGeometry geometry;
		private final FieldTile[] entries;
		@Nullable
		private FieldTile current;
		private int cursor;

		private TileSet(TileGeometry geometry) {
			this.geometry = geometry;
			this.entries = new FieldTile[TILE_CACHE_SIZE];
			for(int index = 0; index < this.entries.length; index++) {
				this.entries[index] = new FieldTile(geometry.samples);
			}
		}

		private FieldTile get(Noise noise, Transform transform, int seed, int tileX, int tileZ, Engine engine) {
			if(this.current != null && this.current.matches(seed, tileX, tileZ)) {
				return this.current;
			}
			for(FieldTile entry : this.entries) {
				if(entry.matches(seed, tileX, tileZ)) {
					this.current = entry;
					return entry;
				}
			}
			FieldTile entry = this.entries[this.cursor];
			this.cursor = (this.cursor + 1) % this.entries.length;
			NoiseBatch batch = NoiseBatch.regular(tileX * this.geometry.size, tileZ * this.geometry.size, this.geometry.size, this.geometry.size,
				transform.xScale, transform.xOffset, transform.zScale, transform.zOffset, engine.nativeKernels);
			long start = System.nanoTime();
			noise.fill(batch, seed, entry.values);
			engine.fillNanos.add(System.nanoTime() - start);
			engine.tileFills.increment();
			entry.seed = seed;
			entry.x = tileX;
			entry.z = tileZ;
			entry.valid = true;
			this.current = entry;
			return entry;
		}
	}

	private static final class FieldTile {
		private final float[] values;
		private int seed;
		private int x;
		private int z;
		private boolean valid;

		private FieldTile(int samples) {
			this.values = new float[samples];
		}

		private boolean matches(int seed, int x, int z) {
			return this.valid && this.seed == seed && this.x == x && this.z == z;
		}
	}

	private static final class Session implements NoiseRootRuntime.Session {
		private final Engine engine;
		private Transform transform = Transform.IDENTITY;
		private int sampleX;
		private int sampleZ;
		private float expectedX;
		private float expectedZ;
		private int scalarDepth;
		private boolean prepared;

		private Session(Engine engine) {
			this.engine = engine;
		}

		@Override
		public float computeRoot(Noise noise, float x, float z, int seed) {
			return this.prepared && this.scalarDepth == 0
				? this.engine.compute(noise, x, z, seed, this)
				: noise.compute(x, z, seed);
		}

		@Override
		public void prepareSample(int sampleX, int sampleZ, float xScale, float xOffset, float zScale, float zOffset) {
			this.sampleX = sampleX;
			this.sampleZ = sampleZ;
			this.transform = Transform.of(xScale, xOffset, zScale, zOffset);
			this.expectedX = this.transform.xAt(sampleX);
			this.expectedZ = this.transform.zAt(sampleZ);
			this.prepared = true;
		}

		@Override
		public AutoCloseable scaleCoordinates(float scale) {
			if(!this.prepared) {
				return () -> {
				};
			}
			Transform previous = this.transform;
			this.transform = previous.scale(scale);
			this.updateExpectedCoordinates();
			return () -> {
				this.transform = previous;
				this.updateExpectedCoordinates();
			};
		}

		@Override
		public AutoCloseable legacyCoordinates() {
			this.scalarDepth++;
			return () -> this.scalarDepth--;
		}

		private boolean matches(float x, float z) {
			return Float.floatToRawIntBits(x) == Float.floatToRawIntBits(this.expectedX)
				&& Float.floatToRawIntBits(z) == Float.floatToRawIntBits(this.expectedZ);
		}

		private float computeScalar(Noise noise, float x, float z, int seed) {
			this.scalarDepth++;
			try {
				return noise.compute(x, z, seed);
			} finally {
				this.scalarDepth--;
			}
		}

		private void updateExpectedCoordinates() {
			this.expectedX = this.transform.xAt(this.sampleX);
			this.expectedZ = this.transform.zAt(this.sampleZ);
		}
	}

	private record Transform(float xScale, float xOffset, float zScale, float zOffset) {
		private static final Transform IDENTITY = new Transform(1.0F, 0.0F, 1.0F, 0.0F);

		private static Transform of(float xScale, float xOffset, float zScale, float zOffset) {
			if(!Float.isFinite(xScale) || !Float.isFinite(xOffset) || !Float.isFinite(zScale) || !Float.isFinite(zOffset)
				|| xScale == 0.0F || zScale == 0.0F) {
				throw new IllegalArgumentException("Coordinate transforms must be finite with non-zero scales");
			}
			return new Transform(normalizeZero(xScale), normalizeZero(xOffset), normalizeZero(zScale), normalizeZero(zOffset));
		}

		private Transform scale(float scale) {
			return of(this.xScale * scale, this.xOffset * scale, this.zScale * scale, this.zOffset * scale);
		}

		private float xAt(int x) {
			return x * this.xScale + this.xOffset;
		}

		private float zAt(int z) {
			return z * this.zScale + this.zOffset;
		}

		private static float normalizeZero(float value) {
			return value == 0.0F ? 0.0F : value;
		}
	}

	private record TileGeometry(int bits, int size, int mask, int samples) {
		private static TileGeometry of(int size) {
			if(size < 8 || size > 64 || Integer.bitCount(size) != 1) {
				throw new IllegalArgumentException("RTF LEGACY_V2 tile size must be 8, 16, 32, or 64");
			}
			return new TileGeometry(Integer.numberOfTrailingZeros(size), size, size - 1, size * size);
		}
	}

	private static int configuredTileSize() {
		String configured = System.getProperty(TILE_SIZE_PROPERTY);
		if(configured == null || configured.isBlank()) {
			return DEFAULT_TILE_SIZE;
		}
		try {
			int value = Integer.parseInt(configured.trim());
			return value == 8 || value == 16 || value == 32 || value == 64 ? value : DEFAULT_TILE_SIZE;
		} catch(NumberFormatException ignored) {
			return DEFAULT_TILE_SIZE;
		}
	}
}
