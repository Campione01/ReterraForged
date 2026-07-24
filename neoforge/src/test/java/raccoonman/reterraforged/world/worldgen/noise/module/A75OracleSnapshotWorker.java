package raccoonman.reterraforged.world.worldgen.noise.module;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.Lifecycle;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import net.neoforged.fml.loading.LoadingModList;

/**
 * Process-isolated extractor for the untouched A75 jar. This class must not
 * acquire a static reference to any current ReTerraForged class.
 */
public final class A75OracleSnapshotWorker {
	static final int MAGIC = 0x52544641;
	static final int VERSION = 2;
	static final int SEED = 0x5EED_2171;
	static final int TILE_FACTOR = 3;
	static final int TILE_BORDER = 2;
	static final int TILE_X = Math.floorDiv(5_312, 128);
	static final int TILE_Z = Math.floorDiv(15_144, 128);
	static final String[] FLOAT_FIELDS = {
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
	static final Window[] WINDOWS = {
		new Window(0, 0, 16),
		new Window(505 << 4, 583 << 4, 16),
		new Window(-4685, -3787, 16),
		new Window(5312, 15144, 16)
	};

	private A75OracleSnapshotWorker() {
	}

	public static void main(String[] args) throws Exception {
		if(args.length < 3 || args.length > 4) {
			throw new IllegalArgumentException(
				"Usage: A75OracleSnapshotWorker <a75-jar> <sha256> <snapshot> [preset-json]"
			);
		}
		Path originalJar = Path.of(args[0]).toAbsolutePath().normalize();
		String expectedSha256 = args[1];
		Path output = Path.of(args[2]).toAbsolutePath().normalize();
		JsonElement configuredPreset = args.length == 4
			? JsonParser.parseString(Files.readString(Path.of(args[3]).toAbsolutePath().normalize()))
			: null;
		if(!Files.isRegularFile(originalJar)) {
			throw new IllegalArgumentException("A75 jar does not exist: " + originalJar);
		}
		String actualSha256 = sha256(originalJar);
		if(!expectedSha256.equalsIgnoreCase(actualSha256)) {
			throw new IllegalArgumentException(
				"Unexpected A75 artifact: expected=" + expectedSha256 + ", actual=" + actualSha256
			);
		}

		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();

		Files.createDirectories(output.getParent());
		try(
			OriginalRuntime original = OriginalRuntime.open(originalJar, configuredPreset);
			DataOutputStream data = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(output)))
		) {
			data.writeInt(MAGIC);
			data.writeInt(VERSION);
			data.writeInt(SEED);
			data.writeInt(WINDOWS.length);
			for(Window window : WINDOWS) {
				data.writeInt(window.startX());
				data.writeInt(window.startZ());
				data.writeInt(window.size());
			}
			data.writeInt(FLOAT_FIELDS.length);
			for(String field : FLOAT_FIELDS) {
				writeString(data, field);
			}
			writeString(data, original.encodeDefaultPreset().toString());

			int samples = 0;
			for(Window window : WINDOWS) {
				samples += window.size() * window.size();
			}
			data.writeInt(samples);
			int completed = 0;
			for(Window window : WINDOWS) {
				for(int dz = 0; dz < window.size(); dz++) {
					for(int dx = 0; dx < window.size(); dx++) {
						int x = window.startX() + dx;
						int z = window.startZ() + dz;
						CellSnapshot snapshot = original.sampleTerrain(x, z);
						data.writeInt(x);
						data.writeInt(z);
						writeCell(data, snapshot);
						completed++;
					}
				}
				System.out.println(
					"RTF_A75_WORKER_PROGRESS completed=" + completed + "/" + samples
						+ ", window=" + window.startX() + "," + window.startZ() + "+" + window.size()
				);
				System.out.flush();
			}
			original.writeTileStages(data, TILE_X, TILE_Z, TILE_FACTOR, TILE_BORDER);
			System.out.println(
				"RTF_A75_WORKER samples=" + samples
					+ ", sha256=" + actualSha256
					+ ", snapshot=" + output
			);
		}
		System.out.flush();
		System.err.flush();
		System.exit(0);
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

