package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.fail;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.chunk.storage.RegionFileVersion;

/**
 * Read-only, on-disk comparison for two client-generated overworlds.
 *
 * <p>The comparison deliberately excludes region timestamps, DataVersion, entities,
 * block entities, lighting, ticks, and structure metadata. Base-terrain parity
 * uses paired WORLD_SURFACE_WG heights and solid/fluid/empty occupancy below the
 * surface. Exact block states are reported separately as a decoration-sensitive
 * diagnostic. This is runtime heuristic evidence, not the mathematical density
 * parity gate.</p>
 */
class ClientRegionParityTest {
	private static final String WORLD_A_PROPERTY = "rtf.client.worldA";
	private static final String WORLD_B_PROPERTY = "rtf.client.worldB";
	private static final String CENTER_CHUNK_X_PROPERTY = "rtf.client.centerChunkX";
	private static final String CENTER_CHUNK_Z_PROPERTY = "rtf.client.centerChunkZ";
	private static final String RADIUS_PROPERTY = "rtf.client.radius";
	private static final String MIN_FULL_CHUNKS_PROPERTY = "rtf.client.minFullChunks";
	private static final int DEFAULT_MIN_FULL_CHUNKS = 32;
	private static final Pattern REGION_NAME = Pattern.compile("r\\.(-?\\d+)\\.(-?\\d+)\\.mca");
	private static final int SECTOR_BYTES = 4096;
	private static final int REGION_HEADER_BYTES = SECTOR_BYTES * 2;
	private static final int CHUNKS_PER_REGION = 32;
	private static final String AIR = "minecraft:air";
	private static final byte[] AIR_HASH_TOKEN = hashToken(AIR);

	@Test
	void comparesGeneratedClientRegions() throws Exception {
		String worldAProperty = System.getProperty(WORLD_A_PROPERTY);
		String worldBProperty = System.getProperty(WORLD_B_PROPERTY);
		Assumptions.assumeTrue(worldAProperty != null && !worldAProperty.isBlank(),
			"Set -D" + WORLD_A_PROPERTY + "=<A75-world-directory>");
		Assumptions.assumeTrue(worldBProperty != null && !worldBProperty.isBlank(),
			"Set -D" + WORLD_B_PROPERTY + "=<current-world-directory>");

		Path worldA = Path.of(worldAProperty).toAbsolutePath().normalize();
		Path worldB = Path.of(worldBProperty).toAbsolutePath().normalize();
		Long seedA = readWorldSeed(worldA);
		Long seedB = readWorldSeed(worldB);
		if(seedA != null && seedB != null && !seedA.equals(seedB)) {
			fail("World generation seeds differ: A=" + seedA + " B=" + seedB);
		}
		Path regionA = resolveRegionDirectory(worldA);
		Path regionB = resolveRegionDirectory(worldB);
		Map<ChunkPos, ChunkRef> chunksA = indexRegions(regionA);
		Map<ChunkPos, ChunkRef> chunksB = indexRegions(regionB);
		Set<ChunkPos> common = new TreeSet<>(chunksA.keySet());
		common.retainAll(chunksB.keySet());
		int commonBeforeFilter = common.size();
		ChunkFilter filter = ChunkFilter.fromProperties();
		common.removeIf(pos -> !filter.includes(pos));

		Analysis analysis = new Analysis(worldA, worldB, chunksA.size(), chunksB.size(),
			commonBeforeFilter, common.size(), filter, seedA, seedB);
		for(ChunkPos pos : common) {
			ChunkTerrain a = readTerrain(chunksA.get(pos));
			ChunkTerrain b = readTerrain(chunksB.get(pos));
			if(!a.status.equals(b.status)) {
				analysis.statusMismatches++;
			}
			if(!a.isFull() || !b.isFull()) {
				analysis.skippedNonFull++;
				continue;
			}
			analysis.compare(pos, a, b);
		}

		int minimumFullChunks = minimumFullChunks();
		if(analysis.comparedChunks < minimumFullChunks) {
			fail("Insufficient common FULL chunks for a useful runtime comparison: "
				+ analysis.comparedChunks + " < " + minimumFullChunks + ".\n" + analysis.summary());
		}

		String summary = analysis.summary();
		System.out.println("RTF_CLIENT_REGION_PARITY\n" + summary);
		if(analysis.hasMismatch()) {
			fail(analysis.failureReport(summary));
		}
		System.out.println(analysis.sampleHeightmap + " firstAvailableY sample chunk=" + analysis.samplePos + "\n"
			+ formatHeightMatrix(analysis.sampleHeights));
	}

	private static Path resolveRegionDirectory(Path world) throws IOException {
		if(!Files.isDirectory(world)) {
			throw new IOException("World directory does not exist: " + world);
		}
		Path nested = world.resolve("region");
		if(Files.isDirectory(nested)) {
			return nested;
		}
		if(world.getFileName() != null && world.getFileName().toString().equals("region")) {
			return world;
		}
		throw new IOException("No overworld region directory under: " + world);
	}

	private static int minimumFullChunks() {
		String value = System.getProperty(MIN_FULL_CHUNKS_PROPERTY);
		if(value == null || value.isBlank()) {
			return DEFAULT_MIN_FULL_CHUNKS;
		}
		try {
			int parsed = Integer.parseInt(value);
			if(parsed < 1) {
				throw new IllegalArgumentException("-D" + MIN_FULL_CHUNKS_PROPERTY + " must be positive");
			}
			return parsed;
		} catch(NumberFormatException exception) {
			throw new IllegalArgumentException(
				"-D" + MIN_FULL_CHUNKS_PROPERTY + " is not an integer: " + value,
				exception
			);
		}
	}

