package raccoonman.reterraforged.world.worldgen.quicknoise;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import raccoonman.reterraforged.RTFCommon;

public final class QuickNoiseNative {
	public static final int TILE_SIZE = 32;
	public static final int TILE_SAMPLES = TILE_SIZE * TILE_SIZE * TILE_SIZE;
	private static final int TILE_BYTES = TILE_SAMPLES * Float.BYTES;
	private static final List<String> WINDOWS_X64_LIBRARIES = List.of(
		"reterraforged_quick_noise_dispatch.dll",
		"reterraforged_quick_noise_avx2.dll",
		"reterraforged_quick_noise_scalar.dll"
	);
	private static final ThreadLocal<ByteBuffer> OUTPUT_BUFFER = ThreadLocal.withInitial(() ->
		ByteBuffer.allocateDirect(TILE_BYTES).order(ByteOrder.nativeOrder())
	);

	private static volatile State state = State.NEW;
	@Nullable
	private static volatile String backendName;

	private QuickNoiseNative() {
	}

	static synchronized boolean initialize() {
		if(state != State.NEW) {
			return state == State.READY;
		}
		state = State.FAILED;
		String platform = platform();
		if(platform == null) {
			RTFCommon.LOGGER.info("RTF quick-noise native backend is unavailable on this platform");
			return false;
		}

		try {
			Path directory = extractLibraries(platform, WINDOWS_X64_LIBRARIES);
			System.load(directory.resolve(WINDOWS_X64_LIBRARIES.getFirst()).toAbsolutePath().toString());
			String result = nativeInitialize(directory.toAbsolutePath().toString());
			if(result == null || result.startsWith("ERROR:")) {
				RTFCommon.LOGGER.error("RTF quick-noise native initialization failed: {}", result);
				return false;
			}
			backendName = result;
			state = State.READY;
			RTFCommon.LOGGER.info("RTF quick-noise initialized with the {} backend", result);
			return true;
		} catch(Throwable throwable) {
			RTFCommon.LOGGER.error("RTF quick-noise native initialization failed", throwable);
			return false;
		}
	}

	static boolean isAvailable() {
		return state == State.READY || initialize();
	}

	public static void requireAvailable() {
		if(!isAvailable()) {
			throw new IllegalStateException("RTF QUICK_V1 requires the bundled quick-noise native backend on Windows x86-64");
		}
	}

	@Nullable
	static String backendName() {
		return backendName;
	}

	public static boolean fillTile(long seed, int tileX, int tileY, int tileZ, float chamberBias, float spaghettiWidth, float noodleWidth, float[] output) {
		if(output.length != TILE_SAMPLES || !isAvailable()) {
			return false;
		}
		ByteBuffer buffer = OUTPUT_BUFFER.get();
		buffer.clear();
		int result = nativeFillTile(seed, tileX, tileY, tileZ, chamberBias, spaghettiWidth, noodleWidth, buffer);
		if(result != 0) {
			RTFCommon.LOGGER.error("RTF quick-noise tile generation failed with native error {}", result);
			return false;
		}
		buffer.asFloatBuffer().get(output);
		return true;
	}

	@Nullable
	private static String platform() {
		String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
		String arch = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
		boolean x64 = arch.equals("amd64") || arch.equals("x86_64");
		return os.contains("win") && x64 ? "windows-x86_64" : null;
	}

	private static Path extractLibraries(String platform, List<String> libraries) throws IOException, NoSuchAlgorithmException {
		MessageDigest digest = MessageDigest.getInstance("SHA-256");
		byte[][] contents = new byte[libraries.size()][];
		for(int i = 0; i < libraries.size(); i++) {
			String resource = "/META-INF/reterraforged/natives/" + platform + "/" + libraries.get(i);
			try(InputStream input = QuickNoiseNative.class.getResourceAsStream(resource)) {
				if(input == null) {
					throw new IOException("Missing native resource " + resource);
				}
				contents[i] = input.readAllBytes();
				digest.update(contents[i]);
			}
		}

		String version = HexFormat.of().formatHex(digest.digest()).substring(0, 24);
		Path directory = Path.of(System.getProperty("java.io.tmpdir"), "reterraforged-natives", version);
		Files.createDirectories(directory);
		for(int i = 0; i < libraries.size(); i++) {
			Path target = directory.resolve(libraries.get(i));
			byte[] content = contents[i];
			if(Files.isRegularFile(target) && Files.size(target) == content.length) {
				continue;
			}
			Path temporary = directory.resolve(libraries.get(i) + "." + ProcessHandle.current().pid() + ".tmp");
			Files.write(temporary, content);
			try {
				Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
			} catch(IOException ignored) {
				Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
			}
		}
		return directory;
	}

	private static native String nativeInitialize(String directory);

	private static native int nativeFillTile(long seed, int tileX, int tileY, int tileZ, float chamberBias, float spaghettiWidth, float noodleWidth, ByteBuffer output);

	private enum State {
		NEW,
		READY,
		FAILED
	}
}
