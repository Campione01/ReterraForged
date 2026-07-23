package raccoonman.reterraforged.world.worldgen.noise.domain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise.Visitor;
import raccoonman.reterraforged.world.worldgen.noise.module.NoiseBatch;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;

public record DomainWarp(Noise x, Noise z, Noise mappedX, Noise mappedZ, Noise distance) implements Domain {
	public static final MapCodec<DomainWarp> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Noise.HOLDER_HELPER_CODEC.fieldOf("x").forGetter(DomainWarp::x),
		Noise.HOLDER_HELPER_CODEC.fieldOf("z").forGetter(DomainWarp::z),
		Noise.HOLDER_HELPER_CODEC.fieldOf("distance").forGetter(DomainWarp::distance)
	).apply(instance, DomainWarp::new));
	
	public DomainWarp(Noise x, Noise z, Noise distance) {
		this(x, z, map(x), map(z), distance);
	}
	
	@Override
	public float getOffsetX(float x, float z, int seed) {
		return this.mappedX.compute(x, z, seed) * this.distance.compute(x, z, seed);
	}

	@Override
	public float getOffsetZ(float x, float z, int seed) {
		return this.mappedZ.compute(x, z, seed) * this.distance.compute(x, z, seed);
	}

	@Override
	public boolean supportsBulk() {
		return this.mappedX.supportsBulk() && this.mappedZ.supportsBulk() && this.distance.supportsBulk();
	}

	@Override
	public void fillOffsetX(NoiseBatch batch, int seed, float[] output) {
		this.mappedX.fill(batch, seed, output);
		float[] distanceValues = batch.acquire();
		try {
			this.distance.fill(batch, seed, distanceValues);
			for(int index = 0; index < output.length; index++) {
				output[index] = output[index] * distanceValues[index];
			}
		} finally {
			batch.release(distanceValues);
		}
	}

	@Override
	public void fillOffsetZ(NoiseBatch batch, int seed, float[] output) {
		this.mappedZ.fill(batch, seed, output);
		float[] distanceValues = batch.acquire();
		try {
			this.distance.fill(batch, seed, distanceValues);
			for(int index = 0; index < output.length; index++) {
				output[index] = output[index] * distanceValues[index];
			}
		} finally {
			batch.release(distanceValues);
		}
	}

	@Override
	public float getRootOffsetX(float x, float z, int seed) {
		return this.mappedX.computeRoot(x, z, seed) * this.distance.computeRoot(x, z, seed);
	}

	@Override
	public float getRootOffsetZ(float x, float z, int seed) {
		return this.mappedZ.computeRoot(x, z, seed) * this.distance.computeRoot(x, z, seed);
	}

	@Override
	public Domain mapAll(Visitor visitor) {
		return new DomainWarp(this.x.mapAll(visitor), this.z.mapAll(visitor), this.mappedX.mapAll(visitor), this.mappedZ.mapAll(visitor), this.distance.mapAll(visitor));
	}

	@Override
	public MapCodec<DomainWarp> codec() {
		return CODEC;
	}
	
    private static Noise map(Noise in) {
    	if (in.minValue() == -0.5F && in.maxValue() == 0.5F) {
    		return in;
    	}
    	return Noises.map(in, -0.5F, 0.5F);
    }
}