	private static Long readWorldSeed(Path world) throws IOException {
		Path levelDat = world.resolve("level.dat");
		if(!Files.isRegularFile(levelDat) && world.getFileName() != null
				&& world.getFileName().toString().equals("region")) {
			levelDat = world.getParent().resolve("level.dat");
		}
		if(!Files.isRegularFile(levelDat)) {
			return null;
		}
		CompoundTag root = NbtIo.readCompressed(levelDat, NbtAccounter.unlimitedHeap());
		CompoundTag data = root.getCompound("Data");
		CompoundTag worldGenSettings = data.getCompound("WorldGenSettings");
		if(!worldGenSettings.contains("seed", Tag.TAG_ANY_NUMERIC)) {
			throw new IOException("No Data.WorldGenSettings.seed in " + levelDat);
		}
		return worldGenSettings.getLong("seed");
	}

	private static Map<ChunkPos, ChunkRef> indexRegions(Path regionDirectory) throws IOException {
		Map<ChunkPos, ChunkRef> chunks = new TreeMap<>();
		try(var paths = Files.list(regionDirectory)) {
			for(Path file : paths.filter(Files::isRegularFile).sorted().toList()) {
				Matcher matcher = REGION_NAME.matcher(file.getFileName().toString());
				if(!matcher.matches()) {
					continue;
				}
				int regionX = Integer.parseInt(matcher.group(1));
				int regionZ = Integer.parseInt(matcher.group(2));
				indexRegionFile(file, regionX, regionZ, chunks);
			}
		}
		if(chunks.isEmpty()) {
			throw new IOException("No allocated Anvil chunks found in: " + regionDirectory);
		}
		return chunks;
	}

	private static void indexRegionFile(Path file, int regionX, int regionZ, Map<ChunkPos, ChunkRef> chunks)
			throws IOException {
		try(FileChannel channel = FileChannel.open(file, StandardOpenOption.READ)) {
			if(channel.size() == 0L) {
				return;
			}
			if(channel.size() < REGION_HEADER_BYTES) {
				throw new IOException("Truncated region header: " + file);
			}
			ByteBuffer locations = ByteBuffer.allocate(SECTOR_BYTES);
			readFully(channel, locations, 0L, file);
			locations.flip();
			for(int index = 0; index < CHUNKS_PER_REGION * CHUNKS_PER_REGION; index++) {
				int location = locations.getInt();
				int sectorOffset = location >>> 8;
				int sectorCount = location & 0xFF;
				if(sectorOffset == 0 && sectorCount == 0) {
					continue;
				}
				if(sectorOffset < 2 || sectorCount == 0) {
					throw new IOException("Invalid location entry index=" + index + " in " + file);
				}
				int localX = index & 31;
				int localZ = index >>> 5;
				ChunkPos pos = new ChunkPos(regionX * CHUNKS_PER_REGION + localX,
					regionZ * CHUNKS_PER_REGION + localZ);
				ChunkRef previous = chunks.put(pos, new ChunkRef(file, sectorOffset, sectorCount, pos));
				if(previous != null) {
					throw new IOException("Duplicate chunk coordinate " + pos + " in " + file
						+ " and " + previous.file);
				}
			}
		}
	}

	private static ChunkTerrain readTerrain(ChunkRef ref) throws IOException {
		CompoundTag root;
		try(InputStream decoded = openChunk(ref);
				DataInputStream input = new DataInputStream(new BufferedInputStream(decoded))) {
			root = NbtIo.read(input);
		}
		if(root == null) {
			throw new IOException("Empty chunk NBT: " + ref);
		}
		CompoundTag chunk = root.contains("Level", Tag.TAG_COMPOUND) ? root.getCompound("Level") : root;
		int nbtX = chunk.getInt("xPos");
		int nbtZ = chunk.getInt("zPos");
		if(nbtX != ref.pos.x || nbtZ != ref.pos.z) {
			throw new IOException("Chunk coordinate mismatch for " + ref + ": NBT=(" + nbtX + "," + nbtZ + ")");
		}

		String status = chunk.getString("Status");
		if(!ChunkTerrain.isFullStatus(status)) {
			return ChunkTerrain.nonFull(status);
		}
		ListTag sectionTags = chunk.getList("sections", Tag.TAG_COMPOUND);
		Map<Integer, Section> sections = new TreeMap<>();
		for(int index = 0; index < sectionTags.size(); index++) {
			CompoundTag sectionTag = sectionTags.getCompound(index);
			int sectionY = sectionTag.getByte("Y");
			if(sections.containsKey(sectionY)) {
				throw new IOException("Duplicate section Y=" + sectionY + " in " + ref);
			}
			sections.put(sectionY, Section.read(sectionTag, ref));
		}
		if(sections.isEmpty()) {
			throw new IOException("Chunk has no sections: " + ref);
		}

		int minSectionY = chunk.contains("yPos", Tag.TAG_ANY_NUMERIC)
			? chunk.getInt("yPos")
			: sections.keySet().stream().min(Integer::compareTo).orElseThrow();
		int maxSectionY = sections.keySet().stream().max(Integer::compareTo).orElse(minSectionY);
		CompoundTag heightmaps = chunk.getCompound("Heightmaps");
		int[] worldSurfaceWg = heightmaps.contains("WORLD_SURFACE_WG", Tag.TAG_LONG_ARRAY)
			? decodeHeightmap(heightmaps.getLongArray("WORLD_SURFACE_WG"), minSectionY, maxSectionY, ref)
			: null;
		int[] worldSurface = heightmaps.contains("WORLD_SURFACE", Tag.TAG_LONG_ARRAY)
			? decodeHeightmap(heightmaps.getLongArray("WORLD_SURFACE"), minSectionY, maxSectionY, ref)
			: null;
		if(worldSurfaceWg == null && worldSurface == null) {
			throw new IOException("Chunk has no WORLD_SURFACE heightmap: " + ref + " status=" + status);
		}
		return new ChunkTerrain(status, worldSurfaceWg, worldSurface, sections);
	}

