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
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
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
    
    public GeneratorContext(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int tileBorder, int batchCount, @Nullable TileCache cache) {
        this.preset = preset;
        this.noiseLookup = noiseLookup;
        this.seed = new Seed(seed);
        this.levels = new Levels(preset.world().properties.terrainScaler(), preset.world().properties.seaLevel);
        this.noiseEngine = preset.world().noiseEngine == WorldSettings.NoiseEngine.QUICK_V2 ? new QuickNoiseRuntime.Engine() : null;
        this.generator = new TileGenerator(Heightmap.make(this), new WorldFilters(this), tileSize, tileBorder, batchCount, this.noiseEngine);
        this.cache = cache;
        this.lookup = new WorldLookup(this);
    }

    public static GeneratorContext makeCached(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int batchCount, boolean queue) {
    	GeneratorContext ctx = makeUncached(preset, noiseLookup, seed, tileSize, Math.min(2, Math.max(1, preset.filters().erosion.dropletLifetime / 16)), batchCount);
    	ctx.cache = new TileCache(tileSize, queue, ctx.generator);
    	ctx.lookup = new WorldLookup(ctx);
    	return ctx;
    }
    
    public static GeneratorContext makeUncached(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int tileBorder, int batchCount) {
    	return new GeneratorContext(preset, noiseLookup, seed, tileSize, tileBorder, batchCount, null);
    }

	@Override
	public void close() {
		QuickNoiseRuntime.Engine engine = this.noiseEngine;
		if(engine != null) {
			RTFCommon.LOGGER.info("RTF QUICK_V2 released {} compiled graphs and {} CPU fallback graphs", engine.compiledGraphCount(), engine.fallbackGraphCount());
			engine.close();
			this.noiseEngine = null;
		}
	}
}
