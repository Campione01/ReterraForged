package raccoonman.reterraforged.world.worldgen.opencl;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

import org.jetbrains.annotations.Nullable;

import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.config.OpenClConfig;
import raccoonman.reterraforged.world.worldgen.quicknoise.QuickNoiseNative;

public final class OpenClManager {
	private static final Map<String, AtomicReference<Verification>> VERIFICATION = new ConcurrentHashMap<>();
	private static final AtomicReference<QuickVerification> QUICK_VERIFICATION = new AtomicReference<>(QuickVerification.UNVERIFIED);
	private static final int[][] QUICK_VERIFICATION_TILES = {
		{ 0, 0, 0 },
		{ -3, -1, 5 },
		{ 17, 0, -11 }
	};

	@Nullable
	private static volatile OpenClConfig config;
	@Nullable
	private static volatile OpenClRuntime runtime;
	private static volatile boolean initializationAttempted;

	private OpenClManager() {
	}

	public static void initialize() {
		getRuntime();
	}

	public static boolean isAvailable() {
		return getRuntime() != null;
	}

	public static boolean isEnabledByConfig() {
		return getConfig().mode() != OpenClConfig.Mode.OFF;
	}

	static int minimumBatchSize() {
		return getConfig().minimumBatchSize();
	}

	static boolean canUse(OpenClKernelTemplate template) {
		AtomicReference<Verification> state = state(template);
		return state.get() != Verification.REJECTED && getRuntime() != null;
	}

	static boolean requiresVerification(OpenClKernelTemplate template) {
		return state(template).get() == Verification.UNVERIFIED;
	}

	static boolean execute(OpenClKernelTemplate template, OpenClKernelTemplate.Batch batch, double[] output, @Nullable double[] expected) {
		AtomicReference<Verification> state = state(template);
		if(state.get() == Verification.REJECTED) {
			return false;
		}
		if(!executeUnchecked(template, batch, output)) {
			return false;
		}
		if(state.get() == Verification.UNVERIFIED) {
			if(expected == null) {
				return false;
			}
			int mismatch = firstMismatch(expected, output);
			if(mismatch >= 0) {
				rejectMismatch(template, mismatch, expected[mismatch], output[mismatch]);
				return false;
			}
			markVerified(template);
		}
		return true;
	}

	static boolean executeUnchecked(OpenClKernelTemplate template, OpenClKernelTemplate.Batch batch, double[] output) {
		OpenClRuntime activeRuntime = getRuntime();
		return activeRuntime != null && activeRuntime.tryExecute(template, batch, output);
	}

	public static boolean tryFillQuickNoiseTile(long seed, int[] octaveSeeds, int tileX, int tileY, int tileZ, float chamberBias, float spaghettiWidth, float noodleWidth, float[] output) {
		if(output.length != QuickNoiseNative.TILE_SAMPLES) {
			throw new IllegalArgumentException("Quick-noise OpenCL output must contain exactly one 32^3 tile");
		}
		OpenClRuntime activeRuntime = getRuntime();
		if(activeRuntime == null) {
			return false;
		}

		QuickVerification state = QUICK_VERIFICATION.get();
		if(state == QuickVerification.REJECTED || state == QuickVerification.VERIFYING) {
			return false;
		}
		if(state == QuickVerification.UNVERIFIED) {
			if(!QUICK_VERIFICATION.compareAndSet(QuickVerification.UNVERIFIED, QuickVerification.VERIFYING)) {
				return false;
			}
			QuickVerification result = verifyQuickNoise(activeRuntime, seed, octaveSeeds, chamberBias, spaghettiWidth, noodleWidth);
			QUICK_VERIFICATION.set(result);
			if(result != QuickVerification.VERIFIED) {
				return false;
			}
		}
		return activeRuntime.tryFillQuickNoiseTile(octaveSeeds, tileX, tileY, tileZ, chamberBias, spaghettiWidth, noodleWidth, output);
	}