	private static InputStream openChunk(ChunkRef ref) throws IOException {
		byte[] payload;
		int versionByte;
		try(FileChannel channel = FileChannel.open(ref.file, StandardOpenOption.READ)) {
			long chunkOffset = (long)ref.sectorOffset * SECTOR_BYTES;
			ByteBuffer header = ByteBuffer.allocate(5);
			readFully(channel, header, chunkOffset, ref.file);
			header.flip();
			int storedLength = header.getInt();
			versionByte = Byte.toUnsignedInt(header.get());
			if(storedLength < 1) {
				throw new IOException("Invalid stored chunk length " + storedLength + " for " + ref);
			}
			boolean external = (versionByte & 0x80) != 0;
			if(external) {
				if(storedLength != 1) {
					throw new IOException("External chunk has invalid stored length " + storedLength + " for " + ref);
				}
				Path externalFile = ref.file.resolveSibling("c." + ref.pos.x + "." + ref.pos.z + ".mcc");
				if(!Files.isRegularFile(externalFile)) {
					throw new IOException("Missing external chunk payload: " + externalFile);
				}
				payload = Files.readAllBytes(externalFile);
			} else {
				long end = chunkOffset + Integer.BYTES + storedLength;
				if(end > channel.size()) {
					throw new IOException("Chunk payload extends beyond " + ref.file + " for " + ref);
				}
				long allocatedBytes = (long)ref.sectorCount * SECTOR_BYTES;
				if(ref.sectorCount != 0xFF && Integer.BYTES + (long)storedLength > allocatedBytes) {
					throw new IOException("Chunk payload exceeds its allocated sectors for " + ref);
				}
				payload = new byte[storedLength - 1];
				ByteBuffer data = ByteBuffer.wrap(payload);
				readFully(channel, data, chunkOffset + 5L, ref.file);
			}
		}

		int versionId = versionByte & 0x7F;
		RegionFileVersion version = RegionFileVersion.fromId(versionId);
		if(version == null) {
			throw new IOException("Unsupported region compression id=" + versionId + " for " + ref);
		}
		return version.wrap(new ByteArrayInputStream(payload));
	}

	private static int[] decodeHeightmap(long[] packed, int minSectionY, int maxSectionY, ChunkRef ref)
			throws IOException {
		if(packed.length == 0) {
			throw new IOException("Empty WORLD_SURFACE heightmap in " + ref);
		}
		int worldHeight = Math.max(16, (maxSectionY - minSectionY + 1) * 16);
		int preferredBits = ceilLog2(worldHeight + 1);
		List<Integer> candidates = new ArrayList<>();
		for(int bits = 1; bits <= 32; bits++) {
			if(packedLongCount(256, bits) == packed.length) {
				candidates.add(bits);
			}
		}
		if(candidates.isEmpty()) {
			throw new IOException("Cannot infer heightmap bit width from " + packed.length + " longs in " + ref);
		}
		int bits = candidates.contains(preferredBits) ? preferredBits : candidates.get(0);
		int minY = minSectionY * 16;
		int[] heights = new int[256];
		for(int index = 0; index < heights.length; index++) {
			heights[index] = Math.toIntExact(unpack(packed, index, bits)) + minY;
		}
		return heights;
	}

	private static long unpack(long[] data, int index, int bits) {
		int valuesPerLong = 64 / bits;
		int longIndex = index / valuesPerLong;
		int bitIndex = (index % valuesPerLong) * bits;
		long mask = (1L << bits) - 1L;
		return data[longIndex] >>> bitIndex & mask;
	}

	private static int packedLongCount(int valueCount, int bits) {
		int valuesPerLong = 64 / bits;
		return (valueCount + valuesPerLong - 1) / valuesPerLong;
	}

	private static int ceilLog2(int value) {
		return value <= 1 ? 0 : Integer.SIZE - Integer.numberOfLeadingZeros(value - 1);
	}

	private static void readFully(FileChannel channel, ByteBuffer target, long position, Path source)
			throws IOException {
		while(target.hasRemaining()) {
			int read = channel.read(target, position);
			if(read < 0) {
				throw new IOException("Unexpected EOF while reading " + source);
			}
			position += read;
		}
	}

	private static MessageDigest sha256() {
		try {
			return MessageDigest.getInstance("SHA-256");
		} catch(NoSuchAlgorithmException e) {
			throw new IllegalStateException(e);
		}
	}

	private static void updateInt(MessageDigest digest, int value) {
		digest.update((byte)(value >>> 24));
		digest.update((byte)(value >>> 16));
		digest.update((byte)(value >>> 8));
		digest.update((byte)value);
	}

