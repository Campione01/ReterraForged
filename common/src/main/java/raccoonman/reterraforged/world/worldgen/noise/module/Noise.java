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
    
	default float compute(float x, float z, int seed) {
		return QuickNoiseRuntime.compute(this, x, z, seed);
	}

	default float computeLegacy(float x, float z, int seed) {
		throw new UnsupportedOperationException("Noise implementation must override compute or computeLegacy");
	}
	
	float minValue();
	
	float maxValue();
	
	Noise mapAll(Visitor visitor);
	
	MapCodec<? extends Noise> codec();
	
	public interface Visitor {
		Noise apply(Noise input);
	}
}