	private static void writeString(DataOutputStream output, String value) throws IOException {
		if(value == null) {
			output.writeInt(-1);
			return;
		}
		byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
		output.writeInt(bytes.length);
		output.write(bytes);
	}

	record Window(int startX, int startZ, int size) {
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
		private static CellSnapshot capture(Object cell) throws ReflectiveOperationException {
			Class<?> type = cell.getClass();
			int[] floatBits = new int[FLOAT_FIELDS.length];
			for(int index = 0; index < FLOAT_FIELDS.length; index++) {
				floatBits[index] = Float.floatToRawIntBits(type.getField(FLOAT_FIELDS[index]).getFloat(cell));
			}
			Object terrain = type.getField("terrain").get(cell);
			return new CellSnapshot(
				floatBits,
				type.getField("continentX").getInt(cell),
				type.getField("continentZ").getInt(cell),
				type.getField("erosionMask").getBoolean(cell),
				(int)terrain.getClass().getMethod("getId").invoke(terrain),
				(String)terrain.getClass().getMethod("getName").invoke(terrain),
				enumName(terrain.getClass().getMethod("getCategory").invoke(terrain)),
				enumName(type.getField("biome").get(cell)),
				enumName(type.getField("fakeWaterBiome").get(cell))
			);
		}

		private static String enumName(Object value) {
			return value instanceof Enum<?> enumValue ? enumValue.name() : null;
		}
	}

	private static void writeCell(DataOutputStream output, CellSnapshot snapshot) throws IOException {
		for(int bits : snapshot.floatBits()) {
			output.writeInt(bits);
		}
		output.writeInt(snapshot.continentX());
		output.writeInt(snapshot.continentZ());
		output.writeBoolean(snapshot.erosionMask());
		output.writeInt(snapshot.terrainId());
		writeString(output, snapshot.terrainName());
		writeString(output, snapshot.terrainCategory());
		writeString(output, snapshot.biome());
		writeString(output, snapshot.fakeWaterBiome());
	}

	private static final class OriginalRuntime implements AutoCloseable {
		private static final String PREFIX = "raccoonman.reterraforged.";
		private final ChildFirstLoader loader;
		private final ClassLoader previousContextLoader;
		private final Object preset;
		private final Codec<Object> presetCodec;
		private final Object context;
		private final Object generator;
		private final Object heightmap;
		private final Class<?> cellClass;
		private final Method applyTerrain;

		private OriginalRuntime(
			ChildFirstLoader loader,
			ClassLoader previousContextLoader,
			Object preset,
			Codec<Object> presetCodec,
			Object context,
			Object generator,
			Object heightmap,
			Class<?> cellClass,
			Method applyTerrain
		) {
			this.loader = loader;
			this.previousContextLoader = previousContextLoader;
			this.preset = preset;
			this.presetCodec = presetCodec;
			this.context = context;
			this.generator = generator;
			this.heightmap = heightmap;
			this.cellClass = cellClass;
			this.applyTerrain = applyTerrain;
		}

