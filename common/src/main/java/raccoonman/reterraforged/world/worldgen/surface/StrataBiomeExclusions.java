package raccoonman.reterraforged.world.worldgen.surface;

import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import raccoonman.reterraforged.RTFCommon;

public final class StrataBiomeExclusions {
	public static final TagKey<Biome> TAG = TagKey.create(Registries.BIOME, RTFCommon.location("strata_excluded"));
	// Alex's Caves surface conditions cannot replace strata above this height.
	public static final int MAX_YIELD_Y = 50;
	public static final StrataBiomeExclusions EXACT = new StrataBiomeExclusions();

	private static final int QUARTS = 6;
	private static final byte NO = 1;
	private static final byte YES = 2;

	private final BiomeManager biomes;
	private final int minX;
	private final int minY;
	private final int minZ;
	private final int height;
	private final byte[] corners;
	private final byte[] cells;

	private StrataBiomeExclusions() {
		this.biomes = null;
		this.minX = this.minY = this.minZ = this.height = 0;
		this.corners = this.cells = null;
	}

	public StrataBiomeExclusions(BiomeManager biomes, ChunkAccess chunk) {
		this.biomes = biomes;
		this.minX = quart(chunk.getPos().getMinBlockX());
		this.minY = quart(chunk.getMinBuildHeight());
		this.minZ = quart(chunk.getPos().getMinBlockZ());
		this.height = quart(chunk.getMaxBuildHeight() - 1) - this.minY + 2;
		this.corners = new byte[QUARTS * QUARTS * this.height];
		this.cells = new byte[(QUARTS - 1) * (QUARTS - 1) * (this.height - 1)];
	}

	public boolean mightExclude(int x, int y, int z) {
		if (this.biomes == null) {
			return true;
		}
		int qx = quart(x) - this.minX;
		int qy = quart(y) - this.minY;
		int qz = quart(z) - this.minZ;
		if ((qx | qy | qz) < 0 || qx >= QUARTS - 1 || qy >= this.height - 1 || qz >= QUARTS - 1) {
			return true;
		}
		int index = (qx * (QUARTS - 1) + qz) * (this.height - 1) + qy;
		byte value = this.cells[index];
		if (value == 0) {
			// Vanilla fuzzy zoom can pick any of these eight holders, not one holder per quart Y.
			boolean tagged = this.tagged(qx, qy, qz) || this.tagged(qx, qy + 1, qz)
					|| this.tagged(qx, qy, qz + 1) || this.tagged(qx, qy + 1, qz + 1)
					|| this.tagged(qx + 1, qy, qz) || this.tagged(qx + 1, qy + 1, qz)
					|| this.tagged(qx + 1, qy, qz + 1) || this.tagged(qx + 1, qy + 1, qz + 1);
			this.cells[index] = value = tagged ? YES : NO;
		}
		return value == YES;
	}

	private boolean tagged(int x, int y, int z) {
		int index = (x * QUARTS + z) * this.height + y;
		byte value = this.corners[index];
		if (value == 0) {
			this.corners[index] = value = this.biomes.getNoiseBiomeAtQuart(this.minX + x, this.minY + y, this.minZ + z)
					.is(TAG) ? YES : NO;
		}
		return value == YES;
	}

	public static int quart(int block) {
		return QuartPos.fromBlock(block - 2);
	}
}
