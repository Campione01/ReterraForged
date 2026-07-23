package raccoonman.reterraforged.world.worldgen.noise.module;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.jetbrains.annotations.Nullable;

import raccoonman.reterraforged.world.worldgen.quicknoise.QuickNoiseNative;

public final class QuickNoiseRuntime {
	private static final int DEFAULT_TILE_SIZE = 64;
	private static final int RTF_TILE_SIZE = 32;
	private static final int MIN_TILE_SIZE = 16;
	private static final int MAX_TILE_SIZE = 64;
	private static final int DEFAULT_TILE_CACHE_SIZE = 2;
	private static final int MAX_TILE_CACHE_SIZE = 64;
	private static final String TILE_SIZE_PROPERTY = "reterraforged.quickNoise.tileSize";
	private static final String BATCH_ROOTS_PROPERTY = "reterraforged.quickNoise.batchRoots";
	private static final String BATCH_ROOT_LIMIT_PROPERTY = "reterraforged.quickNoise.batchRootLimit";
	private static final String BATCH_PROMOTION_TILES_PROPERTY = "reterraforged.quickNoise.batchPromotionTiles";

	private QuickNoiseRuntime() {
	}

	public static Scope bind(@Nullable Engine engine) {
		return new Scope(NoiseRootRuntime.bind(engine));
	}

	public static void prepareSample(int sampleX, int sampleZ) {
		NoiseRootRuntime.prepareSample(sampleX, sampleZ);
	}

	public static void prepareSample(int sampleX, int sampleZ, float xScale, float xOffset, float zScale, float zOffset) {
		NoiseRootRuntime.prepareSample(sampleX, sampleZ, xScale, xOffset, zScale, zOffset);
	}

	public static CoordinateScope scaleCoordinates(float scale) {
		return new CoordinateScope(NoiseRootRuntime.scaleCoordinates(scale));
	}

	public static LegacyScope legacyCoordinates() {
		return new LegacyScope(NoiseRootRuntime.legacyCoordinates());
	}

	public static final class Engine implements NoiseRootRuntime.Engine {
		private final TileGeometry geometry;
		private final Map<Noise, Map<Transform, Entry>> entries = Collections.synchronizedMap(new IdentityHashMap<>());
		private final Map<Transform, List<RootRegistration>> discoveredRoots = new HashMap<>();
		private final Map<Transform, Integer> batchedRootCounts = new HashMap<>();
		private final Map<Transform, BatchOptimization> activeBatchOptimizations = new HashMap<>();
		private final Set<AutoCloseable> retiredSamplers = Collections.newSetFromMap(new IdentityHashMap<>());
		private final ThreadLocal<LocalEntries> localEntries = ThreadLocal.withInitial(LocalEntries::new);
		private final Map<Noise, SemanticMismatch> semanticMismatches = Collections.synchronizedMap(new IdentityHashMap<>());
		private final boolean verifyLegacy = Boolean.getBoolean("reterraforged.quickNoise.verifyLegacy");
		private final boolean profile = Boolean.getBoolean("reterraforged.quickNoise.profile");
		private final boolean batchRoots;
		private final int batchRootLimit = configuredBatchRootLimit();
		private final int batchPromotionTiles = configuredBatchPromotionTiles();
		@Nullable
		private final LongAdder fallbackCalls = this.profile ? new LongAdder() : null;
		private volatile int entriesVersion;
		private int completedTileGenerations;
		private boolean observingRoots;
		private boolean rootPromotionComplete;
		private volatile boolean closed;

		public Engine() {
			this(TileGeometry.of(DEFAULT_TILE_SIZE, DEFAULT_TILE_CACHE_SIZE), true);
		}

		private Engine(TileGeometry geometry, boolean batchRoots) {
			this.geometry = geometry;
			this.batchRoots = batchRoots && Boolean.parseBoolean(System.getProperty(BATCH_ROOTS_PROPERTY, "false"));
			this.observingRoots = this.batchRoots && this.batchPromotionTiles == 0;
			QuickNoiseNative.requireQuickV2Available();
		}

