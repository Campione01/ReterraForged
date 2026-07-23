package raccoonman.reterraforged.world.worldgen.noise.module;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

record Frequency(Noise input, Noise xFreq, Noise zFreq) implements Noise {
	public static final MapCodec<Frequency> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Noise.HOLDER_HELPER_CODEC.fieldOf("input").forGetter(Frequency::input),
		Noise.HOLDER_HELPER_CODEC.fieldOf("x_freq").forGetter(Frequency::xFreq),
		Noise.HOLDER_HELPER_CODEC.fieldOf("z_freq").forGetter(Frequency::zFreq)
	).apply(instance, Frequency::new));
	
	@Override
	public float compute(float x, float z, int seed) {
		float xFreq = this.xFreq.compute(x, z, seed);
		float zFreq = this.zFreq.compute(x, z, seed);
		return this.input.compute(x * xFreq, z * zFreq, seed);
	}

	@Override
	public boolean supportsBulk() {
		return this.input.supportsBulk() && this.xFreq.supportsBulk() && this.zFreq.supportsBulk();
	}

	@Override
	public void fill(NoiseBatch batch, int seed, float[] output) {
		float[] xCoordinates = batch.acquire();
		float[] zCoordinates = batch.acquire();
		try {
			this.xFreq.fill(batch, seed, xCoordinates);
			this.zFreq.fill(batch, seed, zCoordinates);
			for(int index = 0; index < output.length; index++) {
				xCoordinates[index] = batch.xAt(index) * xCoordinates[index];
				zCoordinates[index] = batch.zAt(index) * zCoordinates[index];
			}
			this.input.fill(batch.transformed(xCoordinates, zCoordinates), seed, output);
		} finally {
			batch.release(zCoordinates);
			batch.release(xCoordinates);
		}
	}

	@Override
	public float minValue() {
		return this.input.minValue();
	}

	@Override
	public float maxValue() {
		return this.input.maxValue();
	}

	@Override
	public Noise mapAll(Visitor visitor) {
		return visitor.apply(new Frequency(this.input.mapAll(visitor), this.xFreq.mapAll(visitor), this.zFreq.mapAll(visitor)));
	}

	@Override
	public MapCodec<Frequency> codec() {
		return CODEC;
	}
}
