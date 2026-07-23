package raccoonman.reterraforged.world.worldgen.noise.module;

import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

record Map(Noise alpha, Noise from, Noise to) implements Noise {
	public static final MapCodec<Map> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Noise.HOLDER_HELPER_CODEC.fieldOf("alpha").forGetter(Map::alpha),
		Noise.HOLDER_HELPER_CODEC.fieldOf("from").forGetter(Map::from),
		Noise.HOLDER_HELPER_CODEC.fieldOf("to").forGetter(Map::to)
	).apply(instance, Map::new));

	@Override
	public float compute(float x, float z, int seed) {
		float alphaMin = this.alpha.minValue();
		float alphaMax = this.alpha.maxValue();
        float value = this.alpha.compute(x, z, seed);
		float alpha = (value - alphaMin) / (alphaMax - alphaMin);
        float min = this.from.compute(x, z, seed);
        float max = this.to.compute(x, z, seed);
        return min + alpha * (max - min);
	}

	@Override
	public boolean supportsBulk() {
		return this.alpha.supportsBulk() && this.from.supportsBulk() && this.to.supportsBulk();
	}

	@Override
	public void fill(NoiseBatch batch, int seed, float[] output) {
		float alphaMin = this.alpha.minValue();
		float alphaRange = this.alpha.maxValue() - alphaMin;
		this.alpha.fill(batch, seed, output);
		if(this.from instanceof Constant fromConstant && this.to instanceof Constant toConstant) {
			float constantFrom = fromConstant.value();
			float constantRange = toConstant.value() - constantFrom;
			for(int index = 0; index < output.length; index++) {
				float alpha = (output[index] - alphaMin) / alphaRange;
				output[index] = constantFrom + alpha * constantRange;
			}
			return;
		}
		float[] fromValues = batch.acquire();
		float[] toValues = batch.acquire();
		try {
			this.from.fill(batch, seed, fromValues);
			this.to.fill(batch, seed, toValues);
			for(int index = 0; index < output.length; index++) {
				float alpha = (output[index] - alphaMin) / alphaRange;
				float min = fromValues[index];
				float max = toValues[index];
				output[index] = min + alpha * (max - min);
			}
		} finally {
			batch.release(toValues);
			batch.release(fromValues);
		}
	}

	@Override
	public float minValue() {
		return this.from.minValue();
	}

	@Override
	public float maxValue() {
		return this.to.maxValue();
	}

	@Override
	public Noise mapAll(Visitor visitor) {
		return visitor.apply(new Map(this.alpha.mapAll(visitor), this.from.mapAll(visitor), this.to.mapAll(visitor)));
	}

	@Override
	public MapCodec<Map> codec() {
		return CODEC;
	}

}