		@Override
		public NoiseRootRuntime.Session openSession() {
			return new Context(this);
		}

		@Override
		public void afterTileGeneration() {
			this.optimizeDiscoveredRoots();
		}

		public static Engine forBatch(int batchBlocks) {
			return forBatch(batchBlocks, true);
		}

		public static Engine forBatch(int batchBlocks, boolean batchRoots) {
			if(batchBlocks <= 0) {
				throw new IllegalArgumentException("RTF QUICK_V2 batch width must be positive");
			}
			int tileSize = configuredTileSize(batchBlocks <= MIN_TILE_SIZE ? MIN_TILE_SIZE : RTF_TILE_SIZE);
			int cacheSize = Math.max(DEFAULT_TILE_CACHE_SIZE, ceilDiv(batchBlocks + tileSize - 1, tileSize));
			return new Engine(TileGeometry.of(tileSize, Math.min(cacheSize, MAX_TILE_CACHE_SIZE)), batchRoots);
		}

		private float compute(Noise noise, float x, float z, int seed, Context context) {
			if(this.closed) {
				throw new IllegalStateException("The QUICK_V2 noise engine is closed");
			}
			if(!context.matches(x, z)) {
				Entry cached = context.entries.get(noise);
				if(cached instanceof Accelerated accelerated) {
					accelerated.recordCoordinateFallback();
				}
				if(this.fallbackCalls != null) {
					this.fallbackCalls.increment();
				}
				return context.computeJava(noise, x, z, seed);
			}
			Entry entry = context.entries.get(noise);
			if(entry == null) {
				synchronized(this.entries) {
					Map<Transform, Entry> transforms = this.entries.computeIfAbsent(noise, ignored -> new HashMap<>());
					entry = transforms.get(context.transform);
					if(entry == null) {
						entry = compile(noise, context.transform, this.geometry, this.profile, this.observingRoots);
						transforms.put(context.transform, entry);
						if(entry instanceof Sampler sampler) {
							this.discoveredRoots.computeIfAbsent(context.transform, ignored -> new ArrayList<>()).add(new RootRegistration(noise, sampler.nodeCount, sampler));
						}
					}
				}
				context.entries.put(noise, entry);
			}
			if(entry instanceof Accelerated accelerated) {
				float actual = accelerated.sample(seed, context.sampleX, context.sampleZ);
				if(this.verifyLegacy) {
					float expected = context.computeJava(noise, x, z, seed);
					if(Float.floatToRawIntBits(expected) != Float.floatToRawIntBits(actual)) {
						this.semanticMismatches.computeIfAbsent(noise, ignored -> SemanticMismatch.create(noise, context.transform, x, z, seed, expected, actual));
					}
				}
				return actual;
			}
			if(this.fallbackCalls != null) {
				this.fallbackCalls.increment();
			}
			return context.computeJava(noise, x, z, seed);
		}

		public int compiledGraphCount() {
			synchronized(this.entries) {
				return (int) this.entries.values().stream().flatMap(entries -> entries.values().stream()).filter(Accelerated.class::isInstance).count();
			}
		}

