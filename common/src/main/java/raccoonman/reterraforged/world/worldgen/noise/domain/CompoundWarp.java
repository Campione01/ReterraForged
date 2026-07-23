package raccoonman.reterraforged.world.worldgen.noise.domain;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import raccoonman.reterraforged.world.worldgen.noise.module.Noise.Visitor;
import raccoonman.reterraforged.world.worldgen.noise.module.NoiseBatch;

public record CompoundWarp(Domain input1, Domain input2) implements Domain {
	public static final MapCodec<CompoundWarp> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Domain.CODEC.fieldOf("input1").forGetter(CompoundWarp::input1),
		Domain.CODEC.fieldOf("input2").forGetter(CompoundWarp::input2)
	).apply(instance, CompoundWarp::new));
	
	@Override
	public float getOffsetX(float x, float z, int seed) {
        float ax = this.input1.getX(x, z, seed);
        float ay = this.input1.getZ(x, z, seed);
        return this.input2.getOffsetX(ax, ay, seed);
	}

	@Override
	public float getOffsetZ(float x, float z, int seed) {
        float ax = this.input1.getX(x, z, seed);
        float ay = this.input1.getZ(x, z, seed);
        return this.input2.getOffsetZ(ax, ay, seed);
	}

	@Override
	public boolean supportsBulk() {
		return this.input1.supportsBulk() && this.input2.supportsBulk();
	}

	@Override
	public void fillOffsetX(NoiseBatch batch, int seed, float[] output) {
		float[] xCoordinates = batch.acquire();
		float[] zCoordinates = batch.acquire();
		try {
			this.input1.fillX(batch, seed, xCoordinates);
			this.input1.fillZ(batch, seed, zCoordinates);
			this.input2.fillOffsetX(batch.transformed(xCoordinates, zCoordinates), seed, output);
		} finally {
			batch.release(zCoordinates);
			batch.release(xCoordinates);
		}
	}

	@Override
	public void fillOffsetZ(NoiseBatch batch, int seed, float[] output) {
		float[] xCoordinates = batch.acquire();
		float[] zCoordinates = batch.acquire();
		try {
			this.input1.fillX(batch, seed, xCoordinates);
			this.input1.fillZ(batch, seed, zCoordinates);
			this.input2.fillOffsetZ(batch.transformed(xCoordinates, zCoordinates), seed, output);
		} finally {
			batch.release(zCoordinates);
			batch.release(xCoordinates);
		}
	}

	@Override
	public float getRootOffsetX(float x, float z, int seed) {
		float ax = this.input1.getRootX(x, z, seed);
		float ay = this.input1.getRootZ(x, z, seed);
		return this.input2.getRootOffsetX(ax, ay, seed);
	}

	@Override
	public float getRootOffsetZ(float x, float z, int seed) {
		float ax = this.input1.getRootX(x, z, seed);
		float ay = this.input1.getRootZ(x, z, seed);
		return this.input2.getRootOffsetZ(ax, ay, seed);
	}

	@Override
	public Domain mapAll(Visitor visitor) {
		return new CompoundWarp(this.input1.mapAll(visitor), this.input2.mapAll(visitor));
	}

	@Override
	public MapCodec<CompoundWarp> codec() {
		return CODEC;
	}
}
