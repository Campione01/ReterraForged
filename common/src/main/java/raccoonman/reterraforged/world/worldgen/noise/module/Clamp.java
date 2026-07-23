package raccoonman.reterraforged.world.worldgen.noise.module;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

record Clamp(Noise input, Noise min, Noise max) implements Noise {
	public static final MapCodec<Clamp> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Noise.HOLDER_HELPER_CODEC.fieldOf("input").forGetter(Clamp::input),
		Noise.HOLDER_HELPER_CODEC.fieldOf("min").forGetter(Clamp::min),
		Noise.HOLDER_HELPER_CODEC.fieldOf("max").forGetter(Clamp::max)
	).apply(instance, Clamp::new));
	
	@Override
	public float compute(float x, float z, int seed) {
		return NoiseUtil.clamp(this.input.compute(x, z, seed), this.min.compute(x, z, seed), this.max.compute(x, z, seed));
	}

	@Override
	public boolean supportsBulk() {
		return this.input.supportsBulk() && this.min.supportsBulk() && this.max.supportsBulk();
	}

	@Override
	public void fill(NoiseBatch batch, int seed, float[] output) {
		this.input.fill(batch, seed, output);
		float[] minValues = batch.acquire();
		float[] maxValues = batch.acquire();
		try {
			this.min.fill(batch, seed, minValues);
			this.max.fill(batch, seed, maxValues);
			for(int index = 0; index < output.length; index++) {
				output[index] = NoiseUtil.clamp(output[index], minValues[index], maxValues[index]);
			}
		} finally {
			batch.release(maxValues);
			batch.release(minValues);
		}
	}

	@Override
	public float minValue() {
		return this.min.minValue();
	}

	@Override
	public float maxValue() {
		return this.max.maxValue();
	}

	@Override
	public Noise mapAll(Visitor visitor) {
		return visitor.apply(new Clamp(this.input.mapAll(visitor), this.min.mapAll(visitor), this.max.mapAll(visitor)));
	}

	@Override
	public MapCodec<Clamp> codec() {
		return CODEC;
	}
}
