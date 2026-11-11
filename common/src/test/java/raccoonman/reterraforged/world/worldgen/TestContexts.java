package raccoonman.reterraforged.world.worldgen;

import com.mojang.serialization.Lifecycle;
import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.core.MappedRegistry;
import net.minecraft.core.RegistrationInfo;
import net.minecraft.core.Registry;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.Bootstrap;
import raccoonman.reterraforged.data.worldgen.preset.PresetNoiseData;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;

/** Real generator contexts on the default preset for offline tests. */
public final class TestContexts {
	public static final int TILE_SIZE = 3;
	public static final int BATCH_COUNT = 2;

	private TestContexts() {}

	public static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	public static Preset preset() {
		return Presets.makeRTFDefault();
	}

	public static GeneratorContext cached(int seed) {
		Preset preset = preset();
		return GeneratorContext.makeCached(preset, noise(preset), seed, TILE_SIZE, BATCH_COUNT, true);
	}

	public static HolderGetter<Noise> noise(Preset preset) {
		var registry = new MappedRegistry<>(RTFRegistries.NOISE, Lifecycle.stable());
		var lookup = registry.createRegistrationLookup();
		PresetNoiseData.bootstrap(preset, new BootstrapContext<Noise>() {
			@Override
			public Holder.Reference<Noise> register(ResourceKey<Noise> key, Noise noise, Lifecycle lifecycle) {
				return registry.register(key, noise, RegistrationInfo.BUILT_IN);
			}

			@Override
			@SuppressWarnings("unchecked")
			public <T> HolderGetter<T> lookup(ResourceKey<? extends Registry<? extends T>> key) {
				if (!RTFRegistries.NOISE.equals(key)) {
					throw new IllegalArgumentException("Unexpected noise dependency " + key);
				}
				return (HolderGetter<T>) lookup;
			}
		});
		registry.freeze();
		return registry.asLookup();
	}
}
