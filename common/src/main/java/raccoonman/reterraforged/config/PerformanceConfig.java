package raccoonman.reterraforged.config;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import com.mojang.serialization.DataResult;

import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.concurrent.ThreadPools;
import raccoonman.reterraforged.platform.ConfigUtil;

public record PerformanceConfig(int tileSize, int batchCount, int threadCount) {
	public static final Path DEFAULT_FILE_PATH = ConfigUtil.rtf("performance.conf");
	
    public static final int MAX_TILE_SIZE = 8;
    public static final int MAX_BATCH_COUNT = 20;
    public static final int MAX_THREAD_COUNT = Runtime.getRuntime().availableProcessors() * 2;

    public static DataResult<PerformanceConfig> read(Path path) {
    	if(!Files.exists(path)) {
    		PerformanceConfig defaults = makeDefault();
    		writeDefaults(path, defaults);
    		apply(defaults);
    		return DataResult.success(defaults);
    	}
    	try(BufferedReader reader = Files.newBufferedReader(path)) {
    		Properties props = new Properties();
    		props.load(reader);
    		int tileSize = clamp(parseInt(props, "tileSize", 3), 1, MAX_TILE_SIZE);
    		int batchCount = clamp(parseInt(props, "batchCount", 6), 1, MAX_BATCH_COUNT);
    		int threadCount = clamp(parseInt(props, "threadCount", ThreadPools.defaultWorkerThreads()), 1, MAX_THREAD_COUNT);
    		PerformanceConfig config = new PerformanceConfig(tileSize, batchCount, threadCount);
    		apply(config);
    		return DataResult.success(config);
    	} catch(IOException e) {
    		RTFCommon.LOGGER.error("Failed to read performance config, using defaults", e);
    		PerformanceConfig defaults = makeDefault();
    		apply(defaults);
    		return DataResult.success(defaults);
    	}
    }

    private static void writeDefaults(Path path, PerformanceConfig cfg) {
    	try {
    		Path parent = path.getParent();
    		if(parent != null) {
    			Files.createDirectories(parent);
    		}
    		try(BufferedWriter writer = Files.newBufferedWriter(path)) {
    			writer.write("# ReTerraForged Performance Config\n");
    			writer.write("# Delete this file before exporting a modpack so each machine can generate suitable defaults.\n");
    			writer.write("#\n");
    			writer.write("# tileSize: terrain pre-generation tile size (1-" + MAX_TILE_SIZE + "), default 3\n");
    			writer.write("# batchCount: parallel batches per tile axis (1-" + MAX_BATCH_COUNT + ")\n");
    			writer.write("# threadCount: terrain generation worker threads (1-" + MAX_THREAD_COUNT + ")\n");
    			writer.write("# Default threadCount keeps two CPU cores free for the main/render threads.\n");
    			writer.write("#\n");
    			writer.write("tileSize=" + cfg.tileSize() + "\n");
    			writer.write("batchCount=" + cfg.batchCount() + "\n");
    			writer.write("threadCount=" + cfg.threadCount() + "\n");
    		}
    	} catch(IOException e) {
    		RTFCommon.LOGGER.error("Failed to write default performance config", e);
    	}
    }

    private static void apply(PerformanceConfig config) {
    	ThreadPools.configureWorldGenThreads(config.threadCount());
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

    private static int clamp(int value, int min, int max) {
    	return Math.max(min, Math.min(max, value));
    }
    
    public static PerformanceConfig makeDefault() {
    	int threadCount = ThreadPools.defaultWorkerThreads();
    	int batchCount = Math.max(2, (int) Math.ceil(Math.sqrt(threadCount)));
    	return new PerformanceConfig(3, batchCount, threadCount);
    }
}
