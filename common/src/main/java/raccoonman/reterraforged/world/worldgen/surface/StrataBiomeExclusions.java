package raccoonman.reterraforged.world.worldgen.surface;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.chunk.ChunkAccess;
import org.jetbrains.annotations.Nullable;
import raccoonman.reterraforged.RTFCommon;

public final class StrataBiomeExclusions {
	public static final TagKey<Biome> TAG = TagKey.create(Registries.BIOME, RTFCommon.location("strata_excluded"));
	// Alex's Caves surface conditions cannot replace strata above this height.
	public static final int MAX_YIELD_Y = 50;
	public static final StrataBiomeExclusions EXACT = new StrataBiomeExclusions();

	private static final int QUARTS = 6;
	private static final byte NO = 1;
	private static final byte YES = 2;
	private static final String BYE_BIOMES = "com.moepus.byepregen.worldgen.biome.";
	private static final ClassValue<Boolean> VANILLA_BOUNDS = new ClassValue<>() {
		@Override
		protected Boolean computeValue(Class<?> owner) {
			String name = owner.getName();
			return (name.equals(BYE_BIOMES + "SurfaceBiomeManager") || name.equals(BYE_BIOMES + "ProfiledSurfaceBiomeManager"))
					&& verifiedByePregenBounds(owner);
		}
	};

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

	private StrataBiomeExclusions(BiomeManager biomes, int minX, int minY, int minZ, int height) {
		this.biomes = biomes;
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.height = height;
		this.corners = new byte[QUARTS * QUARTS * this.height];
		this.cells = new byte[(QUARTS - 1) * (QUARTS - 1) * (this.height - 1)];
	}

	public static boolean hasVanillaBounds(BiomeManager biomes) {
		var type = biomes.getClass();
		return type == BiomeManager.class || VANILLA_BOUNDS.get(type);
	}

	private static boolean verifiedByePregenBounds(Class<?> owner) {
		// These wrappers either prove all eight holders identical or call vanilla's fuzzy lookup.
		var contracts = Map.of(
				"SurfaceBiomeManager", "7E5EB5384D46B004B3C5923F83DB73C4196DEA9B125D75B5E24D4C6F32111C03",
				"ProfiledSurfaceBiomeManager", "82C1E838AF296FC74C0C9CDBF3AC5A4718A46D9DC697C1346E51BF535E70D915",
				"SurfaceBiomeLookup", "BC43598B0CE5CBBE1F7DB8690FC6DDEAB9B68774F3CF16096B96357067F58174",
				"ProfiledSurfaceBiomeManager$ProfiledSurfaceBiomeLookup", "0DF5B710F8D8DCA988A2AAC4B2C703748A943FDEB768E0C75B9FA9C764CD5240",
				"SurfaceBiomeCertificateBuilder", "961DD112F1B3C8A4F14CBADC91841C49706698613ACD9A12E10898F5EF7BB737",
				"SurfaceBiomeCertificates", "BC9C837A211F02073AA4ABD96859F3BC4022DB8CBF5CC55035C1F255A0F1C8AF",
				"SurfaceBiomeLookup$LookupInput", "8A8ACAF60996ACE6D115B884340EED42A53A8F80DD91ACEB402BD182AD4ADA17");
		try {
			var sha256 = MessageDigest.getInstance("SHA-256");
			var hex = HexFormat.of().withUpperCase();
			for (var contract : contracts.entrySet()) {
				String resource = "/" + (BYE_BIOMES + contract.getKey()).replace('.', '/') + ".class";
				try (var input = owner.getResourceAsStream(resource)) {
					if (input == null || !hex.formatHex(sha256.digest(input.readAllBytes())).equals(contract.getValue())) return false;
				}
			}
			return true;
		} catch (IOException | NoSuchAlgorithmException failure) {
			return false;
		}
	}

	public static @Nullable StrataBiomeExclusions forChunk(BiomeManager biomes, ChunkAccess chunk) {
		int maxBlockY = Math.min(MAX_YIELD_Y, chunk.getMaxBuildHeight() - 1);
		if (maxBlockY < chunk.getMinBuildHeight()) {
			return null;
		}
		int minX = quart(chunk.getPos().getMinBlockX());
		int minY = quart(chunk.getMinBuildHeight());
		int minZ = quart(chunk.getPos().getMinBlockZ());
		int height = quart(maxBlockY) - minY + 2;
		int index = 0;
		// Include the upper corner on each axis, including neighbours at the chunk edges.
		for (int x = 0; x < QUARTS; x++) {
			for (int z = 0; z < QUARTS; z++) {
				for (int y = 0; y < height; y++, index++) {
					if (biomes.getNoiseBiomeAtQuart(minX + x, minY + y, minZ + z).is(TAG)) {
						var filter = new StrataBiomeExclusions(biomes, minX, minY, minZ, height);
						Arrays.fill(filter.corners, 0, index, NO);
						filter.corners[index] = YES;
						return filter;
					}
				}
			}
		}
		return null;
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