	private static byte[] hashToken(String value) {
		byte[] text = value.getBytes(StandardCharsets.UTF_8);
		ByteBuffer token = ByteBuffer.allocate(Integer.BYTES + text.length);
		token.putInt(text.length).put(text);
		return token.array();
	}

	private static String formatHeightMatrix(int[] heights) {
		StringBuilder result = new StringBuilder();
		result.append("rows=localZ, columns=localX\n");
		for(int z = 0; z < 16; z++) {
			result.append(String.format(Locale.ROOT, "z=%02d", z));
			for(int x = 0; x < 16; x++) {
				result.append(String.format(Locale.ROOT, " %4d", heights[z * 16 + x]));
			}
			result.append('\n');
		}
		return result.toString();
	}

	private record ChunkPos(int x, int z) implements Comparable<ChunkPos> {
		@Override
		public int compareTo(ChunkPos other) {
			int xComparison = Integer.compare(this.x, other.x);
			return xComparison != 0 ? xComparison : Integer.compare(this.z, other.z);
		}

		@Override
		public String toString() {
			return "(" + this.x + "," + this.z + ")";
		}
	}

	private record ChunkRef(Path file, int sectorOffset, int sectorCount, ChunkPos pos) {
	}

	private record ChunkFilter(Integer centerX, Integer centerZ, int radius) {
		static ChunkFilter fromProperties() {
			String xValue = System.getProperty(CENTER_CHUNK_X_PROPERTY);
			String zValue = System.getProperty(CENTER_CHUNK_Z_PROPERTY);
			String radiusValue = System.getProperty(RADIUS_PROPERTY);
			if((xValue == null) != (zValue == null)) {
				throw new IllegalArgumentException("Set both -D" + CENTER_CHUNK_X_PROPERTY
					+ " and -D" + CENTER_CHUNK_Z_PROPERTY);
			}
			if(xValue == null) {
				if(radiusValue != null) {
					throw new IllegalArgumentException("-D" + RADIUS_PROPERTY + " requires center chunk properties");
				}
				return new ChunkFilter(null, null, 0);
			}
			int centerX = parseProperty(CENTER_CHUNK_X_PROPERTY, xValue);
			int centerZ = parseProperty(CENTER_CHUNK_Z_PROPERTY, zValue);
			int radius = radiusValue == null ? 16 : parseProperty(RADIUS_PROPERTY, radiusValue);
			if(radius < 0 || radius > 1024) {
				throw new IllegalArgumentException("-D" + RADIUS_PROPERTY + " must be between 0 and 1024");
			}
			return new ChunkFilter(centerX, centerZ, radius);
		}

		private static int parseProperty(String property, String value) {
			try {
				return Integer.parseInt(value);
			} catch(NumberFormatException e) {
				throw new IllegalArgumentException("-D" + property + " must be an integer: " + value, e);
			}
		}

		boolean includes(ChunkPos pos) {
			return this.centerX == null
				|| Math.abs((long)pos.x - this.centerX) <= this.radius
					&& Math.abs((long)pos.z - this.centerZ) <= this.radius;
		}

		@Override
		public String toString() {
			return this.centerX == null ? "none"
				: "centerChunk=(" + this.centerX + "," + this.centerZ + ") chebyshevRadius=" + this.radius;
		}
	}

	private record ChunkTerrain(String status, int[] worldSurfaceWg, int[] worldSurface,
			Map<Integer, Section> sections) {
		static ChunkTerrain nonFull(String status) {
			return new ChunkTerrain(status, null, null, Map.of());
		}

		static boolean isFullStatus(String status) {
			return status.equals("full") || status.equals("minecraft:full");
		}

		boolean isFull() {
			return isFullStatus(this.status);
		}

		String state(int localX, int blockY, int localZ) throws IOException {
			Section section = this.sections.get(Math.floorDiv(blockY, 16));
			if(section == null) {
				return AIR;
			}
			int index = Math.floorMod(blockY, 16) << 8 | localZ << 4 | localX;
			return section.state(section.paletteIndex(index));
		}

		int minBlockY() {
			return this.sections.keySet().stream().min(Integer::compareTo).orElseThrow() * 16;
		}
	}

	private record HeightSelection(String name, int[] heightsA, int[] heightsB, boolean fallback) {
		static HeightSelection select(ChunkPos pos, ChunkTerrain a, ChunkTerrain b) throws IOException {
			if(a.worldSurfaceWg != null && b.worldSurfaceWg != null) {
				return new HeightSelection("WORLD_SURFACE_WG", a.worldSurfaceWg, b.worldSurfaceWg, false);
			}
			if(a.worldSurface != null && b.worldSurface != null) {
				return new HeightSelection("DERIVED_BASE_SURFACE(fallback-from-WORLD_SURFACE)",
					deriveBaseSurface(a), deriveBaseSurface(b), true);
			}
			throw new IOException("No symmetric surface heightmap pair for chunk " + pos
				+ ": A(WG/final)=" + (a.worldSurfaceWg != null) + "/" + (a.worldSurface != null)
				+ " B(WG/final)=" + (b.worldSurfaceWg != null) + "/" + (b.worldSurface != null));
		}

