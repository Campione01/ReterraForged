package raccoonman.reterraforged.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OpenClConfigTest {
	@Test
	void newConfigDefaultsToOff(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("reterraforged").resolve("opencl.conf");

		OpenClConfig config = OpenClConfig.read(path).getOrThrow();

		assertEquals(OpenClConfig.Mode.OFF, config.mode());
		assertTrue(Files.readString(path).contains("mode=OFF"));
	}

	@Test
	void missingModeInExistingConfigDefaultsToOff(@TempDir Path directory) throws IOException {
		Path path = directory.resolve("opencl.conf");
		Files.writeString(path, "minimumBatchSize=64\n");

		OpenClConfig config = OpenClConfig.read(path).getOrThrow();

		assertEquals(OpenClConfig.Mode.OFF, config.mode());
		assertEquals(64, config.minimumBatchSize());
	}
}
