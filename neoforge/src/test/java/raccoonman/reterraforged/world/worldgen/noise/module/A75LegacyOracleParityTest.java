package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.JsonOps;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;
import raccoonman.reterraforged.concurrent.SimpleResource;
import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings.DensityAlgorithm;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.WorldFilters;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Heightmap;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.Rivermap;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Size;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.Tile;
import raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter.Filterable;
import raccoonman.reterraforged.world.worldgen.noise.module.NoiseRootRuntime;

/**
 * Compares current scalar terrain semantics against a process-isolated snapshot
 * produced from the untouched A75 jar.
 */
class A75LegacyOracleParityTest {
	private static final String ORIGINAL_SHA256 =
		"a75ed34a2fa36f3222c75f193143a711589df2e0078b14152fa582af53e9d1e9";
	private static final String ORIGINAL_JAR_PROPERTY = "rtf.a75.jar";
	private static final String PRESET_PROPERTY = "rtf.a75.preset";
	private static final int SEED = 0x5EED_2171;
	private static final String[] FLOAT_FIELDS = {
		"height",
		"heightErosion",
		"sediment",
		"gradient",
		"regionMoisture",
		"regionTemperature",
		"continentId",
		"continentEdge",
		"continentDistance",
		"terrainRegionId",
		"terrainRegionEdge",
		"terrainRegionCenterX",
		"terrainRegionCenterZ",
		"biomeRegionId",
		"biomeRegionEdge",
		"macroBiomeId",
		"riverMask",
		"erosion",
		"weirdness",
		"temperature",
		"moisture",
		"beachNoise"
	};
	private static final List<Window> WINDOWS = List.of(
		new Window(0, 0, 16),
		new Window(505 << 4, 583 << 4, 16),
		new Window(-4685, -3787, 16),
		new Window(5312, 15144, 16)
	);

	@Test
	void currentLegacyAndLegacyV2MatchUntouchedA75AtEveryTerrainCoordinate() throws Exception {
		Path originalJar = originalJar();
		assumeTrue(
			originalJar != null && Files.isRegularFile(originalJar),
			"Set -D" + ORIGINAL_JAR_PROPERTY + "=<untouched-A75-jar>"
		);
		String expectedSha256 = expectedOracleSha256();
		assertEquals(expectedSha256, sha256(originalJar), "Unexpected RTF oracle artifact");

		OriginalSnapshot original = runOriginalWorker(originalJar, expectedSha256);

		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		Preset currentLegacyPreset = decodeCurrent(original.preset());
		currentLegacyPreset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY;
		currentLegacyPreset.caves().densityAlgorithm = DensityAlgorithm.LEGACY;

		Preset currentLegacyV2Preset = decodeCurrent(original.preset());
		currentLegacyV2Preset.world().noiseEngine = WorldSettings.NoiseEngine.LEGACY_V2;
		currentLegacyV2Preset.caves().densityAlgorithm = DensityAlgorithm.LEGACY_V2;

		try(
			GeneratorContext legacy = context(currentLegacyPreset);
			GeneratorContext legacyV2 = context(currentLegacyV2Preset)
		) {
			Comparison legacyComparison = compareTerrain(original, legacy);
			Comparison legacyV2Comparison = compareTerrain(original, legacyV2);
			System.out.println("RTF_A75_ORACLE currentLegacy=" + legacyComparison.describe());
			System.out.println("RTF_A75_ORACLE legacyV2=" + legacyV2Comparison.describe());
			assertEquals(0L, legacyComparison.mismatchCount(), legacyComparison::describe);
			assertEquals(0L, legacyV2Comparison.mismatchCount(), legacyV2Comparison::describe);

			compareTileStages("currentLegacy", original.tileStages(), captureCurrentTileStages(legacy));
			compareTileStages("legacyV2", original.tileStages(), captureCurrentTileStages(legacyV2));
		}
	}