		private static int[] deriveBaseSurface(ChunkTerrain terrain) throws IOException {
			int[] derived = terrain.worldSurface.clone();
			int minBlockY = terrain.minBlockY();
			for(int localZ = 0; localZ < 16; localZ++) {
				for(int localX = 0; localX < 16; localX++) {
					int index = localZ * 16 + localX;
					int blockY = terrain.worldSurface[index] - 1;
					while(blockY >= minBlockY) {
						BaseOccupancy occupancy = BaseOccupancy.of(terrain.state(localX, blockY, localZ));
						if(occupancy != BaseOccupancy.EMPTY && occupancy != BaseOccupancy.IGNORE) {
							break;
						}
						blockY--;
					}
					derived[index] = blockY + 1;
				}
			}
			return derived;
		}
	}

	private enum BaseOccupancy {
		EMPTY,
		FLUID,
		SOLID,
		IGNORE;

		static BaseOccupancy of(String state) {
			String name = state.substring(0, state.indexOf('[') >= 0 ? state.indexOf('[') : state.length());
			int separator = name.indexOf(':');
			String path = separator >= 0 ? name.substring(separator + 1) : name;
			if(path.equals("air") || path.equals("cave_air") || path.equals("void_air")) {
				return EMPTY;
			}
			if(path.equals("water") || path.equals("lava") || path.equals("bubble_column")
					|| path.equals("ice") || path.equals("packed_ice") || path.equals("blue_ice")
					|| path.equals("frosted_ice") || path.contains("seagrass")
					|| path.equals("kelp") || path.equals("kelp_plant")) {
				return FLUID;
			}
			if(isDecoration(path)) {
				return IGNORE;
			}
			return SOLID;
		}

		private static boolean isDecoration(String path) {
			return path.endsWith("_leaves") || path.endsWith("_sapling")
				|| path.endsWith("_flower") || path.endsWith("_mushroom")
				|| path.endsWith("_fungus") || path.endsWith("_roots")
				|| path.endsWith("_vines") || path.endsWith("_vine")
				|| path.endsWith("_grass") || path.endsWith("_fern")
				|| path.endsWith("_bush") || path.endsWith("_torch")
				|| path.endsWith("_wall_torch") || path.endsWith("_banner")
				|| path.endsWith("_wall_banner") || path.endsWith("_sign")
				|| path.endsWith("_wall_sign") || path.endsWith("_hanging_sign")
				|| path.endsWith("_wall_hanging_sign") || path.endsWith("_log")
				|| path.endsWith("_wood") || path.endsWith("_stem")
				|| path.endsWith("_hyphae") || path.endsWith("_sprouts")
				|| path.endsWith("_tuft") || path.endsWith("_carpet")
				|| path.endsWith("_puddle") || path.endsWith("_patch")
				|| path.endsWith("_pile") || path.endsWith("_shard")
				|| path.contains("speleothem") || path.contains("icicle")
				|| path.contains("succulent") || path.equals("short_grass")
				|| path.equals("tall_grass") || path.equals("fern") || path.equals("large_fern")
				|| path.equals("dead_bush") || path.equals("sugar_cane") || path.equals("cactus")
				|| path.equals("bamboo") || path.equals("bamboo_sapling") || path.equals("vine")
				|| path.equals("glow_lichen") || path.equals("snow") || path.equals("fire")
				|| path.equals("soul_fire") || path.equals("cobweb") || path.equals("hanging_roots")
				|| path.equals("spore_blossom") || path.equals("pointed_dripstone")
				|| path.equals("bush") || path.equals("rose") || path.equals("crocus")
				|| path.equals("iris");
		}
	}

	private static final class Section {
		private final String[] palette;
		private final byte[][] hashTokens;
		private final long[] data;
		private final int bits;

		private Section(String[] palette, long[] data, int bits) {
			this.palette = palette;
			this.hashTokens = Arrays.stream(palette).map(ClientRegionParityTest::hashToken).toArray(byte[][]::new);
			this.data = data;
			this.bits = bits;
		}

		static Section read(CompoundTag section, ChunkRef ref) throws IOException {
			if(!section.contains("block_states", Tag.TAG_COMPOUND)) {
				return null;
			}
			CompoundTag blockStates = section.getCompound("block_states");
			ListTag paletteTags = blockStates.getList("palette", Tag.TAG_COMPOUND);
			if(paletteTags.isEmpty()) {
				throw new IOException("Empty block-state palette in " + ref);
			}
			String[] palette = new String[paletteTags.size()];
			for(int index = 0; index < palette.length; index++) {
				palette[index] = canonicalState(paletteTags.getCompound(index), ref);
			}
			if(palette.length == 1) {
				return new Section(palette, new long[0], 0);
			}
			if(!blockStates.contains("data", Tag.TAG_LONG_ARRAY)) {
				throw new IOException("Missing packed block-state data in " + ref);
			}
			int bits = Math.max(4, ceilLog2(palette.length));
			long[] data = blockStates.getLongArray("data");
			int expectedLongs = packedLongCount(4096, bits);
			if(data.length != expectedLongs) {
				throw new IOException("Packed block-state length mismatch in " + ref + ": palette="
					+ palette.length + " bits=" + bits + " expectedLongs=" + expectedLongs
					+ " actualLongs=" + data.length);
			}
			return new Section(palette, data, bits);
		}

		private static String canonicalState(CompoundTag paletteEntry, ChunkRef ref) throws IOException {
			String name = paletteEntry.getString("Name");
			if(name.isBlank()) {
				throw new IOException("Block-state palette entry has no Name in " + ref);
			}
			if(!paletteEntry.contains("Properties", Tag.TAG_COMPOUND)) {
				return name;
			}
			CompoundTag properties = paletteEntry.getCompound("Properties");
			List<String> keys = properties.getAllKeys().stream().sorted(Comparator.naturalOrder()).toList();
			if(keys.isEmpty()) {
				return name;
			}
			StringBuilder result = new StringBuilder(name).append('[');
			for(int index = 0; index < keys.size(); index++) {
				if(index > 0) {
					result.append(',');
				}
				String key = keys.get(index);
				result.append(key).append('=').append(properties.getString(key));
			}
			return result.append(']').toString();
		}

