package raccoonman.reterraforged.world.worldgen.noise.domain;

import java.util.Arrays;

import com.mojang.serialization.Codec;

import com.mojang.serialization.MapCodec;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise.Visitor;
import raccoonman.reterraforged.world.worldgen.noise.module.NoiseBatch;

public record DirectWarp() implements Domain {
	public static final MapCodec<DirectWarp> CODEC = MapCodec.unit(DirectWarp::new);
	
	@Override
	public float getOffsetX(float x, float z, int seed) {
		return 0.0F;
	}

	@Override
	public float getOffsetZ(float x, float z, int seed) {
		return 0.0F;
	}

	@Override
	public boolean supportsBulk() {
		return true;
	}

	@Override
	public void fillOffsetX(NoiseBatch batch, int seed, float[] output) {
		Arrays.fill(output, 0.0F);
	}

	@Override
	public void fillOffsetZ(NoiseBatch batch, int seed, float[] output) {
		Arrays.fill(output, 0.0F);
	}

	@Override
	public Domain mapAll(Visitor visitor) {
		return this;
	}

	@Override
	public MapCodec<DirectWarp> codec() {
		return CODEC;
	}
}
