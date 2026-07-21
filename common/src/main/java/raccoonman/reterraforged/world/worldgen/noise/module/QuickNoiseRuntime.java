package raccoonman.reterraforged.world.worldgen.noise.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import raccoonman.reterraforged.world.worldgen.quicknoise.QuickNoiseNative;

public final class QuickNoiseRuntime {
	private static final int TILE_BITS = 6;
	private static final int TILE_SIZE = 1 << TILE_BITS;
	private static final int TILE_SAMPLES = TILE_SIZE * TILE_SIZE;
	private static final int TILE_CACHE_SIZE = 2;
	private static final float COORDINATE_TOLERANCE = 0.001F;
	private static final ThreadLocal<Context> CURRENT = new ThreadLocal<>();

	private QuickNoiseRuntime() {
	}

	static float compute(Noise noise, float x, float z, int seed) {
		Context context = CURRENT.get();
		return context != null && context.prepared && !context.legacyOnly() ? context.engine.compute(noise, x, z, seed, context) : noise.computeLegacy(x, z, seed);
	}

	public static Scope bind(@Nullable Engine engine) {
		Context previous = CURRENT.get();
		if(engine == null) {
			CURRENT.remove();
		} else {
			CURRENT.set(new Context(engine));
		}
		return new Scope(previous);
	}

	public static void prepareSample(int sampleX, int sampleZ) {
		Context context = CURRENT.get();
		if(context != null) {
			context.sampleX = sampleX;
			context.sampleZ = sampleZ;
			context.setTransform(Transform.IDENTITY);
			context.updateExpectedCoordinates();
			context.prepared = true;
		}
	}

	public static void prepareSample(int sampleX, int sampleZ, float xScale, float xOffset, float zScale, float zOffset) {
		Context context = CURRENT.get();
		if(context == null) {
			return;
		}
		context.sampleX = sampleX;
		context.sampleZ = sampleZ;
		context.setTransform(Transform.of(xScale, xOffset, zScale, zOffset));
		context.updateExpectedCoordinates();
		context.prepared = true;
	}

	public static CoordinateScope scaleCoordinates(float scale) {
		if(!Float.isFinite(scale) || scale == 0.0F) {
			throw new IllegalArgumentException("Coordinate scale must be finite and non-zero");
		}
		Context context = CURRENT.get();
		if(context == null || !context.prepared) {
			return new CoordinateScope(null, null);
		}
		Transform previous = context.transform;
		context.setTransform(previous.scale(scale));
		context.updateExpectedCoordinates();
		return new CoordinateScope(context, previous);
	}

	public static final class Engine implements AutoCloseable {
		private final Map<Noise, Map<Transform, Entry>> entries = Collections.synchronizedMap(new IdentityHashMap<>());
		private final ThreadLocal<Map<Transform, IdentityHashMap<Noise, Entry>>> localEntries = ThreadLocal.withInitial(HashMap::new);
		private volatile boolean closed;

		public Engine() {
			QuickNoiseNative.requireQuickV2Available();
		}

		private float compute(Noise noise, float x, float z, int seed, Context context) {
			if(this.closed) {
				throw new IllegalStateException("The QUICK_V2 noise engine is closed");
			}
			Entry entry = context.entries.get(noise);
			if(entry == null) {
				synchronized(this.entries) {
					Map<Transform, Entry> transforms = this.entries.computeIfAbsent(noise, ignored -> new HashMap<>());
					entry = transforms.computeIfAbsent(context.transform, transform -> compile(noise, transform));
				}
				context.entries.put(noise, entry);
			}
			if(entry instanceof Sampler sampler) {
				if(context.matches(x, z)) {
					return sampler.sample(seed, context.sampleX, context.sampleZ);
				}
			}
			return context.computeLegacy(noise, x, z, seed);
		}

		public int compiledGraphCount() {
			synchronized(this.entries) {
				return (int) this.entries.values().stream().flatMap(entries -> entries.values().stream()).filter(Sampler.class::isInstance).count();
			}
		}

		public int fallbackGraphCount() {
			synchronized(this.entries) {
				return (int) this.entries.values().stream().flatMap(entries -> entries.values().stream()).filter(entry -> entry == Unsupported.INSTANCE).count();
			}
		}

		@Override
		public void close() {
			List<Sampler> samplers = new ArrayList<>();
			synchronized(this.entries) {
				if(this.closed) {
					return;
				}
				this.closed = true;
				for(Map<Transform, Entry> entries : this.entries.values()) {
					for(Entry entry : entries.values()) {
						if(entry instanceof Sampler sampler) {
							samplers.add(sampler);
						}
					}
				}
				this.entries.clear();
			}
			for(Sampler sampler : samplers) {
				sampler.close();
			}
			this.localEntries.remove();
		}

		private static Entry compile(Noise noise, Transform transform) {
			return QuickNoiseGraphCompiler.compile(noise, transform.xScale, transform.xOffset, transform.zScale, transform.zOffset)
				.<Entry>map(graph -> new Sampler(QuickNoiseNative.compileProgram(graph.graph().encode())))
				.orElse(Unsupported.INSTANCE);
		}

		private IdentityHashMap<Noise, Entry> localEntries(Transform transform) {
			return this.localEntries.get().computeIfAbsent(transform, ignored -> new IdentityHashMap<>());
		}
	}

	public static final class Scope implements AutoCloseable {
		@Nullable
		private Context previous;
		private boolean closed;

		private Scope(@Nullable Context previous) {
			this.previous = previous;
		}

		@Override
		public void close() {
			if(this.closed) {
				return;
			}
			this.closed = true;
			if(this.previous == null) {
				CURRENT.remove();
			} else {
				CURRENT.set(this.previous);
			}
			this.previous = null;
		}
	}