		public BatchOptimization optimizeDiscoveredRoots() {
			if(this.profile || !this.batchRoots) {
				return BatchOptimization.NONE;
			}
			int groups = 0;
			int roots = 0;
			int separateNodes = 0;
			int batchNodes = 0;
			boolean changed = false;
			synchronized(this.entries) {
				if(this.closed || this.rootPromotionComplete) {
					return BatchOptimization.NONE;
				}
				this.completedTileGenerations++;
				if(!this.observingRoots) {
					if(this.completedTileGenerations < this.batchPromotionTiles) {
						return BatchOptimization.NONE;
					}
					for(List<RootRegistration> registrations : this.discoveredRoots.values()) {
						registrations.forEach(registration -> registration.sampler.beginCoverageObservation());
					}
					this.observingRoots = true;
					return BatchOptimization.NONE;
				}
				for(Map.Entry<Transform, List<RootRegistration>> discovered : this.discoveredRoots.entrySet()) {
					Transform transform = discovered.getKey();
					List<RootRegistration> registrations = discovered.getValue();
					if(registrations.size() < 2 || this.batchedRootCounts.getOrDefault(transform, 0) == registrations.size()) {
						continue;
					}
					Map<Set<TileKey>, List<RootRegistration>> coverageGroups = new HashMap<>();
					for(RootRegistration registration : registrations) {
						coverageGroups.computeIfAbsent(registration.sampler.freezeCoverage(), ignored -> new ArrayList<>()).add(registration);
					}
					List<BatchBuild> builds = new ArrayList<>();
					boolean failed = false;
					for(List<RootRegistration> coverageRoots : coverageGroups.values()) {
						for(int from = 0; from < coverageRoots.size(); from += this.batchRootLimit) {
							int to = Math.min(from + this.batchRootLimit, coverageRoots.size());
							if(to - from < 2) {
								continue;
							}
							List<RootRegistration> batchRoots = coverageRoots.subList(from, to);
							List<Noise> noises = batchRoots.stream().map(RootRegistration::noise).toList();
							QuickNoiseGraphCompiler.CompiledBatch compiled = QuickNoiseGraphCompiler.compileBatch(noises, transform.xScale, transform.xOffset, transform.zScale, transform.zOffset).orElse(null);
							if(compiled == null) {
								failed = true;
								break;
							}
							try {
								builds.add(new BatchBuild(batchRoots, new BatchSampler(QuickNoiseNative.compileProgram(compiled.graph().encode()), this.geometry), compiled.graph().nodeCount()));
							} catch(RuntimeException exception) {
								failed = true;
								break;
							}
						}
						if(failed) {
							break;
						}
					}
					if(failed) {
						builds.forEach(BatchBuild::close);
						continue;
					}
					int transformGroups = 0;
					int transformRoots = 0;
					int transformSeparateNodes = 0;
					int transformBatchNodes = 0;
					for(BatchBuild build : builds) {
						List<RootRegistration> batchRoots = build.roots;
						BatchSampler sampler = build.sampler;
						for(int output = 0; output < batchRoots.size(); output++) {
							Noise noise = batchRoots.get(output).noise;
							Map<Transform, Entry> transforms = this.entries.get(noise);
							Entry previous = transforms.put(transform, new BatchView(sampler, output));
							this.retire(previous);
						}
						transformGroups++;
						transformRoots += batchRoots.size();
						transformSeparateNodes += batchRoots.stream().mapToInt(RootRegistration::nodeCount).sum();
						transformBatchNodes += build.nodeCount;
					}
					this.batchedRootCounts.put(transform, registrations.size());
					BatchOptimization optimization = new BatchOptimization(transformGroups, transformRoots, transformSeparateNodes, transformBatchNodes);
					this.activeBatchOptimizations.put(transform, optimization);
					groups += optimization.groups;
					roots += optimization.roots;
					separateNodes += optimization.separateNodes;
					batchNodes += optimization.batchNodes;
					changed |= transformGroups > 0;
				}
				if(changed) {
					this.entriesVersion++;
				}
				this.observingRoots = false;
				this.rootPromotionComplete = true;
			}
			return new BatchOptimization(groups, roots, separateNodes, batchNodes);
		}

		public BatchOptimization batchOptimizationSnapshot() {
			synchronized(this.entries) {
				int groups = 0;
				int roots = 0;
				int separateNodes = 0;
				int batchNodes = 0;
				for(BatchOptimization optimization : this.activeBatchOptimizations.values()) {
					groups += optimization.groups;
					roots += optimization.roots;
					separateNodes += optimization.separateNodes;
					batchNodes += optimization.batchNodes;
				}
				return new BatchOptimization(groups, roots, separateNodes, batchNodes);
			}
		}

		public int fallbackGraphCount() {
			synchronized(this.entries) {
				return (int) this.entries.values().stream().flatMap(entries -> entries.values().stream()).filter(entry -> entry == Unsupported.INSTANCE).count();
			}
		}

