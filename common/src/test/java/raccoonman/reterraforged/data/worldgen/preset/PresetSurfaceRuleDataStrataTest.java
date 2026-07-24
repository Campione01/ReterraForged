package raccoonman.reterraforged.data.worldgen.preset;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderGetter;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.SurfaceRules;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;

class PresetSurfaceRuleDataStrataTest {

	@BeforeAll
	static void bootstrap() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void disabledDecoratorDoesNotResolveOrAppendStrataNoise() {
		Preset preset = Presets.makeRTFDefault();
		preset.miscellaneous().strataDecorator = false;
		AtomicInteger lookupAccesses = new AtomicInteger();
		HolderGetter<Noise> noise = throwingLookup(lookupAccesses);

		SurfaceRules.RuleSource rule = assertDoesNotThrow(() -> PresetSurfaceRuleData.overworld(preset, null, noise));

		assertNotNull(rule);
		assertEquals(0, lookupAccesses.get());
	}

	@Test
	void enabledDecoratorStillResolvesTheStrataRule() {
		Preset preset = Presets.makeRTFDefault();
		preset.miscellaneous().strataDecorator = true;
		AtomicInteger lookupAccesses = new AtomicInteger();
		HolderGetter<Noise> noise = throwingLookup(lookupAccesses);

		assertThrows(LookupAccessed.class, () -> PresetSurfaceRuleData.overworld(preset, null, noise));
		assertEquals(1, lookupAccesses.get());
	}

	@SuppressWarnings("unchecked")
	private static HolderGetter<Noise> throwingLookup(AtomicInteger lookupAccesses) {
		return (HolderGetter<Noise>) Proxy.newProxyInstance(
			HolderGetter.class.getClassLoader(),
			new Class<?>[] { HolderGetter.class },
			(proxy, method, args) -> {
				lookupAccesses.incrementAndGet();
				throw new LookupAccessed();
			}
		);
	}

	private static final class LookupAccessed extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