	private static OriginalSnapshot runOriginalWorker(Path originalJar, String expectedSha256) throws Exception {
		Path snapshotPath = Files.createTempFile("rtf-a75-oracle-", ".bin");
		Path argumentFile = Files.createTempFile("rtf-a75-worker-", ".args");
		try {
			String classpath = System.getProperty("java.class.path");
			assertNotNull(classpath, "Test worker did not expose java.class.path");
			List<String> workerArguments = new ArrayList<>(List.of(
				"-cp",
				quoteArgument(classpath),
				A75OracleSnapshotWorker.class.getName(),
				quoteArgument(originalJar.toString()),
				expectedSha256,
				quoteArgument(snapshotPath.toString())
			));
			String configuredPreset = System.getProperty(PRESET_PROPERTY);
			if(configuredPreset == null || configuredPreset.isBlank()) {
				configuredPreset = System.getenv("RTF_A75_PRESET");
			}
			if(configuredPreset != null && !configuredPreset.isBlank()) {
				Path presetPath = Path.of(configuredPreset).toAbsolutePath().normalize();
				assertEquals(true, Files.isRegularFile(presetPath), "Configured A75 preset does not exist");
				workerArguments.add(quoteArgument(presetPath.toString()));
			}
			String arguments = String.join(System.lineSeparator(), workerArguments) + System.lineSeparator();
			Files.writeString(argumentFile, arguments, StandardCharsets.UTF_8);

			Path javaExecutable = Path.of(
				System.getProperty("java.home"),
				"bin",
				System.getProperty("os.name", "").startsWith("Windows") ? "java.exe" : "java"
			);
			Process process = new ProcessBuilder(
				javaExecutable.toString(),
				"@" + argumentFile.toAbsolutePath()
			)
				.redirectErrorStream(true)
				.start();
			CompletableFuture<String> outputFuture = CompletableFuture.supplyAsync(() -> {
				try {
					return new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
				} catch(IOException exception) {
					throw new CompletionException(exception);
				}
			});
			if(!process.waitFor(180, TimeUnit.SECONDS)) {
				process.destroyForcibly();
				process.waitFor(10, TimeUnit.SECONDS);
				String output = outputFuture.get(10, TimeUnit.SECONDS);
				fail("A75 snapshot worker timed out after 180 seconds:\n" + output);
			}
			String output = outputFuture.get(10, TimeUnit.SECONDS);
			assertEquals(0, process.exitValue(), () -> "A75 snapshot worker failed:\n" + output);
			System.out.print(output);
			return OriginalSnapshot.read(snapshotPath);
		} finally {
			Files.deleteIfExists(argumentFile);
			Files.deleteIfExists(snapshotPath);
		}
	}

	private static String quoteArgument(String value) {
		String normalized = value.replace('\\', '/');
		if(normalized.indexOf('\n') >= 0 || normalized.indexOf('\r') >= 0) {
			throw new IllegalArgumentException("Java argument contains a line break");
		}
		return '"' + normalized.replace("\"", "\\\"") + '"';
	}

	private static Comparison compareTerrain(OriginalSnapshot original, GeneratorContext current) {
		Heightmap heightmap = current.generator.getHeightmap();
		Comparison comparison = new Comparison();
		for(Window window : WINDOWS) {
			for(int dz = 0; dz < window.size(); dz++) {
				for(int dx = 0; dx < window.size(); dx++) {
					int x = window.startX() + dx;
					int z = window.startZ() + dz;
					Cell currentCell = new Cell();
					heightmap.applyTerrain(currentCell, x, z);
					CellSnapshot expected = original.cells().get(positionKey(x, z));
					assertNotNull(expected, () -> "A75 snapshot omitted " + x + "," + z);
					comparison.compare(x, z, expected, CellSnapshot.capture(currentCell));
				}
			}
		}
		return comparison;
	}

