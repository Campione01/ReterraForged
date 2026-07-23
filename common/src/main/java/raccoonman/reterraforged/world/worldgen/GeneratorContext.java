package raccoonman.reterraforged.world.worldgen;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderGetter;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.WorldLookup;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.TileCache;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator;
import raccoonman.reterraforged.world.worldgen.noise.module.LegacyV2Runtime;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.NoiseRootRuntime;
import raccoonman.reterraforged.world.worldgen.noise.module.QuickNoiseRuntime;
import raccoonman.reterraforged.world.worldgen.util.Seed;

public class GeneratorContext implements AutoCloseable {
    public Seed seed;
    public Levels levels;
    public Preset preset;
    public HolderGetter<Noise> noiseLookup;
    public TileGenerator generator;
    @Nullable
    public TileCache cache;
    public WorldLookup lookup;
    @Nullable
    public QuickNoiseRuntime.Engine noiseEngine;
    @Nullable
    public LegacyV2Runtime.Engine legacyV2Engine;
    @Nullable
    public NoiseRootRuntime.Engine rootNoiseEngine;
    
    public GeneratorContext(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int tileBorder, int batchCount, @Nullable TileCache cache) {
		this(preset, noiseLookup, seed, tileSize, tileBorder, batchCount, cache, true);
	}

	private GeneratorContext(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int tileBorder, int batchCount, @Nullable TileCache cache, boolean batchNoiseRoots) {
        this.preset = preset;
        this.noiseLookup = noiseLookup;
        this.seed = new Seed(seed);
        this.levels = new Levels(preset.world().properties.terrainScaler(), preset.world().properties.seaLevel);
        int batchBlocks = batchBlocks(tileSize, tileBorder, batchCount);
        switch(preset.world().noiseEngine) {
            case QUICK_V2 -> {
                this.noiseEngine = QuickNoiseRuntime.Engine.forBatch(batchBlocks, batchNoiseRoots);
                this.rootNoiseEngine = this.noiseEngine;
            }
            case LEGACY_V2 -> {
				if(LegacyV2Runtime.batchRootsEnabled()) {
					this.legacyV2Engine = LegacyV2Runtime.Engine.forBatch(batchBlocks);
					this.rootNoiseEngine = this.legacyV2Engine;
				}
            }
            case LEGACY -> {
            }
        }
        this.generator = new TileGenerator(Heightmap.make(this), new WorldFilters(this), tileSize, tileBorder, batchCount, this.rootNoiseEngine);
        this.cache = cache;
        this.lookup = new WorldLookup(this);
    }

    public static GeneratorContext makeCached(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int batchCount, boolean queue) {
		int tileBorder = Math.min(2, Math.max(1, preset.filters().erosion.dropletLifetime / 16));
		GeneratorContext ctx = new GeneratorContext(preset, noiseLookup, seed, tileSize, tileBorder, batchCount, null, true);
    	ctx.cache = new TileCache(tileSize, queue, ctx.generator);
    	ctx.lookup = new WorldLookup(ctx);
    	return ctx;
    }
    
    public static GeneratorContext makeUncached(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int tileBorder, int batchCount) {
		return makeUncached(preset, noiseLookup, seed, tileSize, tileBorder, batchCount, false);
	}

	public static GeneratorContext makeUncached(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int tileBorder, int batchCount, boolean batchNoiseRoots) {
		return new GeneratorContext(preset, noiseLookup, seed, tileSize, tileBorder, batchCount, null, batchNoiseRoots);
    }

	@Override
	public void close() {
		QuickNoiseRuntime.Engine engine = this.noiseEngine;
		if(engine != null) {
			RTFCommon.LOGGER.info("RTF QUICK_V2 released {} compiled graphs and {} CPU fallback graphs", engine.compiledGraphCount(), engine.fallbackGraphCount());
			QuickNoiseRuntime.ProfileSnapshot profile = engine.profileSnapshot();
			if(profile.enabled()) {
				long fillNanos = profile.entries().stream().mapToLong(QuickNoiseRuntime.ProfileEntry::fillNanos).sum();
				long tileFills = profile.entries().stream().mapToLong(QuickNoiseRuntime.ProfileEntry::tileFills).sum();
				long sampleCalls = profile.entries().stream().mapToLong(QuickNoiseRuntime.ProfileEntry::sampleCalls).sum();
				String tileGeometry = profile.entries().isEmpty() ? "unused" : profile.entries().getFirst().tileSize() + "x" + profile.entries().getFirst().tileSize() + "/cache=" + profile.entries().getFirst().tileCacheSize();
				RTFCommon.LOGGER.info("RTF QUICK_V2 root profile summary: roots={}, tileGeometry={}, calls={}, tileFills={}, nativeFillMs={}, fallbackCalls={}", profile.entries().size(), tileGeometry, sampleCalls, tileFills, fillNanos / 1_000_000.0D, profile.fallbackCalls());
				profile.entries().stream().limit(32).forEach(entry -> {
					double utilizationPercent = Math.round(entry.utilization() * 10_000.0D) / 100.0D;
					RTFCommon.LOGGER.info("RTF QUICK_V2 root profile: type={}, expressionHash={}, transform={}, nodes={}, tileSize={}, cacheSize={}, calls={}, tileFills={}, utilization={}%, nativeFillMs={}, coordinateFallbacks={}", entry.noiseType(), Integer.toUnsignedString(entry.expression().hashCode(), 16), entry.transform(), entry.nodeCount(), entry.tileSize(), entry.tileCacheSize(), entry.sampleCalls(), entry.tileFills(), utilizationPercent, entry.fillNanos() / 1_000_000.0D, entry.coordinateFallbacks());
				});
			}
			engine.close();
			this.noiseEngine = null;
		}
		LegacyV2Runtime.Engine legacyV2 = this.legacyV2Engine;
		if(legacyV2 != null) {
			LegacyV2Runtime.Snapshot snapshot = legacyV2.snapshot();
			RTFCommon.LOGGER.info("RTF LEGACY_V2 released {} batched roots and {} scalar roots: tileSize={}x{}, calls={}, tileFills={}, fillMs={}, scalarFallbacks={}, nativeEnabled={}, nativeAvailable={}, nativePrograms={}, nativeCalls={}, nativeJavaFallbacks={}",
				snapshot.roots(), snapshot.fallbackRoots(), snapshot.tileSize(), snapshot.tileSize(), snapshot.sampleCalls(), snapshot.tileFills(), snapshot.fillNanos() / 1_000_000.0D,
				snapshot.fallbackCalls(), snapshot.nativeKernelsEnabled(), snapshot.nativeKernelsAvailable(), snapshot.nativePrograms(), snapshot.nativeCalls(), snapshot.nativeJavaFallbacks());
			legacyV2.close();
			this.legacyV2Engine = null;
		}
		this.rootNoiseEngine = null;
	}

	private static int batchBlocks(int tileSize, int tileBorder, int batchCount) {
		int chunks = (1 << tileSize) + 2 * tileBorder;
		int batches = Math.max(1, batchCount);
		int batchChunks = (chunks + batches - 1) / batches;
		return batchChunks << 4;
	}
}