		public List<SemanticMismatch> semanticMismatches() {
			synchronized(this.semanticMismatches) {
				return List.copyOf(this.semanticMismatches.values());
			}
		}

		public ProfileSnapshot profileSnapshot() {
			if(!this.profile) {
				return ProfileSnapshot.DISABLED;
			}
			List<ProfileEntry> profiles = new ArrayList<>();
			synchronized(this.entries) {
				for(Map<Transform, Entry> transforms : this.entries.values()) {
					for(Entry entry : transforms.values()) {
						if(entry instanceof Accelerated accelerated) {
							ProfileEntry profile = accelerated.profile();
							if(profile != null) {
								profiles.add(profile);
							}
						}
					}
				}
			}
			profiles.sort(Comparator.comparingLong(ProfileEntry::fillNanos).reversed());
			return new ProfileSnapshot(true, this.fallbackCalls.sum(), List.copyOf(profiles));
		}

		@Override
		public void close() {
			Set<AutoCloseable> samplers = Collections.newSetFromMap(new IdentityHashMap<>());
			synchronized(this.entries) {
				if(this.closed) {
					return;
				}
				this.closed = true;
				for(Map<Transform, Entry> entries : this.entries.values()) {
					for(Entry entry : entries.values()) {
						this.collectSampler(entry, samplers);
					}
				}
				samplers.addAll(this.retiredSamplers);
				this.entries.clear();
				this.discoveredRoots.clear();
				this.batchedRootCounts.clear();
				this.activeBatchOptimizations.clear();
				this.retiredSamplers.clear();
				this.semanticMismatches.clear();
			}
			for(AutoCloseable sampler : samplers) {
				try {
					sampler.close();
				} catch(Exception exception) {
					throw new IllegalStateException("Failed to close a QUICK_V2 sampler", exception);
				}
			}
			this.localEntries.remove();
		}

		private static Entry compile(Noise noise, Transform transform, TileGeometry geometry, boolean profile, boolean observeCoverage) {
			return QuickNoiseGraphCompiler.compile(noise, transform.xScale, transform.xOffset, transform.zScale, transform.zOffset)
				.<Entry>map(graph -> new Sampler(
					QuickNoiseNative.compileProgram(graph.graph().encode()),
					geometry,
					graph.graph().nodeCount(),
					profile ? SamplerStats.create(noise, transform, graph.graph().nodeCount(), geometry) : null,
					observeCoverage
				))
				.orElse(Unsupported.INSTANCE);
		}

		private IdentityHashMap<Noise, Entry> localEntries(Transform transform) {
			LocalEntries entries = this.localEntries.get();
			int version = this.entriesVersion;
			if(entries.version != version) {
				entries.entries.clear();
				entries.version = version;
			}
			return entries.entries.computeIfAbsent(transform, ignored -> new IdentityHashMap<>());
		}

		private void retire(@Nullable Entry entry) {
			if(entry instanceof Sampler sampler) {
				this.retiredSamplers.add(sampler);
			} else if(entry instanceof BatchView view) {
				this.retiredSamplers.add(view.sampler);
			}
		}

		private void collectSampler(Entry entry, Set<AutoCloseable> samplers) {
			if(entry instanceof Sampler sampler) {
				samplers.add(sampler);
			} else if(entry instanceof BatchView view) {
				samplers.add(view.sampler);
			}
		}
	}

	public record BatchOptimization(int groups, int roots, int separateNodes, int batchNodes) {
		private static final BatchOptimization NONE = new BatchOptimization(0, 0, 0, 0);
	}

	public record SemanticMismatch(String noiseType, String expression, float x, float z, int seed, String transform, float expected, float actual) {
		private static final int MAX_EXPRESSION_LENGTH = 4000;