	static void rejectMismatch(OpenClKernelTemplate template, int mismatch, double expected, double actual) {
		state(template).set(Verification.REJECTED);
		OpenClRuntime activeRuntime = runtime;
		if(activeRuntime != null) {
			activeRuntime.disable();
		}
		RTFCommon.LOGGER.error(
			"Rejected OpenCL density kernel {} after exact parity mismatch at index {}: cpu={}, gpu={}",
			template.id().substring(0, 12),
			mismatch,
			expected,
			actual
		);
	}

	static void markVerified(OpenClKernelTemplate template) {
		if(state(template).compareAndSet(Verification.UNVERIFIED, Verification.VERIFIED)) {
			RTFCommon.LOGGER.info("Verified OpenCL density kernel {} with exact CPU parity", template.id().substring(0, 12));
		}
	}

	static void recordCollectionFailure(OpenClKernelTemplate template, RuntimeException exception) {
		state(template).set(Verification.REJECTED);
		OpenClRuntime activeRuntime = runtime;
		if(activeRuntime != null) {
			activeRuntime.disable();
		}
		RTFCommon.LOGGER.error("Rejected OpenCL density kernel {} after CPU input collection failed", template.id().substring(0, 12), exception);
	}

	public static synchronized void close() {
		OpenClRuntime current = runtime;
		runtime = null;
		if(current != null) {
			current.close();
		}
		VERIFICATION.clear();
		QUICK_VERIFICATION.set(QuickVerification.UNVERIFIED);
		config = null;
		initializationAttempted = false;
	}

	private static AtomicReference<Verification> state(OpenClKernelTemplate template) {
		return VERIFICATION.computeIfAbsent(template.id(), key -> new AtomicReference<>(Verification.UNVERIFIED));
	}

	private static int firstMismatch(double[] expected, double[] actual) {
		for(int i = 0; i < expected.length; i++) {
			if(Double.doubleToRawLongBits(expected[i]) != Double.doubleToRawLongBits(actual[i])) {
				return i;
			}
		}
		return -1;
	}

	private static QuickVerification verifyQuickNoise(OpenClRuntime activeRuntime, long seed, int[] octaveSeeds, float chamberBias, float spaghettiWidth, float noodleWidth) {
		float[] expected = new float[QuickNoiseNative.TILE_SAMPLES];
		float[] actual = new float[QuickNoiseNative.TILE_SAMPLES];
		if(!QuickNoiseNative.fillTile(seed, 31, -2, 13, chamberBias, spaghettiWidth, noodleWidth, expected)
			|| !activeRuntime.tryFillQuickNoiseTile(octaveSeeds, 31, -2, 13, chamberBias, spaghettiWidth, noodleWidth, actual)) {
			return QuickVerification.UNVERIFIED;
		}
		int warmupMismatch = firstMismatch(expected, actual);
		if(warmupMismatch >= 0) {
			return rejectQuickNoiseMismatch(31, -2, 13, warmupMismatch, expected[warmupMismatch], actual[warmupMismatch]);
		}

		long cpuNanos = 0L;
		long gpuNanos = 0L;
		for(int[] tile : QUICK_VERIFICATION_TILES) {
			long cpuStart = System.nanoTime();
			if(!QuickNoiseNative.fillTile(seed, tile[0], tile[1], tile[2], chamberBias, spaghettiWidth, noodleWidth, expected)) {
				RTFCommon.LOGGER.error("Rejected QUICK_V1 OpenCL verification because the native reference backend failed");
				return QuickVerification.REJECTED;
			}
			cpuNanos += System.nanoTime() - cpuStart;
			long gpuStart = System.nanoTime();
			if(!activeRuntime.tryFillQuickNoiseTile(octaveSeeds, tile[0], tile[1], tile[2], chamberBias, spaghettiWidth, noodleWidth, actual)) {
				return QuickVerification.UNVERIFIED;
			}
			gpuNanos += System.nanoTime() - gpuStart;
			int mismatch = firstMismatch(expected, actual);
			if(mismatch >= 0) {
				return rejectQuickNoiseMismatch(tile[0], tile[1], tile[2], mismatch, expected[mismatch], actual[mismatch]);
			}
		}
		if(getConfig().mode() == OpenClConfig.Mode.AUTO && gpuNanos >= cpuNanos) {
			RTFCommon.LOGGER.info(
				"RTF QUICK_V1 OpenCL remains disabled because its verified tile time ({} us) did not beat native SIMD ({} us)",
				gpuNanos / 1_000L,
				cpuNanos / 1_000L
			);
			return QuickVerification.REJECTED;
		}
		RTFCommon.LOGGER.info(
			"Verified QUICK_V1 OpenCL backend against {} complete native tiles (GPU {} us, native {} us)",
			QUICK_VERIFICATION_TILES.length,
			gpuNanos / 1_000L,
			cpuNanos / 1_000L
		);
		return QuickVerification.VERIFIED;
	}