		int paletteIndex(int index) throws IOException {
			if(this.palette.length == 1) {
				return 0;
			}
			int paletteIndex = Math.toIntExact(unpack(this.data, index, this.bits));
			if(paletteIndex >= this.palette.length) {
				throw new IOException("Packed palette index " + paletteIndex + " exceeds palette size "
					+ this.palette.length);
			}
			return paletteIndex;
		}

		String state(int paletteIndex) {
			return this.palette[paletteIndex];
		}

		byte[] hashToken(int paletteIndex) {
			return this.hashTokens[paletteIndex];
		}
	}

	private static final class Analysis {
		private final Path worldA;
		private final Path worldB;
		private final int indexedA;
		private final int indexedB;
		private final int commonBeforeFilter;
		private final int selectedCommon;
		private final ChunkFilter filter;
		private final Long seedA;
		private final Long seedB;
		private final MessageDigest baseAggregateA = sha256();
		private final MessageDigest baseAggregateB = sha256();
		private final MessageDigest exactAggregateA = sha256();
		private final MessageDigest exactAggregateB = sha256();
		private long comparedChunks;
		private long worldSurfaceWgChunks;
		private long fallbackSurfaceChunks;
		private long comparedHeightColumns;
		private long comparedOccupancyPositions;
		private long comparedExactBlockPositions;
		private long skippedNonFull;
		private long statusMismatches;
		private long heightMismatches;
		private long worldSurfaceWgHeightMismatches;
		private long fallbackDerivedHeightMismatches;
		private long occupancyMismatches;
		private long exactStateMismatches;
		private long baseChunkHashMismatches;
		private long exactChunkHashMismatches;
		private long baseMismatchedChunks;
		private long exactMismatchedChunks;
		private final Map<String, Long> occupancyMismatchPairs = new TreeMap<>();
		private HeightMismatch firstHeightMismatch;
		private OccupancyMismatch firstOccupancyMismatch;
		private ExactStateMismatch firstExactStateMismatch;
		private ChunkPos samplePos;
		private String sampleHeightmap;
		private int[] sampleHeights;
		private String baseHashA;
		private String baseHashB;
		private String exactHashA;
		private String exactHashB;

		private Analysis(Path worldA, Path worldB, int indexedA, int indexedB,
				int commonBeforeFilter, int selectedCommon, ChunkFilter filter, Long seedA, Long seedB) {
			this.worldA = worldA;
			this.worldB = worldB;
			this.indexedA = indexedA;
			this.indexedB = indexedB;
			this.commonBeforeFilter = commonBeforeFilter;
			this.selectedCommon = selectedCommon;
			this.filter = filter;
			this.seedA = seedA;
			this.seedB = seedB;
		}

