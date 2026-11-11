package raccoonman.reterraforged.world.worldgen;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderGetter;
import raccoonman.reterraforged.concurrent.ThreadPools;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.WorldLookup;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.TileCache;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.generation.TileGenerator;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.util.Seed;

public class GeneratorContext {
    private static final Logger LOGGER = LogManager.getLogger("ReTerraForged");
    public Seed seed;
    public Levels levels;
    public Preset preset;
    public HolderGetter<Noise> noiseLookup;
    public TileGenerator generator;
    @Nullable
    public TileCache cache;
    public WorldLookup lookup;
    
    public GeneratorContext(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int tileBorder, int batchCount, @Nullable TileCache cache) {
        this.preset = preset;
        this.noiseLookup = noiseLookup;
        this.seed = new Seed(seed);
        this.levels = new Levels(preset.world().properties.terrainScaler(), preset.world().properties.seaLevel);
        this.generator = new TileGenerator(Heightmap.make(this), new WorldFilters(this), tileSize, tileBorder, batchCount);
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

    // For contexts that only predict terrain: the same tiles, generated on the background pool so they never queue ahead of world generation.
    public static GeneratorContext makeBackground(Preset preset, HolderGetter<Noise> noiseLookup, int seed, int tileSize, int batchCount, boolean queue) {
    	GeneratorContext ctx = makeCached(preset, noiseLookup, seed, tileSize, batchCount, queue);
    	ctx.generator.setExecutor(ThreadPools.BACKGROUND);
    	return ctx;
    }

    // Ends the cache polls and frees the tiles, river maps and erosion tables. The context keeps answering lookups afterwards:
    // it then keeps nothing for good, and callers that ask for one tile again and again share one tile.
    public void close() {
    	try {
    		TileCache cache = this.cache;
    		if(cache != null) {
    			cache.close();
    		}
    	} finally {
    		this.generator.close();
    	}
    }

    // For a level that is closing: a context that cannot be released must not stop the server from closing its other levels.
    public static void closeQuietly(@Nullable GeneratorContext context) {
    	if(context == null) {
    		return;
    	}
    	try {
    		context.close();
    	} catch (Throwable failure) {
    		LOGGER.error("Could not release a generator context; its caches are left to the garbage collector", failure);
    	}
    }
}
