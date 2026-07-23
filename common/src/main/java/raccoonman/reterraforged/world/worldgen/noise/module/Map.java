package raccoonman.reterraforged.world.worldgen.noise.module;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

record Map(Noise alpha, Noise from, Noise to, float alphaMin, float alphaRange, boolean constantBounds, float constantFrom, float constantRange) implements Noise {
	public static final MapCodec<Map> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Noise.HOLDER_HELPER_CODEC.fieldOf("alpha").forGetter(Map::alpha),
		Noise.HOLDER_HELPER_CODEC.fieldOf("from").forGetter(Map::from),
		Noise.HOLDER_HELPER_CODEC.fieldOf("to").forGetter(Map::to)
	).apply(instance, Map::new));

	Map(Noise alpha, Noise from, Noise to) {
		this(alpha, from, to, bounds(alpha), constantBounds(from, to));
	}

	private Map(Noise alpha, Noise from, Noise to, Bounds bounds, ConstantBounds constantBounds) {
		this(alpha, from, to, bounds.min, bounds.range, constantBounds.present, constantBounds.from, constantBounds.range);
	}
	
	@Override
	public float compute(float x, float z, int seed) {
        float value = this.alpha.compute(x, z, seed);
		float alpha = (value - this.alphaMin) / this.alphaRange;
		if(this.constantBounds) {
			return this.constantFrom + alpha * this.constantRange;
		}
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
		this.alpha.fill(batch, seed, output);
		if(this.constantBounds) {
			for(int index = 0; index < output.length; index++) {
				float alpha = (output[index] - this.alphaMin) / this.alphaRange;
				output[index] = this.constantFrom + alpha * this.constantRange;
			}
			return;
		}
		float[] fromValues = batch.acquire();
		float[] toValues = batch.acquire();
		try {
			this.from.fill(batch, seed, fromValues);
			this.to.fill(batch, seed, toValues);
			for(int index = 0; index < output.length; index++) {
				float alpha = (output[index] - this.alphaMin) / this.alphaRange;
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

	private static Bounds bounds(Noise alpha) {
		float min = alpha.minValue();
		float max = alpha.maxValue();
		return new Bounds(min, max - min);
	}

	private static ConstantBounds constantBounds(Noise from, Noise to) {
		if(from instanceof Constant fromConstant && to instanceof Constant toConstant) {
			float fromValue = fromConstant.value();
			return new ConstantBounds(true, fromValue, toConstant.value() - fromValue);
		}
		return ConstantBounds.NONE;
	}

	private record Bounds(float min, float range) {
	}

	private record ConstantBounds(boolean present, float from, float range) {
		private static final ConstantBounds NONE = new ConstantBounds(false, 0.0F, 0.0F);
	}
}