		void compare(ChunkPos pos, ChunkTerrain a, ChunkTerrain b) throws IOException {
			this.comparedChunks++;
			HeightSelection heightSelection = HeightSelection.select(pos, a, b);
			if(heightSelection.fallback) {
				this.fallbackSurfaceChunks++;
			} else {
				this.worldSurfaceWgChunks++;
			}
			if(this.samplePos == null) {
				this.samplePos = pos;
				this.sampleHeightmap = heightSelection.name;
				this.sampleHeights = heightSelection.heightsA.clone();
			}
			MessageDigest baseChunkA = sha256();
			MessageDigest baseChunkB = sha256();
			MessageDigest exactChunkA = sha256();
			MessageDigest exactChunkB = sha256();
			updateChunkCoordinates(baseChunkA, baseChunkB, pos);
			updateChunkCoordinates(exactChunkA, exactChunkB, pos);
			boolean baseChunkMismatch = false;
			boolean exactChunkMismatch = false;

			for(int index = 0; index < 256; index++) {
				int heightA = heightSelection.heightsA[index];
				int heightB = heightSelection.heightsB[index];
				updateInt(baseChunkA, heightA);
				updateInt(baseChunkB, heightB);
				this.comparedHeightColumns++;
				if(heightA != heightB) {
					this.heightMismatches++;
					if(heightSelection.fallback) {
						this.fallbackDerivedHeightMismatches++;
					} else {
						this.worldSurfaceWgHeightMismatches++;
					}
					baseChunkMismatch = true;
					if(this.firstHeightMismatch == null) {
						int localX = index & 15;
						int localZ = index >>> 4;
						this.firstHeightMismatch = new HeightMismatch(pos, localX, localZ, heightA, heightB,
							heightSelection.name, a.state(localX, heightA - 1, localZ),
							b.state(localX, heightB - 1, localZ),
							heightSelection.heightsA.clone(), heightSelection.heightsB.clone());
					}
				}
			}

			Set<Integer> sectionYs = new TreeSet<>(a.sections.keySet());
			sectionYs.addAll(b.sections.keySet());
			for(int sectionY : sectionYs) {
				updateInt(exactChunkA, sectionY);
				updateInt(exactChunkB, sectionY);
				Section sectionA = a.sections.get(sectionY);
				Section sectionB = b.sections.get(sectionY);
				for(int localY = 0; localY < 16; localY++) {
					for(int localZ = 0; localZ < 16; localZ++) {
						for(int localX = 0; localX < 16; localX++) {
							int index = localY << 8 | localZ << 4 | localX;
							int paletteA = sectionA == null ? -1 : sectionA.paletteIndex(index);
							int paletteB = sectionB == null ? -1 : sectionB.paletteIndex(index);
							String stateA = sectionA == null ? AIR : sectionA.state(paletteA);
							String stateB = sectionB == null ? AIR : sectionB.state(paletteB);
							exactChunkA.update(sectionA == null ? AIR_HASH_TOKEN : sectionA.hashToken(paletteA));
							exactChunkB.update(sectionB == null ? AIR_HASH_TOKEN : sectionB.hashToken(paletteB));
							this.comparedExactBlockPositions++;
							if(!stateA.equals(stateB)) {
								this.exactStateMismatches++;
								exactChunkMismatch = true;
								if(this.firstExactStateMismatch == null) {
									this.firstExactStateMismatch = new ExactStateMismatch(pos,
										pos.x * 16 + localX, sectionY * 16 + localY, pos.z * 16 + localZ,
										localX, localY, localZ, stateA, stateB);
								}
							}
						}
					}
				}
			}

			int minSectionY = sectionYs.stream().min(Integer::compareTo).orElseThrow();
			int minBlockY = minSectionY * 16;
			for(int localZ = 0; localZ < 16; localZ++) {
				for(int localX = 0; localX < 16; localX++) {
					int columnIndex = localZ * 16 + localX;
					int maxBlockY = Math.max(heightSelection.heightsA[columnIndex],
						heightSelection.heightsB[columnIndex]) - 1;
					for(int blockY = minBlockY; blockY <= maxBlockY; blockY++) {
						String stateA = a.state(localX, blockY, localZ);
						String stateB = b.state(localX, blockY, localZ);
						BaseOccupancy occupancyA = BaseOccupancy.of(stateA);
						BaseOccupancy occupancyB = BaseOccupancy.of(stateB);
						if(occupancyA == BaseOccupancy.IGNORE || occupancyB == BaseOccupancy.IGNORE) {
							BaseOccupancy inherited = occupancyA == BaseOccupancy.IGNORE
								? occupancyB == BaseOccupancy.IGNORE ? BaseOccupancy.EMPTY : occupancyB
								: occupancyA;
							occupancyA = inherited;
							occupancyB = inherited;
						}
						baseChunkA.update((byte)occupancyA.ordinal());
						baseChunkB.update((byte)occupancyB.ordinal());
						this.comparedOccupancyPositions++;
						if(occupancyA != occupancyB) {
							this.occupancyMismatches++;
							this.occupancyMismatchPairs.merge(stateA + " -> " + stateB, 1L, Long::sum);
							baseChunkMismatch = true;
							if(this.firstOccupancyMismatch == null) {
								this.firstOccupancyMismatch = new OccupancyMismatch(pos,
									pos.x * 16 + localX, blockY, pos.z * 16 + localZ,
									localX, Math.floorMod(blockY, 16), localZ,
									occupancyA, occupancyB, stateA, stateB);
							}
						}
					}
				}
			}

			byte[] baseHashA = baseChunkA.digest();
			byte[] baseHashB = baseChunkB.digest();
			byte[] exactHashA = exactChunkA.digest();
			byte[] exactHashB = exactChunkB.digest();
			updateAggregate(this.baseAggregateA, pos, baseHashA);
			updateAggregate(this.baseAggregateB, pos, baseHashB);
			updateAggregate(this.exactAggregateA, pos, exactHashA);
			updateAggregate(this.exactAggregateB, pos, exactHashB);
			if(!Arrays.equals(baseHashA, baseHashB)) {
				this.baseChunkHashMismatches++;
			}
			if(!Arrays.equals(exactHashA, exactHashB)) {
				this.exactChunkHashMismatches++;
			}
			if(baseChunkMismatch) {
				this.baseMismatchedChunks++;
			}
			if(exactChunkMismatch) {
				this.exactMismatchedChunks++;
			}
		}

		private static void updateChunkCoordinates(MessageDigest a, MessageDigest b, ChunkPos pos) {
			updateInt(a, pos.x);
			updateInt(a, pos.z);
			updateInt(b, pos.x);
			updateInt(b, pos.z);
		}

		private static void updateAggregate(MessageDigest aggregate, ChunkPos pos, byte[] chunkHash) {
			updateInt(aggregate, pos.x);
			updateInt(aggregate, pos.z);
			aggregate.update(chunkHash);
		}

		boolean hasMismatch() {
			return this.heightMismatches != 0 || this.occupancyMismatches != 0;
		}

