package raccoonman.reterraforged.world.worldgen.noise.domain;

import java.util.function.Function;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;

import raccoonman.reterraforged.registries.RTFBuiltInRegistries;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.NoiseBatch;

public interface Domain {
    public static final Codec<Domain> CODEC = RTFBuiltInRegistries.DOMAIN_TYPE.byNameCodec().dispatch(Domain::codec, Function.identity());
	
    float getOffsetX(float x, float z, int seed);
    
    float getOffsetZ(float x, float z, int seed);

    default boolean supportsBulk() {
        return false;
    }

    default void fillOffsetX(NoiseBatch batch, int seed, float[] output) {
        for(int index = 0; index < output.length; index++) {
            output[index] = this.getOffsetX(batch.xAt(index), batch.zAt(index), seed);
        }
    }

    default void fillOffsetZ(NoiseBatch batch, int seed, float[] output) {
        for(int index = 0; index < output.length; index++) {
            output[index] = this.getOffsetZ(batch.xAt(index), batch.zAt(index), seed);
        }
    }

    default void fillX(NoiseBatch batch, int seed, float[] output) {
        this.fillOffsetX(batch, seed, output);
        for(int index = 0; index < output.length; index++) {
            output[index] = batch.xAt(index) + output[index];
        }
    }

    default void fillZ(NoiseBatch batch, int seed, float[] output) {
        this.fillOffsetZ(batch, seed, output);
        for(int index = 0; index < output.length; index++) {
            output[index] = batch.zAt(index) + output[index];
        }
    }

    default float getRootOffsetX(float x, float z, int seed) {
        return this.getOffsetX(x, z, seed);
    }

    default float getRootOffsetZ(float x, float z, int seed) {
        return this.getOffsetZ(x, z, seed);
    }
    
    Domain mapAll(Noise.Visitor visitor);
    
    MapCodec<? extends Domain> codec();

    default float getX(float x, float z, int seed) {
        return x + this.getOffsetX(x, z, seed);
    }
    
    default float getZ(float x, float z, int seed) {
        return z + this.getOffsetZ(x, z, seed);
    }

    default float getRootX(float x, float z, int seed) {
        return x + this.getRootOffsetX(x, z, seed);
    }

    default float getRootZ(float x, float z, int seed) {
        return z + this.getRootOffsetZ(x, z, seed);
    }
}