		private static SemanticMismatch create(Noise noise, Transform transform, float x, float z, int seed, float expected, float actual) {
			String expression = String.valueOf(noise);
			if(expression.length() > MAX_EXPRESSION_LENGTH) {
				expression = expression.substring(0, MAX_EXPRESSION_LENGTH) + "...";
			}
			return new SemanticMismatch(noise.getClass().getName(), expression, x, z, seed, transform.toString(), expected, actual);
		}
	}

	public record ProfileSnapshot(boolean enabled, long fallbackCalls, List<ProfileEntry> entries) {
		private static final ProfileSnapshot DISABLED = new ProfileSnapshot(false, 0L, List.of());
	}

	public record ProfileEntry(String noiseType, String expression, String transform, int nodeCount, int tileSize, int tileCacheSize, long sampleCalls, long currentTileHits, long retainedTileHits, long tileFills, long computedSamples, long coordinateFallbacks, long fillNanos, double utilization) {
	}

	public static final class Scope implements AutoCloseable {
		@Nullable
		private NoiseRootRuntime.Scope delegate;

		private Scope(NoiseRootRuntime.Scope delegate) {
			this.delegate = delegate;
		}

		@Override
		public void close() {
			if(this.delegate == null) {
				return;
			}
			this.delegate.close();
			this.delegate = null;
		}
	}

	public static final class CoordinateScope implements AutoCloseable {
		@Nullable
		private NoiseRootRuntime.CoordinateScope delegate;

		private CoordinateScope(NoiseRootRuntime.CoordinateScope delegate) {
			this.delegate = delegate;
		}

		@Override
		public void close() {
			if(this.delegate == null) {
				return;
			}
			this.delegate.close();
			this.delegate = null;
		}
	}

	private interface Entry {
	}

	private interface Accelerated extends Entry {
		float sample(int seed, int sampleX, int sampleZ);

		void recordCoordinateFallback();

		@Nullable
		ProfileEntry profile();
	}

	private enum Unsupported implements Entry {
		INSTANCE
	}

	private static final class Sampler implements Accelerated, AutoCloseable {
		private final QuickNoiseNative.Program program;
		private final TileGeometry geometry;
		private final int nodeCount;
		@Nullable
		private volatile Set<TileKey> observedTiles;
		@Nullable
		private Set<TileKey> frozenCoverage;
		private final ThreadLocal<TileSet> tiles;
		@Nullable
		private final SamplerStats stats;

		private Sampler(QuickNoiseNative.Program program, TileGeometry geometry, int nodeCount, @Nullable SamplerStats stats, boolean observeCoverage) {
			if(program.outputs() != 1) {
				program.close();
				throw new IllegalArgumentException("A runtime QUICK_V2 noise graph must have exactly one output");
			}
			this.program = program;
			this.geometry = geometry;
			this.nodeCount = nodeCount;
			this.tiles = ThreadLocal.withInitial(() -> new TileSet(geometry, 1));
			this.stats = stats;
			this.observedTiles = observeCoverage ? ConcurrentHashMap.newKeySet() : null;
		}

		@Override
		public float sample(int seed, int sampleX, int sampleZ) {
			if(this.stats != null) {
				this.stats.sampleCalls.increment();
			}
			int tileX = sampleX >> this.geometry.bits;
			int tileZ = sampleZ >> this.geometry.bits;
			FieldTile tile = this.tiles.get().get(this.program, seed, tileX, tileZ, this.stats, this.observedTiles);
			int localX = sampleX & this.geometry.mask;
			int localZ = sampleZ & this.geometry.mask;
			return tile.values[localZ * this.geometry.size + localX];
		}

		@Override
		public void recordCoordinateFallback() {
			if(this.stats != null) {
				this.stats.coordinateFallbacks.increment();
			}
		}

		@Nullable
		public ProfileEntry profile() {
			return this.stats == null ? null : this.stats.snapshot();
		}

		private synchronized Set<TileKey> freezeCoverage() {
			if(this.frozenCoverage == null) {
				Set<TileKey> observedTiles = this.observedTiles;
				this.frozenCoverage = observedTiles == null ? Set.of() : Set.copyOf(observedTiles);
				this.observedTiles = null;
			}
			return this.frozenCoverage;
		}