	private static Map<String, TileSnapshot> captureCurrentTileStages(GeneratorContext context) throws Exception {
		int factor = A75OracleSnapshotWorker.TILE_FACTOR;
		int border = A75OracleSnapshotWorker.TILE_BORDER;
		int tileX = A75OracleSnapshotWorker.TILE_X;
		int tileZ = A75OracleSnapshotWorker.TILE_Z;
		Size blockSize = Size.blocks(factor, border);
		Size chunkSize = Size.chunks(factor, border);
		Cell[] cells = new Cell[blockSize.arraySize()];
		for(int index = 0; index < cells.length; index++) {
			cells[index] = new Cell();
		}
		Tile.Chunk[] chunks = new Tile.Chunk[chunkSize.arraySize()];
		Map<String, TileSnapshot> stages = new LinkedHashMap<>();
		try(Tile tile = new Tile(
			tileX,
			tileZ,
			factor,
			border,
			blockSize,
			chunkSize,
			new SimpleResource<>(cells, ignored -> {
			}),
			new SimpleResource<>(chunks, ignored -> {
			})
		)) {
			Heightmap heightmap = context.generator.getHeightmap();
			try(NoiseRootRuntime.Scope ignored = NoiseRootRuntime.bind(context.rootNoiseEngine)) {
				for(int chunkZ = 0; chunkZ < chunkSize.total(); chunkZ++) {
					for(int chunkX = 0; chunkX < chunkSize.total(); chunkX++) {
						Tile.Chunk chunk = tile.getChunkWriter(chunkX, chunkZ);
						Rivermap rivermap = null;
						for(int dz = 0; dz < 16; dz++) {
							for(int dx = 0; dx < 16; dx++) {
								int worldX = chunk.getBlockX() + dx;
								int worldZ = chunk.getBlockZ() + dz;
								Cell cell = chunk.getCell(dx, dz);
								NoiseRootRuntime.prepareSample(worldX, worldZ);
								heightmap.applyTerrain(cell, worldX, worldZ);
								rivermap = Rivermap.get(cell, rivermap, heightmap);
								heightmap.applyRivers(cell, worldX, worldZ, rivermap);
								heightmap.applyClimate(cell, worldX, worldZ, true);
							}
						}
					}
				}
			}
			stages.put("native-input", TileSnapshot.capture(tile));

			WorldFilters filters = new WorldFilters(context);
			Method optional = WorldFilters.class.getDeclaredMethod(
				"applyOptionalFilters",
				Filterable.class,
				int.class,
				int.class
			);
			Method required = WorldFilters.class.getDeclaredMethod(
				"applyRequiredFilters",
				Filterable.class,
				int.class,
				int.class
			);
			optional.setAccessible(true);
			required.setAccessible(true);
			optional.invoke(filters, tile, tileX, tileZ);
			stages.put("optional-filters", TileSnapshot.capture(tile));
			required.invoke(filters, tile, tileX, tileZ);
			stages.put("required-filters", TileSnapshot.capture(tile));
			filters.applyCorrections(tile, tileX, tileZ);
			stages.put("noise-correction", TileSnapshot.capture(tile));
		}
		try(Tile production = context.generator.generate(tileX, tileZ).join()) {
			stages.put("production-final", TileSnapshot.capture(production));
		}
		return stages;
	}

	private static void compareTileStages(
		String engine,
		Map<String, TileSnapshot> expectedStages,
		Map<String, TileSnapshot> actualStages
	) {
		assertEquals(expectedStages.keySet(), actualStages.keySet(), engine + " tile stage set");
		for(Map.Entry<String, TileSnapshot> entry : expectedStages.entrySet()) {
			String stage = entry.getKey();
			TileSnapshot expected = entry.getValue();
			TileSnapshot actual = actualStages.get(stage);
			assertNotNull(actual, () -> engine + " omitted tile stage " + stage);
			assertEquals(expected.tileX(), actual.tileX(), engine + "/" + stage + " tile X");
			assertEquals(expected.tileZ(), actual.tileZ(), engine + "/" + stage + " tile Z");
			assertEquals(expected.blockX(), actual.blockX(), engine + "/" + stage + " block X");
			assertEquals(expected.blockZ(), actual.blockZ(), engine + "/" + stage + " block Z");
			assertEquals(expected.size(), actual.size(), engine + "/" + stage + " size");
			assertEquals(expected.border(), actual.border(), engine + "/" + stage + " border");
			assertEquals(expected.total(), actual.total(), engine + "/" + stage + " total");
			assertEquals(expected.cells().length, actual.cells().length, engine + "/" + stage + " cells");

			Comparison comparison = new Comparison();
			for(int index = 0; index < expected.cells().length; index++) {
				int relativeX = index % expected.total();
				int relativeZ = index / expected.total();
				int worldX = expected.blockX() - expected.border() + relativeX;
				int worldZ = expected.blockZ() - expected.border() + relativeZ;
				comparison.compare(worldX, worldZ, expected.cells()[index], actual.cells()[index]);
			}
			System.out.println(
				"RTF_A75_TILE_ORACLE engine=" + engine + ", stage=" + stage + ", " + comparison.describe()
			);
			assertEquals(
				0L,
				comparison.mismatchCount(),
				() -> engine + " first divergent tile stage=" + stage + ": " + comparison.describe()
			);
		}
	}