		String summary() {
			if(this.baseHashA == null) {
				this.baseHashA = HexFormat.of().formatHex(this.baseAggregateA.digest());
				this.baseHashB = HexFormat.of().formatHex(this.baseAggregateB.digest());
				this.exactHashA = HexFormat.of().formatHex(this.exactAggregateA.digest());
				this.exactHashB = HexFormat.of().formatHex(this.exactAggregateB.digest());
			}
			return String.format(Locale.ROOT,
				"worldA=%s%nworldB=%s%n"
					+ "worldGenerationSeed(A/B)=%s/%s equal=%s%n"
					+ "filter=%s%n"
					+ "indexedChunks(A/B)=%d/%d commonBeforeFilter=%d selectedCommon=%d filteredOutCommon=%d "
					+ "unmatched(A/B)=%d/%d%n"
					+ "comparedFullChunks=%d skippedNonFull=%d statusMismatches=%d "
					+ "heightSources(WG/fallback)=%d/%d%n"
					+ "comparedHeightColumns=%d comparedOccupancyPositions=%d "
					+ "comparedExactBlockPositions=%d%n"
					+ "baseMismatchedChunks=%d heightMismatches(total/WG/derivedFallback)=%d/%d/%d "
					+ "occupancyMismatches=%d "
					+ "baseChunkHashMismatches=%d%n"
					+ "exactMismatchedChunks=%d exactStateMismatches=%d exactChunkHashMismatches=%d%n"
					+ "baseTerrainSha256(A/B)=%s/%s%n"
					+ "exactStateSha256(A/B)=%s/%s%n"
					+ "topOccupancyMismatchPairs=%s",
				this.worldA, this.worldB,
				this.seedA == null ? "unavailable" : this.seedA,
				this.seedB == null ? "unavailable" : this.seedB,
				this.seedA != null && this.seedA.equals(this.seedB),
				this.filter,
				this.indexedA, this.indexedB, this.commonBeforeFilter, this.selectedCommon,
				this.commonBeforeFilter - this.selectedCommon,
				this.indexedA - this.commonBeforeFilter, this.indexedB - this.commonBeforeFilter,
				this.comparedChunks, this.skippedNonFull, this.statusMismatches,
				this.worldSurfaceWgChunks, this.fallbackSurfaceChunks,
				this.comparedHeightColumns, this.comparedOccupancyPositions,
				this.comparedExactBlockPositions,
				this.baseMismatchedChunks, this.heightMismatches,
				this.worldSurfaceWgHeightMismatches, this.fallbackDerivedHeightMismatches,
				this.occupancyMismatches,
				this.baseChunkHashMismatches,
				this.exactMismatchedChunks, this.exactStateMismatches, this.exactChunkHashMismatches,
				this.baseHashA, this.baseHashB, this.exactHashA, this.exactHashB,
				this.occupancyMismatchPairs.entrySet().stream()
					.sorted(Map.Entry.<String, Long>comparingByValue().reversed()
						.thenComparing(Map.Entry.comparingByKey()))
					.limit(10)
					.map(entry -> entry.getValue() + "x " + entry.getKey())
					.toList());
		}

		String failureReport(String summary) {
			StringBuilder report = new StringBuilder("Client-generated base terrain differs.\n").append(summary);
			if(this.firstHeightMismatch != null) {
				report.append("\n\n").append(this.firstHeightMismatch.describe());
			}
			if(this.firstOccupancyMismatch != null) {
				report.append("\n\n").append(this.firstOccupancyMismatch.describe());
			}
			if(this.firstExactStateMismatch != null) {
				report.append("\n\n").append(this.firstExactStateMismatch.describe());
			}
			return report.toString();
		}
	}

	private record HeightMismatch(ChunkPos chunk, int localX, int localZ, int heightA, int heightB,
			String heightmap, String topStateA, String topStateB, int[] matrixA, int[] matrixB) {
		String describe() {
			int blockX = this.chunk.x * 16 + this.localX;
			int blockZ = this.chunk.z * 16 + this.localZ;
			return "first " + this.heightmap + " mismatch chunk=" + this.chunk
				+ " column=(" + blockX + "," + blockZ + ") local=(" + this.localX + "," + this.localZ + ")"
				+ " mod4=(" + Math.floorMod(blockX, 4) + "," + Math.floorMod(blockZ, 4) + ")"
				+ " A=" + this.heightA + " B=" + this.heightB
				+ " topStates(A/B)=" + this.topStateA + "/" + this.topStateB
				+ "\nA firstAvailableY matrix:\n" + formatHeightMatrix(this.matrixA)
				+ "B firstAvailableY matrix:\n" + formatHeightMatrix(this.matrixB);
		}
	}

	private record OccupancyMismatch(ChunkPos chunk, int blockX, int blockY, int blockZ,
			int localX, int localY, int localZ, BaseOccupancy occupancyA, BaseOccupancy occupancyB,
			String stateA, String stateB) {
		String describe() {
			return "first base-occupancy mismatch chunk=" + this.chunk
				+ " block=(" + this.blockX + "," + this.blockY + "," + this.blockZ + ")"
				+ " local=(" + this.localX + "," + this.localY + "," + this.localZ + ")"
				+ " mod4=(" + Math.floorMod(this.blockX, 4) + "," + Math.floorMod(this.blockY, 4)
				+ "," + Math.floorMod(this.blockZ, 4) + ")"
				+ " A=" + this.occupancyA + "[" + this.stateA + "]"
				+ " B=" + this.occupancyB + "[" + this.stateB + "]";
		}
	}

	private record ExactStateMismatch(ChunkPos chunk, int blockX, int blockY, int blockZ,
			int localX, int localY, int localZ, String stateA, String stateB) {
		String describe() {
			return "first exact-state mismatch (informational) chunk=" + this.chunk
				+ " block=(" + this.blockX + "," + this.blockY + "," + this.blockZ + ")"
				+ " local=(" + this.localX + "," + this.localY + "," + this.localZ + ")"
				+ " mod4=(" + Math.floorMod(this.blockX, 4) + "," + Math.floorMod(this.blockY, 4)
				+ "," + Math.floorMod(this.blockZ, 4) + ")"
				+ " A=" + this.stateA + " B=" + this.stateB;
		}
	}
}