		private synchronized void beginCoverageObservation() {
			if(this.frozenCoverage == null && this.observedTiles == null) {
				this.observedTiles = ConcurrentHashMap.newKeySet();
			}
		}

		@Override
		public void close() {
			this.tiles.remove();
			this.program.close();
		}
	}

	private static final class Context implements NoiseRootRuntime.Session {
		private final Engine engine;
		private Transform transform = Transform.IDENTITY;
		private IdentityHashMap<Noise, Entry> entries;
		private int sampleX;
		private int sampleZ;
		private float expectedX;
		private float expectedZ;
		private int legacyDepth;
		private boolean prepared;

		private Context(Engine engine) {
			this.engine = engine;
			this.entries = engine.localEntries(this.transform);
		}

		@Override
		public float computeRoot(Noise noise, float x, float z, int seed) {
			return this.prepared && !this.legacyOnly() && QuickNoiseGraphCompiler.isRtfGraph(noise)
				? this.engine.compute(noise, x, z, seed, this)
				: noise.compute(x, z, seed);
		}

		@Override
		public void prepareSample(int sampleX, int sampleZ, float xScale, float xOffset, float zScale, float zOffset) {
			this.sampleX = sampleX;
			this.sampleZ = sampleZ;
			this.setTransform(Transform.of(xScale, xOffset, zScale, zOffset));
			this.updateExpectedCoordinates();
			this.prepared = true;
		}

		@Override
		public AutoCloseable scaleCoordinates(float scale) {
			if(!this.prepared) {
				return () -> {
				};
			}
			Transform previous = this.transform;
			this.setTransform(previous.scale(scale));
			this.updateExpectedCoordinates();
			return new TransformScope(this, previous);
		}

		@Override
		public AutoCloseable legacyCoordinates() {
			this.legacyDepth++;
			return new LegacyToken(this);
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
		}

		private boolean matches(float x, float z) {
			return Float.floatToRawIntBits(x) == Float.floatToRawIntBits(this.expectedX)
				&& Float.floatToRawIntBits(z) == Float.floatToRawIntBits(this.expectedZ);
		}

		private boolean legacyOnly() {
			return this.legacyDepth > 0;
		}

		private float computeJava(Noise noise, float x, float z, int seed) {
			this.legacyDepth++;
			try {
				return noise.compute(x, z, seed);
			} finally {
				this.legacyDepth--;
			}
		}
	}

	private static final class TransformScope implements AutoCloseable {
		@Nullable
		private Context context;
		private final Transform previous;

		private TransformScope(Context context, Transform previous) {
			this.context = context;
			this.previous = previous;
		}

		@Override
		public void close() {
			if(this.context != null) {
				this.context.setTransform(this.previous);
				this.context.updateExpectedCoordinates();
				this.context = null;
			}
		}
	}

	private static final class LegacyToken implements AutoCloseable {
		@Nullable
		private Context context;

		private LegacyToken(Context context) {
			this.context = context;
		}

		@Override
		public void close() {
			if(this.context != null) {
				this.context.legacyDepth--;
				this.context = null;
			}
		}
	}

	private static final class BatchView implements Accelerated {
		private final BatchSampler sampler;
		private final int output;

		private BatchView(BatchSampler sampler, int output) {
			this.sampler = sampler;
			this.output = output;
		}

		@Override
		public float sample(int seed, int sampleX, int sampleZ) {
			return this.sampler.sample(this.output, seed, sampleX, sampleZ);
		}

		@Override
		public void recordCoordinateFallback() {
		}

		@Override
		@Nullable
		public ProfileEntry profile() {
			return null;
		}
	}

	private static final class BatchSampler implements AutoCloseable {
		private final QuickNoiseNative.Program program;
		private final TileGeometry geometry;
		private final int outputs;
		private final ThreadLocal<TileSet> tiles;