	private static GeneratorContext context(Preset preset) {
		return GeneratorContext.makeUncached(
			preset,
			RtfSemanticParityTest.noiseLookup(preset),
			SEED,
			3,
			A75OracleSnapshotWorker.TILE_BORDER,
			1
		);
	}

	private static Preset decodeCurrent(JsonElement json) {
		return Preset.DIRECT_CODEC.parse(JsonOps.INSTANCE, json)
			.getOrThrow(message -> new IllegalArgumentException("Could not decode A75 default preset: " + message));
	}

	private static Path originalJar() {
		String configured = System.getProperty(ORIGINAL_JAR_PROPERTY);
		if(configured == null || configured.isBlank()) {
			configured = System.getenv("RTF_ORACLE_JAR");
		}
		return configured == null || configured.isBlank() ? null : Path.of(configured).toAbsolutePath().normalize();
	}

	private static String expectedOracleSha256() {
		String configured = System.getProperty("rtf.oracle.sha256");
		if(configured == null || configured.isBlank()) {
			configured = System.getenv("RTF_ORACLE_SHA256");
		}
		return configured == null || configured.isBlank()
			? ORIGINAL_SHA256
			: configured.toLowerCase();
	}

	private static String sha256(Path file) throws Exception {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		try(var input = Files.newInputStream(file)) {
			byte[] buffer = new byte[64 * 1024];
			for(int read; (read = input.read(buffer)) >= 0;) {
				digest.update(buffer, 0, read);
			}
		}
		return HexFormat.of().formatHex(digest.digest());
	}

	private static long positionKey(int x, int z) {
		return ((long)x << 32) ^ (z & 0xFFFF_FFFFL);
	}

	private static String enumName(Enum<?> value) {
		return value == null ? null : value.name();
	}

	private static String readString(DataInputStream input) throws IOException {
		int length = input.readInt();
		if(length < 0) {
			return null;
		}
		byte[] bytes = input.readNBytes(length);
		if(bytes.length != length) {
			throw new IOException("Unexpected end of A75 snapshot string");
		}
		return new String(bytes, StandardCharsets.UTF_8);
	}

	private record Window(int startX, int startZ, int size) {
	}

	private record CellSnapshot(
		int[] floatBits,
		int continentX,
		int continentZ,
		boolean erosionMask,
		int terrainId,
		String terrainName,
		String terrainCategory,
		String biome,
		String fakeWaterBiome
	) {
		private static CellSnapshot capture(Cell cell) {
			return new CellSnapshot(
				new int[] {
					Float.floatToRawIntBits(cell.height),
					Float.floatToRawIntBits(cell.heightErosion),
					Float.floatToRawIntBits(cell.sediment),
					Float.floatToRawIntBits(cell.gradient),
					Float.floatToRawIntBits(cell.regionMoisture),
					Float.floatToRawIntBits(cell.regionTemperature),
					Float.floatToRawIntBits(cell.continentId),
					Float.floatToRawIntBits(cell.continentEdge),
					Float.floatToRawIntBits(cell.continentDistance),
					Float.floatToRawIntBits(cell.terrainRegionId),
					Float.floatToRawIntBits(cell.terrainRegionEdge),
					Float.floatToRawIntBits(cell.terrainRegionCenterX),
					Float.floatToRawIntBits(cell.terrainRegionCenterZ),
					Float.floatToRawIntBits(cell.biomeRegionId),
					Float.floatToRawIntBits(cell.biomeRegionEdge),
					Float.floatToRawIntBits(cell.macroBiomeId),
					Float.floatToRawIntBits(cell.riverMask),
					Float.floatToRawIntBits(cell.erosion),
					Float.floatToRawIntBits(cell.weirdness),
					Float.floatToRawIntBits(cell.temperature),
					Float.floatToRawIntBits(cell.moisture),
					Float.floatToRawIntBits(cell.beachNoise)
				},
				cell.continentX,
				cell.continentZ,
				cell.erosionMask,
				cell.terrain.getId(),
				cell.terrain.getName(),
				cell.terrain.getCategory().name(),
				enumName(cell.biome),
				enumName(cell.fakeWaterBiome)
			);
		}
	}