		@SuppressWarnings({"unchecked", "rawtypes"})
		private static OriginalRuntime open(Path jar, JsonElement configuredPreset) throws Exception {
			ChildFirstLoader loader = new ChildFirstLoader(
				new URL[] {jar.toUri().toURL()},
				A75OracleSnapshotWorker.class.getClassLoader()
			);
			Thread thread = Thread.currentThread();
			ClassLoader previousContextLoader = thread.getContextClassLoader();
			thread.setContextClassLoader(loader);
			try {
				Class<?> presetClass = loader.loadClass(PREFIX + "data.worldgen.preset.settings.Preset");
				Codec<Object> presetCodec = (Codec<Object>)presetClass.getField("DIRECT_CODEC").get(null);
				Object preset;
				if(configuredPreset == null) {
					Class<?> presetsClass = loader.loadClass(PREFIX + "data.worldgen.preset.settings.Presets");
					preset = presetsClass.getMethod("makeRTFDefault").invoke(null);
				} else {
					preset = presetCodec.parse(JsonOps.INSTANCE, configuredPreset)
						.getOrThrow(message -> new IllegalArgumentException(
							"Could not decode configured preset with untouched A75: " + message
						));
				}

				Class<?> worldSettingsClass = loader.loadClass(PREFIX + "data.worldgen.preset.settings.WorldSettings");
				Object worldSettings = presetClass.getMethod("world").invoke(preset);
				Object properties = worldSettingsClass.getField("properties").get(worldSettings);
				Class<?> propertiesClass = properties.getClass();
				int seaLevel = propertiesClass.getField("seaLevel").getInt(properties);
				int terrainScaler = (int)propertiesClass.getMethod("terrainScaler").invoke(properties);

				Class<?> registriesClass = loader.loadClass(PREFIX + "registries.RTFRegistries");
				ResourceKey registryKey = (ResourceKey)registriesClass.getField("NOISE").get(null);
				MappedRegistry registry = new MappedRegistry(registryKey, Lifecycle.stable());

				Class<?> noisesClass = loader.loadClass(PREFIX + "world.worldgen.noise.module.Noises");
				Object groundNoise = noisesClass.getMethod("constant", float.class)
					.invoke(null, seaLevel / (float)terrainScaler);
				Class<?> terrainNoiseClass = loader.loadClass(PREFIX + "data.worldgen.preset.PresetTerrainTypeNoise");
				ResourceKey groundKey = (ResourceKey)terrainNoiseClass.getField("GROUND").get(null);
				registry.register(groundKey, groundNoise, RegistrationInfo.BUILT_IN);

				Object climateSettings = presetClass.getMethod("climate").invoke(preset);
				Field biomeEdgeShapeField = climateSettings.getClass().getField("biomeEdgeShape");
				Object biomeEdgeShape = biomeEdgeShapeField.get(climateSettings);
				Object edgeNoise = biomeEdgeShape.getClass().getMethod("build", int.class)
					.invoke(biomeEdgeShape, 0);
				Class<?> climateNoiseClass = loader.loadClass(PREFIX + "data.worldgen.preset.PresetClimateNoise");
				ResourceKey edgeKey = (ResourceKey)climateNoiseClass.getField("BIOME_EDGE_SHAPE").get(null);
				registry.register(edgeKey, edgeNoise, RegistrationInfo.BUILT_IN);
				HolderGetter<?> noiseLookup = registry.asLookup();

				Class<?> contextClass = loader.loadClass(PREFIX + "world.worldgen.GeneratorContext");
				Object context = contextClass.getMethod(
					"makeUncached",
					presetClass,
					HolderGetter.class,
					int.class,
					int.class,
					int.class,
					int.class
				).invoke(null, preset, noiseLookup, SEED, 3, 2, 1);
				Object generator = contextClass.getField("generator").get(context);
				Object heightmap = generator.getClass().getMethod("getHeightmap").invoke(generator);
				Class<?> cellClass = loader.loadClass(PREFIX + "world.worldgen.cell.Cell");
				Method applyTerrain = heightmap.getClass().getMethod(
					"applyTerrain",
					cellClass,
					float.class,
					float.class
				);
				return new OriginalRuntime(
					loader,
					previousContextLoader,
					preset,
					presetCodec,
					context,
					generator,
					heightmap,
					cellClass,
					applyTerrain
				);
			} catch(Throwable throwable) {
				thread.setContextClassLoader(previousContextLoader);
				try {
					loader.close();
				} catch(IOException closeFailure) {
					throwable.addSuppressed(closeFailure);
				}
				throw throwable;
			}
		}

		private JsonElement encodeDefaultPreset() {
			return this.presetCodec.encodeStart(JsonOps.INSTANCE, this.preset)
				.getOrThrow(message -> new IllegalArgumentException("Could not encode A75 default preset: " + message));
		}

		private CellSnapshot sampleTerrain(int x, int z) throws ReflectiveOperationException {
			Object cell = this.cellClass.getConstructor().newInstance();
			this.applyTerrain.invoke(this.heightmap, cell, (float)x, (float)z);
			return CellSnapshot.capture(cell);
		}