		private BatchSampler(QuickNoiseNative.Program program, TileGeometry geometry) {
			if(program.outputs() < 2 || program.outputs() > 64) {
				program.close();
				throw new IllegalArgumentException("A runtime QUICK_V2 batch must have between 2 and 64 outputs");
			}
			this.program = program;
			this.geometry = geometry;
			this.outputs = program.outputs();
			this.tiles = ThreadLocal.withInitial(() -> new TileSet(geometry, this.outputs));
		}

		private float sample(int output, int seed, int sampleX, int sampleZ) {
			int tileX = sampleX >> this.geometry.bits;
			int tileZ = sampleZ >> this.geometry.bits;
			FieldTile tile = this.tiles.get().get(this.program, seed, tileX, tileZ, null, null);
			int localX = sampleX & this.geometry.mask;
			int localZ = sampleZ & this.geometry.mask;
			return tile.values[output * this.geometry.samples + localZ * this.geometry.size + localX];
		}

		@Override
		public void close() {
			this.tiles.remove();
			this.program.close();
		}
	}

	private record RootRegistration(Noise noise, int nodeCount, Sampler sampler) {
	}

	private record TileKey(int seed, int x, int z) {
	}

	private record BatchBuild(List<RootRegistration> roots, BatchSampler sampler, int nodeCount) {
		private void close() {
			this.sampler.close();
		}
	}

	private static final class LocalEntries {
		private final Map<Transform, IdentityHashMap<Noise, Entry>> entries = new HashMap<>();
		private int version = -1;
	}

	public static final class LegacyScope implements AutoCloseable {
		@Nullable
		private NoiseRootRuntime.LegacyScope delegate;

		private LegacyScope(NoiseRootRuntime.LegacyScope delegate) {
			this.delegate = delegate;
		}

		@Override
		public void close() {
			if(this.delegate == null) {
				return;
			}
			this.delegate.close();
			this.delegate = null;
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
		private final TileGeometry geometry;
		private final FieldTile[] entries;
		@Nullable
		private FieldTile current;
		private int cursor;

		private TileSet(TileGeometry geometry, int outputs) {
			this.geometry = geometry;
			this.entries = new FieldTile[geometry.cacheSize];
			for(int index = 0; index < this.entries.length; index++) {
				this.entries[index] = new FieldTile(Math.multiplyExact(geometry.samples, outputs));
			}
		}

		private FieldTile get(QuickNoiseNative.Program program, int seed, int tileX, int tileZ, @Nullable SamplerStats stats, @Nullable Set<TileKey> observedTiles) {
			if(this.current != null && this.current.matches(seed, tileX, tileZ)) {
				if(stats != null) {
					stats.currentTileHits.increment();
				}
				return this.current;
			}
			for(FieldTile entry : this.entries) {
				if(entry.matches(seed, tileX, tileZ)) {
					if(stats != null) {
						stats.retainedTileHits.increment();
					}
					this.current = entry;
					return entry;
				}
			}
			FieldTile entry = this.entries[this.cursor];
			this.cursor = (this.cursor + 1) % this.entries.length;
			long start = stats == null ? 0L : System.nanoTime();
			program.fill(seed, tileX * this.geometry.size, tileZ * this.geometry.size, this.geometry.size, this.geometry.size, entry.values);
			if(stats != null) {
				stats.tileFills.increment();
				stats.fillNanos.add(System.nanoTime() - start);
			}
			entry.seed = seed;
			entry.x = tileX;
			entry.z = tileZ;
			entry.valid = true;
			if(observedTiles != null) {
				observedTiles.add(new TileKey(seed, tileX, tileZ));
			}
			this.current = entry;
			return entry;
		}
	}

	private static final class FieldTile {
		private final float[] values;
		private int seed = Integer.MIN_VALUE;
		private int x = Integer.MIN_VALUE;
		private int z = Integer.MIN_VALUE;
		private boolean valid;

		private FieldTile(int samples) {
			this.values = new float[samples];
		}

		private boolean matches(int seed, int x, int z) {
			return this.valid && this.seed == seed && this.x == x && this.z == z;
		}
	}