	private static QuickVerification rejectQuickNoiseMismatch(int tileX, int tileY, int tileZ, int mismatch, float expected, float actual) {
		RTFCommon.LOGGER.error(
			"Rejected QUICK_V1 OpenCL backend after exact tile mismatch at ({},{},{}) index {}: cpu={}, gpu={}",
			tileX, tileY, tileZ, mismatch, expected, actual
		);
		return QuickVerification.REJECTED;
	}

	private static int firstMismatch(float[] expected, float[] actual) {
		for(int i = 0; i < expected.length; i++) {
			if(Float.floatToRawIntBits(expected[i]) != Float.floatToRawIntBits(actual[i])) {
				return i;
			}
		}
		return -1;
	}

	private static OpenClConfig getConfig() {
		OpenClConfig current = config;
		if(current == null) {
				synchronized(OpenClManager.class) {
					current = config;
					if(current == null) {
						try {
							current = OpenClConfig.read(OpenClConfig.defaultFilePath())
								.resultOrPartial(RTFCommon.LOGGER::error)
								.orElseGet(OpenClConfig::makeDefault);
						} catch(Throwable unavailablePlatform) {
							current = new OpenClConfig(OpenClConfig.Mode.OFF, 32, 1, false);
							RTFCommon.LOGGER.debug("RTF OpenCL configuration is unavailable before the mod platform initializes");
						}
						config = current;
				}
			}
		}
		return current;
	}

	@Nullable
	private static OpenClRuntime getRuntime() {
		OpenClConfig currentConfig = getConfig();
		if(currentConfig.mode() == OpenClConfig.Mode.OFF) {
			return null;
		}
		OpenClRuntime current = runtime;
		if(current == null && !initializationAttempted) {
			synchronized(OpenClManager.class) {
				current = runtime;
				if(current == null && !initializationAttempted) {
					initializationAttempted = true;
					if(!hasLwjglCore()) {
						RTFCommon.LOGGER.info("RTF OpenCL is unavailable because this runtime does not provide LWJGL core; density generation will use CPU backends");
						current = null;
					} else try {
						current = OpenClRuntime.open(currentConfig);
					} catch(Throwable unavailableBinding) {
						RTFCommon.LOGGER.warn("RTF OpenCL binding is unavailable; density generation will use CPU backends ({})", unavailableBinding.toString());
						RTFCommon.LOGGER.debug("RTF OpenCL binding failure", unavailableBinding);
						current = null;
					}
					runtime = current;
				}
			}
		}
		return current != null && current.isEnabled() ? current : null;
	}

	private static boolean hasLwjglCore() {
		try {
			Class.forName("org.lwjgl.system.CallbackI", false, OpenClManager.class.getClassLoader());
			return true;
		} catch(ClassNotFoundException | LinkageError unavailable) {
			return false;
		}
	}

	private enum Verification {
		UNVERIFIED,
		VERIFIED,
		REJECTED
	}

	private enum QuickVerification {
		UNVERIFIED,
		VERIFYING,
		VERIFIED,
		REJECTED
	}
}
