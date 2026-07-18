package raccoonman.reterraforged.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Properties;

import com.mojang.serialization.DataResult;

import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.platform.ConfigUtil;

public record OpenClConfig(Mode mode, int minimumBatchSize, int failureLimit, boolean allowCpuDevice) {

	private static final int MIN_BATCH_SIZE = 1;
	private static final int MAX_BATCH_SIZE = 4096;
	private static final int MIN_FAILURE_LIMIT = 1;
	private static final int MAX_FAILURE_LIMIT = 16;

	public static Path defaultFilePath() {
		return ConfigUtil.rtf("opencl.conf");
	}

	public static DataResult<OpenClConfig> read(Path path) {
		if(!Files.exists(path)) {
			OpenClConfig defaults = makeDefault();
			writeDefaults(path, defaults);
			return DataResult.success(defaults);
		}
		try(BufferedReader reader = Files.newBufferedReader(path)) {
			Properties props = new Properties();
			props.load(reader);
			Mode mode = Mode.parse(props.getProperty("mode"), Mode.AUTO);
			int minimumBatchSize = clamp(parseInt(props, "minimumBatchSize", 32), MIN_BATCH_SIZE, MAX_BATCH_SIZE);
			int failureLimit = clamp(parseInt(props, "failureLimit", 1), MIN_FAILURE_LIMIT, MAX_FAILURE_LIMIT);
			boolean allowCpuDevice = parseBoolean(props, "allowCpuDevice", false);
			return DataResult.success(new OpenClConfig(mode, minimumBatchSize, failureLimit, allowCpuDevice));
		} catch(IOException e) {
			RTFCommon.LOGGER.error("Failed to read OpenCL config, using defaults", e);
			return DataResult.success(makeDefault());
		}
	}

	public static OpenClConfig makeDefault() {
		return new OpenClConfig(Mode.AUTO, 32, 1, false);
	}

	private static void writeDefaults(Path path, OpenClConfig config) {
		try {
			Path parent = path.getParent();
			if(parent != null) {
				Files.createDirectories(parent);
			}
			try(BufferedWriter writer = Files.newBufferedWriter(path)) {
				writer.write("# ReTerraForged OpenCL Config\n");
				writer.write("# AUTO enables QUICK_V1 only after exact CPU/GPU tile parity and a local speed check.\n");
				writer.write("# Unavailable, busy, rejected, or failed GPU work falls back to the same native CPU algorithm.\n");
				writer.write("# This backend is independent from C2ME and does not share its executors or OpenCL context.\n");
				writer.write("#\n");
				writer.write("mode=" + config.mode() + "\n");
				writer.write("minimumBatchSize=" + config.minimumBatchSize() + "\n");
				writer.write("failureLimit=" + config.failureLimit() + "\n");
				writer.write("allowCpuDevice=" + config.allowCpuDevice() + "\n");
			}
		} catch(IOException e) {
			RTFCommon.LOGGER.error("Failed to write default OpenCL config", e);
		}
	}

	private static int parseInt(Properties props, String key, int fallback) {
		String value = props.getProperty(key);
		if(value == null) {
			return fallback;
		}
		try {
			return Integer.parseInt(value.trim());
		} catch(NumberFormatException e) {
			return fallback;
		}
	}

	private static boolean parseBoolean(Properties props, String key, boolean fallback) {
		String value = props.getProperty(key);
		if(value == null) {
			return fallback;
		}
		return switch(value.trim().toLowerCase(Locale.ROOT)) {
			case "true", "yes", "on", "1" -> true;
			case "false", "no", "off", "0" -> false;
			default -> fallback;
		};
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	public enum Mode {
		OFF,
		AUTO,
		ON;

		private static Mode parse(String value, Mode fallback) {
			if(value == null) {
				return fallback;
			}
			try {
				return valueOf(value.trim().toUpperCase(Locale.ROOT));
			} catch(IllegalArgumentException e) {
				return fallback;
			}
		}
	}
}