		@SuppressWarnings("unchecked")
		private void writeTileStages(
			DataOutputStream output,
			int tileX,
			int tileZ,
			int factor,
			int border
		) throws Exception {
			Class<?> sizeClass = this.loader.loadClass(PREFIX + "world.worldgen.densityfunction.tile.Size");
			Object blockSize = sizeClass.getMethod("blocks", int.class, int.class)
				.invoke(null, factor, border);
			Object chunkSize = sizeClass.getMethod("chunks", int.class, int.class)
				.invoke(null, factor, border);
			int cellCount = (int)sizeClass.getMethod("arraySize").invoke(blockSize);
			int chunkCount = (int)sizeClass.getMethod("arraySize").invoke(chunkSize);

			Object cells = Array.newInstance(this.cellClass, cellCount);
			for(int index = 0; index < cellCount; index++) {
				Array.set(cells, index, this.cellClass.getConstructor().newInstance());
			}
			Class<?> tileClass = this.loader.loadClass(PREFIX + "world.worldgen.densityfunction.tile.Tile");
			Class<?> chunkClass = this.loader.loadClass(PREFIX + "world.worldgen.densityfunction.tile.Tile$Chunk");
			Object chunks = Array.newInstance(chunkClass, chunkCount);
			Class<?> resourceClass = this.loader.loadClass(PREFIX + "concurrent.Resource");
			Class<?> simpleResourceClass = this.loader.loadClass(PREFIX + "concurrent.SimpleResource");
			Consumer<Object> noClose = ignored -> {
			};
			Object cellResource = simpleResourceClass.getConstructor(Object.class, Consumer.class)
				.newInstance(cells, noClose);
			Object chunkResource = simpleResourceClass.getConstructor(Object.class, Consumer.class)
				.newInstance(chunks, noClose);
			Object tile = tileClass.getConstructor(
				int.class,
				int.class,
				int.class,
				int.class,
				sizeClass,
				sizeClass,
				resourceClass,
				resourceClass
			).newInstance(tileX, tileZ, factor, border, blockSize, chunkSize, cellResource, chunkResource);

			output.writeInt(5);
			try {
				this.fillNativeTile(tile, chunkSize, sizeClass, chunkClass);
				this.writeTileStage(output, "native-input", tile, tileClass, sizeClass);

				Class<?> worldFiltersClass = this.loader.loadClass(PREFIX + "world.worldgen.WorldFilters");
				Object filters = worldFiltersClass.getConstructor(this.context.getClass()).newInstance(this.context);
				Class<?> filterableClass = this.loader.loadClass(
					PREFIX + "world.worldgen.densityfunction.tile.filter.Filterable"
				);
				Method optional = worldFiltersClass.getDeclaredMethod(
					"applyOptionalFilters",
					filterableClass,
					int.class,
					int.class
				);
				Method required = worldFiltersClass.getDeclaredMethod(
					"applyRequiredFilters",
					filterableClass,
					int.class,
					int.class
				);
				optional.setAccessible(true);
				required.setAccessible(true);
				optional.invoke(filters, tile, tileX, tileZ);
				this.writeTileStage(output, "optional-filters", tile, tileClass, sizeClass);
				required.invoke(filters, tile, tileX, tileZ);
				this.writeTileStage(output, "required-filters", tile, tileClass, sizeClass);
				worldFiltersClass.getMethod(
					"applyCorrections",
					filterableClass,
					int.class,
					int.class
				).invoke(filters, tile, tileX, tileZ);
				this.writeTileStage(output, "noise-correction", tile, tileClass, sizeClass);
			} finally {
				tileClass.getMethod("close").invoke(tile);
			}

			Object productionTile = ((CompletableFuture<?>)this.generator.getClass()
				.getMethod("generate", int.class, int.class)
				.invoke(this.generator, tileX, tileZ)).join();
			try {
				this.writeTileStage(output, "production-final", productionTile, tileClass, sizeClass);
			} finally {
				tileClass.getMethod("close").invoke(productionTile);
			}
			System.out.println("RTF_A75_WORKER_TILE stages=5, tile=" + tileX + "," + tileZ);
			System.out.flush();
		}

