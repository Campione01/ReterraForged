package raccoonman.reterraforged.world.worldgen.noise.module;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;

record Warp(Noise input, Domain domain) implements Noise {
	public static final MapCodec<Warp> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Noise.HOLDER_HELPER_CODEC.fieldOf("input").forGetter(Warp::input),
		Domain.CODEC.fieldOf("domain").forGetter(Warp::domain)
	).apply(instance, Warp::new));
	
	@Override
	public float compute(float x, float z, int seed) {
		return this.input.compute(this.domain.getX(x, z, seed), this.domain.getZ(x, z, seed), seed);
	}

	@Override
	public boolean supportsBulk() {
		return this.input.supportsBulk() && this.domain.supportsBulk();
	}

	@Override
	public void fill(NoiseBatch batch, int seed, float[] output) {
		float[] xCoordinates = batch.acquire();
		float[] zCoordinates = batch.acquire();
		try {
			this.domain.fillX(batch, seed, xCoordinates);
			this.domain.fillZ(batch, seed, zCoordinates);
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
		return visitor.apply(new Warp(this.input.mapAll(visitor), this.domain.mapAll(visitor)));
	}

	@Override
	public MapCodec<Warp> codec() {
		return CODEC;
	}
}
