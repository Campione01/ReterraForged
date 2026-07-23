package raccoonman.reterraforged.world.worldgen.noise.domain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import raccoonman.reterraforged.world.worldgen.noise.module.Noise.Visitor;
import raccoonman.reterraforged.world.worldgen.noise.module.NoiseBatch;

public record AddWarp(Domain input1, Domain input2) implements Domain {
	public static final MapCodec<AddWarp> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Domain.CODEC.fieldOf("input1").forGetter(AddWarp::input1),
		Domain.CODEC.fieldOf("input2").forGetter(AddWarp::input2)
	).apply(instance, AddWarp::new));
	
	@Override
	public float getOffsetX(float x, float z, int seed) {
		return this.input1.getOffsetX(x, z, seed) + this.input2.getOffsetX(x, z, seed);
	}

	@Override
	public float getOffsetZ(float x, float z, int seed) {
		return this.input1.getOffsetZ(x, z, seed) + this.input2.getOffsetZ(x, z, seed);
	}

	@Override
	public boolean supportsBulk() {
		return this.input1.supportsBulk() && this.input2.supportsBulk();
	}

	@Override
	public void fillOffsetX(NoiseBatch batch, int seed, float[] output) {
		this.input1.fillOffsetX(batch, seed, output);
		float[] right = batch.acquire();
		try {
			this.input2.fillOffsetX(batch, seed, right);
			for(int index = 0; index < output.length; index++) {
				output[index] = output[index] + right[index];
			}
		} finally {
			batch.release(right);
		}
	}

	@Override
	public void fillOffsetZ(NoiseBatch batch, int seed, float[] output) {
		this.input1.fillOffsetZ(batch, seed, output);
		float[] right = batch.acquire();
		try {
			this.input2.fillOffsetZ(batch, seed, right);
			for(int index = 0; index < output.length; index++) {
				output[index] = output[index] + right[index];
			}
		} finally {
			batch.release(right);
		}
	}

	@Override
	public float getRootOffsetX(float x, float z, int seed) {
		return this.input1.getRootOffsetX(x, z, seed) + this.input2.getRootOffsetX(x, z, seed);
	}

	@Override
	public float getRootOffsetZ(float x, float z, int seed) {
		return this.input1.getRootOffsetZ(x, z, seed) + this.input2.getRootOffsetZ(x, z, seed);
	}

	@Override
	public Domain mapAll(Visitor visitor) {
		return new AddWarp(this.input1.mapAll(visitor), this.input2.mapAll(visitor));
	}

	@Override
	public MapCodec<AddWarp> codec() {
		return CODEC;
	}
}