	private static final class SamplerStats {
		private static final int MAX_EXPRESSION_LENGTH = 512;
		private final String noiseType;
		private final String expression;
		private final String transform;
		private final int nodeCount;
		private final int tileSize;
		private final int tileCacheSize;
		private final int tileSamples;
		private final LongAdder sampleCalls = new LongAdder();
		private final LongAdder currentTileHits = new LongAdder();
		private final LongAdder retainedTileHits = new LongAdder();
		private final LongAdder tileFills = new LongAdder();
		private final LongAdder coordinateFallbacks = new LongAdder();
		private final LongAdder fillNanos = new LongAdder();

		private SamplerStats(String noiseType, String expression, String transform, int nodeCount, TileGeometry geometry) {
			this.noiseType = noiseType;
			this.expression = expression;
			this.transform = transform;
			this.nodeCount = nodeCount;
			this.tileSize = geometry.size;
			this.tileCacheSize = geometry.cacheSize;
			this.tileSamples = geometry.samples;
		}

		private static SamplerStats create(Noise noise, Transform transform, int nodeCount, TileGeometry geometry) {
			String expression = String.valueOf(noise);
			if(expression.length() > MAX_EXPRESSION_LENGTH) {
				expression = expression.substring(0, MAX_EXPRESSION_LENGTH) + "...";
			}
			return new SamplerStats(noise.getClass().getName(), expression, transform.toString(), nodeCount, geometry);
		}

		private ProfileEntry snapshot() {
			long samples = this.sampleCalls.sum();
			long fills = this.tileFills.sum();
			long computedSamples = fills * (long) this.tileSamples;
			double utilization = computedSamples == 0L ? 0.0D : (double) samples / computedSamples;
			return new ProfileEntry(
				this.noiseType,
				this.expression,
				this.transform,
				this.nodeCount,
				this.tileSize,
				this.tileCacheSize,
				samples,
				this.currentTileHits.sum(),
				this.retainedTileHits.sum(),
				fills,
				computedSamples,
				this.coordinateFallbacks.sum(),
				this.fillNanos.sum(),
				utilization
			);
		}
	}

	private record TileGeometry(int bits, int size, int mask, int samples, int cacheSize) {
		private static TileGeometry of(int size, int cacheSize) {
			if(size < MIN_TILE_SIZE || size > MAX_TILE_SIZE || Integer.bitCount(size) != 1) {
				throw new IllegalArgumentException("RTF QUICK_V2 tile size must be 16, 32, or 64");
			}
			if(cacheSize <= 0) {
				throw new IllegalArgumentException("RTF QUICK_V2 tile cache size must be positive");
			}
			return new TileGeometry(Integer.numberOfTrailingZeros(size), size, size - 1, size * size, cacheSize);
		}
	}

	private static int configuredTileSize(int fallback) {
		String configured = System.getProperty(TILE_SIZE_PROPERTY);
		if(configured == null || configured.isBlank()) {
			return fallback;
		}
		try {
			int value = Integer.parseInt(configured.trim());
			return value == 16 || value == 32 || value == 64 ? value : fallback;
		} catch(NumberFormatException ignored) {
			return fallback;
		}
	}

	private static int configuredBatchRootLimit() {
		String configured = System.getProperty(BATCH_ROOT_LIMIT_PROPERTY);
		if(configured == null || configured.isBlank()) {
			return 32;
		}
		try {
			int value = Integer.parseInt(configured.trim());
			return value >= 2 && value <= 64 ? value : 32;
		} catch(NumberFormatException ignored) {
			return 32;
		}
	}

	private static int configuredBatchPromotionTiles() {
		String configured = System.getProperty(BATCH_PROMOTION_TILES_PROPERTY);
		if(configured == null || configured.isBlank()) {
			return 4;
		}
		try {
			int value = Integer.parseInt(configured.trim());
			return value >= 0 && value <= 64 ? value : 4;
		} catch(NumberFormatException ignored) {
			return 4;
		}
	}

	private static int ceilDiv(int value, int divisor) {
		return (value + divisor - 1) / divisor;
	}

}
