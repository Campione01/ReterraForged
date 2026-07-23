package raccoonman.reterraforged.world.worldgen.noise.module;

import com.mojang.serialization.Codec;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.resources.RegistryFileCodec;
import raccoonman.reterraforged.registries.RTFRegistries;

public interface Noise {
    public static final Codec<Noise> DIRECT_CODEC = Noises.DIRECT_CODEC;
    public static final Codec<Holder<Noise>> CODEC = RegistryFileCodec.create(RTFRegistries.NOISE, DIRECT_CODEC);
    public static final Codec<Noise> HOLDER_HELPER_CODEC = CODEC.xmap(Noises.HolderHolder::new, noise -> {
        if (noise instanceof Noises.HolderHolder holderHolder) {
            return holderHolder.holder();
        }
        return new Holder.Direct<>(noise);
    });

	float compute(float x, float z, int seed);

	default float computeRoot(float x, float z, int seed) {
		return NoiseRootRuntime.computeRoot(this, x, z, seed);
	}

	/**
	 * Whether this original RTF node owns a batch implementation that preserves
	 * its scalar result. Third-party implementations are scalar by default.
	 */
	default boolean supportsBulk() {
		return false;
	}

	default void fill(NoiseBatch batch, int seed, float[] output) {
		batch.validate(output);
		for(int index = 0; index < output.length; index++) {
			output[index] = this.compute(batch.xAt(index), batch.zAt(index), seed);
		}
	}
	
	float minValue();
	
	float maxValue();
	
	Noise mapAll(Visitor visitor);
	
	MapCodec<? extends Noise> codec();
	
	public interface Visitor {
		Noise apply(Noise input);
	}
}