		private void fillNativeTile(
			Object tile,
			Object chunkSize,
			Class<?> sizeClass,
			Class<?> chunkClass
		) throws Exception {
			int chunks = (int)sizeClass.getMethod("total").invoke(chunkSize);
			Method getChunkWriter = tile.getClass().getMethod("getChunkWriter", int.class, int.class);
			Method chunkBlockX = chunkClass.getMethod("getBlockX");
			Method chunkBlockZ = chunkClass.getMethod("getBlockZ");
			Method chunkCell = chunkClass.getMethod("getCell", int.class, int.class);
			Class<?> heightmapClass = this.heightmap.getClass();
			Method applyRivers = heightmapClass.getMethod(
				"applyRivers",
				this.cellClass,
				float.class,
				float.class,
				this.loader.loadClass(PREFIX + "world.worldgen.cell.rivermap.Rivermap")
			);
			Method applyClimate = heightmapClass.getMethod(
				"applyClimate",
				this.cellClass,
				float.class,
				float.class,
				boolean.class
			);
			Class<?> rivermapClass = this.loader.loadClass(PREFIX + "world.worldgen.cell.rivermap.Rivermap");
			Method rivermapGet = rivermapClass.getMethod(
				"get",
				this.cellClass,
				rivermapClass,
				heightmapClass
			);
			for(int chunkZ = 0; chunkZ < chunks; chunkZ++) {
				for(int chunkX = 0; chunkX < chunks; chunkX++) {
					Object chunk = getChunkWriter.invoke(tile, chunkX, chunkZ);
					int blockX = (int)chunkBlockX.invoke(chunk);
					int blockZ = (int)chunkBlockZ.invoke(chunk);
					Object rivermap = null;
					for(int dz = 0; dz < 16; dz++) {
						for(int dx = 0; dx < 16; dx++) {
							int worldX = blockX + dx;
							int worldZ = blockZ + dz;
							Object cell = chunkCell.invoke(chunk, dx, dz);
							this.applyTerrain.invoke(this.heightmap, cell, (float)worldX, (float)worldZ);
							rivermap = rivermapGet.invoke(null, cell, rivermap, this.heightmap);
							applyRivers.invoke(
								this.heightmap,
								cell,
								(float)worldX,
								(float)worldZ,
								rivermap
							);
							applyClimate.invoke(this.heightmap, cell, (float)worldX, (float)worldZ, true);
						}
					}
				}
			}
		}

		private void writeTileStage(
			DataOutputStream output,
			String stage,
			Object tile,
			Class<?> tileClass,
			Class<?> sizeClass
		) throws Exception {
			Object blockSize = tileClass.getMethod("getBlockSize").invoke(tile);
			int size = (int)sizeClass.getMethod("size").invoke(blockSize);
			int border = (int)sizeClass.getMethod("border").invoke(blockSize);
			int total = (int)sizeClass.getMethod("total").invoke(blockSize);
			Object[] cells = (Object[])tileClass.getMethod("getBacking").invoke(tile);
			writeString(output, stage);
			output.writeInt((int)tileClass.getMethod("getX").invoke(tile));
			output.writeInt((int)tileClass.getMethod("getZ").invoke(tile));
			output.writeInt((int)tileClass.getMethod("getBlockX").invoke(tile));
			output.writeInt((int)tileClass.getMethod("getBlockZ").invoke(tile));
			output.writeInt(size);
			output.writeInt(border);
			output.writeInt(total);
			output.writeInt(cells.length);
			for(Object cell : cells) {
				writeCell(output, CellSnapshot.capture(cell));
			}
		}

		@Override
		public void close() throws IOException {
			Thread.currentThread().setContextClassLoader(this.previousContextLoader);
			this.loader.close();
		}
	}

	private static final class ChildFirstLoader extends URLClassLoader {
		private ChildFirstLoader(URL[] urls, ClassLoader parent) {
			super(urls, parent);
		}

		@Override
		protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
			if(name.startsWith(OriginalRuntime.PREFIX)) {
				synchronized(this.getClassLoadingLock(name)) {
					Class<?> loaded = this.findLoadedClass(name);
					if(loaded == null) {
						loaded = this.findClass(name);
					}
					if(resolve) {
						this.resolveClass(loaded);
					}
					return loaded;
				}
			}
			return super.loadClass(name, resolve);
		}
	}
}