	private record TileSnapshot(
		int tileX,
		int tileZ,
		int blockX,
		int blockZ,
		int size,
		int border,
		int total,
		CellSnapshot[] cells
	) {
		private static TileSnapshot capture(Tile tile) {
			Size blockSize = tile.getBlockSize();
			Cell[] backing = tile.getBacking();
			CellSnapshot[] cells = new CellSnapshot[backing.length];
			for(int index = 0; index < backing.length; index++) {
				cells[index] = CellSnapshot.capture(backing[index]);
			}
			return new TileSnapshot(
				tile.getX(),
				tile.getZ(),
				tile.getBlockX(),
				tile.getBlockZ(),
				blockSize.size(),
				blockSize.border(),
				blockSize.total(),
				cells
			);
		}
	}

	private record OriginalSnapshot(
		JsonElement preset,
		Map<Long, CellSnapshot> cells,
		Map<String, TileSnapshot> tileStages
	) {
		private static OriginalSnapshot read(Path path) throws IOException {
			try(DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(path)))) {
				assertEquals(A75OracleSnapshotWorker.MAGIC, input.readInt(), "Invalid A75 snapshot magic");
				assertEquals(A75OracleSnapshotWorker.VERSION, input.readInt(), "Unsupported A75 snapshot version");
				assertEquals(SEED, input.readInt(), "A75 snapshot used the wrong seed");

				int windowCount = input.readInt();
				assertEquals(WINDOWS.size(), windowCount, "A75 snapshot window count");
				for(int index = 0; index < windowCount; index++) {
					Window expected = WINDOWS.get(index);
					assertEquals(expected.startX(), input.readInt(), "A75 window start X");
					assertEquals(expected.startZ(), input.readInt(), "A75 window start Z");
					assertEquals(expected.size(), input.readInt(), "A75 window size");
				}

				int fieldCount = input.readInt();
				assertEquals(FLOAT_FIELDS.length, fieldCount, "A75 snapshot float field count");
				for(String expected : FLOAT_FIELDS) {
					assertEquals(expected, readString(input), "A75 snapshot float field order");
				}
				JsonElement preset = JsonParser.parseString(readString(input));

				int sampleCount = input.readInt();
				Map<Long, CellSnapshot> cells = new LinkedHashMap<>(sampleCount * 2);
				for(int sample = 0; sample < sampleCount; sample++) {
					int x = input.readInt();
					int z = input.readInt();
					int[] floatBits = new int[FLOAT_FIELDS.length];
					for(int index = 0; index < floatBits.length; index++) {
						floatBits[index] = input.readInt();
					}
					CellSnapshot snapshot = new CellSnapshot(
						floatBits,
						input.readInt(),
						input.readInt(),
						input.readBoolean(),
						input.readInt(),
						readString(input),
						readString(input),
						readString(input),
						readString(input)
					);
					CellSnapshot previous = cells.put(positionKey(x, z), snapshot);
					if(previous != null) {
						throw new IOException("Duplicate A75 snapshot coordinate " + x + "," + z);
					}
				}
				int expectedSamples = WINDOWS.stream().mapToInt(window -> window.size() * window.size()).sum();
				assertEquals(expectedSamples, sampleCount, "A75 snapshot sample count");

				int stageCount = input.readInt();
				Map<String, TileSnapshot> tileStages = new LinkedHashMap<>(stageCount * 2);
				for(int stageIndex = 0; stageIndex < stageCount; stageIndex++) {
					String stage = readString(input);
					int tileX = input.readInt();
					int tileZ = input.readInt();
					int blockX = input.readInt();
					int blockZ = input.readInt();
					int size = input.readInt();
					int border = input.readInt();
					int total = input.readInt();
					int cellCount = input.readInt();
					assertEquals(total * total, cellCount, stage + " A75 tile cell count");
					CellSnapshot[] stageCells = new CellSnapshot[cellCount];
					for(int index = 0; index < stageCells.length; index++) {
						int[] floatBits = new int[FLOAT_FIELDS.length];
						for(int field = 0; field < floatBits.length; field++) {
							floatBits[field] = input.readInt();
						}
						stageCells[index] = new CellSnapshot(
							floatBits,
							input.readInt(),
							input.readInt(),
							input.readBoolean(),
							input.readInt(),
							readString(input),
							readString(input),
							readString(input),
							readString(input)
						);
					}
					TileSnapshot previous = tileStages.put(
						stage,
						new TileSnapshot(tileX, tileZ, blockX, blockZ, size, border, total, stageCells)
					);
					if(previous != null) {
						throw new IOException("Duplicate A75 tile stage " + stage);
					}
				}
				assertEquals(
					List.of(
						"native-input",
						"optional-filters",
						"required-filters",
						"noise-correction",
						"production-final"
					),
					new ArrayList<>(tileStages.keySet()),
					"A75 tile stage order"
				);
				return new OriginalSnapshot(preset, cells, tileStages);
			}
		}
	}

	private static final class Comparison {
		private long samples;
		private long mismatchCount;
		private final Map<String, Long> mismatchesByField = new LinkedHashMap<>();
		private final List<String> firstMismatches = new ArrayList<>();

		private void compare(int x, int z, CellSnapshot expected, CellSnapshot actual) {
			this.samples++;
			for(int index = 0; index < FLOAT_FIELDS.length; index++) {
				int expectedBits = expected.floatBits()[index];
				int actualBits = actual.floatBits()[index];
				if(expectedBits != actualBits) {
					this.recordFloat(FLOAT_FIELDS[index], x, z, expectedBits, actualBits);
				}
			}
			this.compareValue("continentX", x, z, expected.continentX(), actual.continentX());
			this.compareValue("continentZ", x, z, expected.continentZ(), actual.continentZ());
			this.compareValue("erosionMask", x, z, expected.erosionMask(), actual.erosionMask());
			this.compareValue("terrainId", x, z, expected.terrainId(), actual.terrainId());
			this.compareValue("terrainName", x, z, expected.terrainName(), actual.terrainName());
			this.compareValue("terrainCategory", x, z, expected.terrainCategory(), actual.terrainCategory());
			this.compareValue("biome", x, z, expected.biome(), actual.biome());
			this.compareValue("fakeWaterBiome", x, z, expected.fakeWaterBiome(), actual.fakeWaterBiome());
		}

		private void recordFloat(String field, int x, int z, int expectedBits, int actualBits) {
			this.record(
				field,
				x,
				z,
				Float.intBitsToFloat(expectedBits) + "/0x" + Integer.toHexString(expectedBits),
				Float.intBitsToFloat(actualBits) + "/0x" + Integer.toHexString(actualBits)
			);
		}

		private void compareValue(String field, int x, int z, Object expected, Object actual) {
			if(!java.util.Objects.equals(expected, actual)) {
				this.record(field, x, z, String.valueOf(expected), String.valueOf(actual));
			}
		}

		private void record(String field, int x, int z, String expected, String actual) {
			this.mismatchCount++;
			this.mismatchesByField.merge(field, 1L, Long::sum);
			if(this.firstMismatches.size() < 20) {
				this.firstMismatches.add(
					field + "@" + x + "," + z + " expected=" + expected + " actual=" + actual
				);
			}
		}

		private long mismatchCount() {
			return this.mismatchCount;
		}

		private String describe() {
			return "samples=" + this.samples
				+ ", mismatches=" + this.mismatchCount
				+ ", byField=" + this.mismatchesByField
				+ ", first=" + this.firstMismatches;
		}
	}
}
