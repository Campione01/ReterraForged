package raccoonman.reterraforged.world.worldgen.structure;

import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.core.SectionPos;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.TileCache;

/** Allows uncached one-column structure searches to use unfiltered heights. */
public final class StructureProbe {
	private static final ThreadLocal<Integer> DEPTH = new ThreadLocal<>();
	private static final AtomicBoolean FIRST_BYPASS = new AtomicBoolean();
	private static final AtomicBoolean LOOKUP_FAILED = new AtomicBoolean();

	private StructureProbe() {
	}

	public static void enter() {
		Integer depth = DEPTH.get();
		DEPTH.set(depth == null ? 1 : depth + 1);
	}

	public static void exit() {
		Integer depth = DEPTH.get();
		if(depth == null || depth == 1) {
			DEPTH.remove();
		} else {
			DEPTH.set(depth - 1);
		}
	}

	public static boolean bypass(Object randomState, int cellCountXZ, int minBlockX, int minBlockZ) {
		if(cellCountXZ != 1 || LOOKUP_FAILED.get() || DEPTH.get() == null) {
			return false;
		}
		try {
			if(!(randomState instanceof RTFRandomState rtf)) {
				return false;
			}
			GeneratorContext context = rtf.generatorContext();
			TileCache cache = context == null ? null : context.cache;
			if(cache == null) {
				return false;
			}
			int chunkX = SectionPos.blockToSectionCoord(minBlockX);
			int chunkZ = SectionPos.blockToSectionCoord(minBlockZ);
			// Present entries include in-flight tiles: this waits for them without queuing new work.
			if(cache.provideAtChunkIfPresent(chunkX, chunkZ) != null) {
				return false;
			}
			if(FIRST_BYPASS.compareAndSet(false, true)) {
				RTFCommon.LOGGER.info("Structure probe sampled chunk {},{} per column without building ReTerraForged tile {},{} (first such probe; repeats are not logged)",
					chunkX, chunkZ, cache.chunkToTile(chunkX), cache.chunkToTile(chunkZ));
			}
			return true;
		} catch(Exception | LinkageError failure) {
			if(LOOKUP_FAILED.compareAndSet(false, true)) {
				RTFCommon.LOGGER.error("The tile lookup for a structure probe failed. Structure probes use ReTerraForged's original tile path for the rest of this session.", failure);
			}
			return false;
		}
	}
}