	public static final class CoordinateScope implements AutoCloseable {
		@Nullable
		private Context context;
		@Nullable
		private Transform previous;

		private CoordinateScope(@Nullable Context context, @Nullable Transform previous) {
			this.context = context;
			this.previous = previous;
		}

		@Override
		public void close() {
			if(this.context == null) {
				return;
			}
			if(CURRENT.get() != this.context) {
				throw new IllegalStateException("QUICK_V2 coordinate scopes must close on the creating thread");
			}
			this.context.setTransform(this.previous);
			this.context.updateExpectedCoordinates();
			this.context = null;
			this.previous = null;
		}
	}

	private interface Entry {
	}

	private enum Unsupported implements Entry {
		INSTANCE
	}

	private static final class Sampler implements Entry, AutoCloseable {
		private final QuickNoiseNative.Program program;
		private final ThreadLocal<TileSet> tiles = ThreadLocal.withInitial(TileSet::new);

		private Sampler(QuickNoiseNative.Program program) {
			if(program.outputs() != 1) {
				program.close();
				throw new IllegalArgumentException("A runtime QUICK_V2 noise graph must have exactly one output");
			}
			this.program = program;
		}

		private float sample(int seed, int sampleX, int sampleZ) {
			int tileX = sampleX >> TILE_BITS;
			int tileZ = sampleZ >> TILE_BITS;
			FieldTile tile = this.tiles.get().get(this.program, seed, tileX, tileZ);
			int localX = sampleX & (TILE_SIZE - 1);
			int localZ = sampleZ & (TILE_SIZE - 1);
			return tile.values[localZ * TILE_SIZE + localX];
		}

		@Override
		public void close() {
			this.tiles.remove();
			this.program.close();
		}
	}

	private static final class Context {
		private final Engine engine;
		private Transform transform = Transform.IDENTITY;
		private IdentityHashMap<Noise, Entry> entries;
		private int sampleX;
		private int sampleZ;
		private float expectedX;
		private float expectedZ;
		private float xTolerance;
		private float zTolerance;
		private int legacyDepth;
		private boolean prepared;

		private Context(Engine engine) {
			this.engine = engine;
			this.entries = engine.localEntries(this.transform);
		}

		private void setTransform(Transform transform) {
			if(!this.transform.equals(transform)) {
				this.transform = transform;
				this.entries = this.engine.localEntries(transform);
			}
		}

		private void updateExpectedCoordinates() {
			this.expectedX = this.transform.xAt(this.sampleX);
			this.expectedZ = this.transform.zAt(this.sampleZ);
			if(this.transform == Transform.IDENTITY) {
				this.xTolerance = 0.0F;
				this.zTolerance = 0.0F;
			} else {
				this.xTolerance = Math.max(COORDINATE_TOLERANCE, Math.ulp(this.expectedX) * 4.0F);
				this.zTolerance = Math.max(COORDINATE_TOLERANCE, Math.ulp(this.expectedZ) * 4.0F);
			}
		}

		private boolean matches(float x, float z) {
			return Math.abs(x - this.expectedX) <= this.xTolerance && Math.abs(z - this.expectedZ) <= this.zTolerance;
		}

		private boolean legacyOnly() {
			return this.legacyDepth > 0;
		}

		private float computeLegacy(Noise noise, float x, float z, int seed) {
			this.legacyDepth++;
			try {
				return noise.computeLegacy(x, z, seed);
			} finally {
				this.legacyDepth--;
			}
		}
	}

	private record Transform(float xScale, float xOffset, float zScale, float zOffset) {
		private static final Transform IDENTITY = new Transform(1.0F, 0.0F, 1.0F, 0.0F);

		private static Transform of(float xScale, float xOffset, float zScale, float zOffset) {
			if(!Float.isFinite(xScale) || !Float.isFinite(xOffset) || !Float.isFinite(zScale) || !Float.isFinite(zOffset) || xScale == 0.0F || zScale == 0.0F) {
				throw new IllegalArgumentException("Coordinate transforms must be finite with non-zero scales");
			}
			xScale = normalizeZero(xScale);
			xOffset = normalizeZero(xOffset);
			zScale = normalizeZero(zScale);
			zOffset = normalizeZero(zOffset);
			return xScale == 1.0F && xOffset == 0.0F && zScale == 1.0F && zOffset == 0.0F ? IDENTITY : new Transform(xScale, xOffset, zScale, zOffset);
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

	private static final class TileSet {
		private final FieldTile[] entries = new FieldTile[TILE_CACHE_SIZE];
		@Nullable
		private FieldTile current;
		private int cursor;

		private TileSet() {
			for(int index = 0; index < this.entries.length; index++) {
				this.entries[index] = new FieldTile();
			}
		}

		private FieldTile get(QuickNoiseNative.Program program, int seed, int tileX, int tileZ) {
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
			program.fill(seed, tileX * TILE_SIZE, tileZ * TILE_SIZE, TILE_SIZE, TILE_SIZE, entry.values);
			entry.seed = seed;
			entry.x = tileX;
			entry.z = tileZ;
			entry.valid = true;
			this.current = entry;
			return entry;
		}
	}

	private static final class FieldTile {
		private final float[] values = new float[TILE_SAMPLES];
		private int seed = Integer.MIN_VALUE;
		private int x = Integer.MIN_VALUE;
		private int z = Integer.MIN_VALUE;
		private boolean valid;

		private boolean matches(int seed, int x, int z) {
			return this.valid && this.seed == seed && this.x == x && this.z == z;
		}
	}
}
