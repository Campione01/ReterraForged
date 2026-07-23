package raccoonman.reterraforged.world.worldgen.noise.module;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.function.Interpolation;

record Blend(Noise alpha, Noise lower, Noise upper, float mid, float range, Interpolation interpolation,
		float blendLower, float blendUpper, float blendRange) implements Noise {
	public static final MapCodec<Blend> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
		Noise.HOLDER_HELPER_CODEC.fieldOf("alpha").forGetter(Blend::alpha),
		Noise.HOLDER_HELPER_CODEC.fieldOf("lower").forGetter(Blend::lower),
		Noise.HOLDER_HELPER_CODEC.fieldOf("upper").forGetter(Blend::upper),
		Codec.FLOAT.fieldOf("mid").forGetter(Blend::mid),
		Codec.FLOAT.fieldOf("range").forGetter(Blend::range),
		Interpolation.CODEC.fieldOf("interpolation").forGetter(Blend::interpolation)
	).apply(instance, Blend::new));

	Blend(Noise alpha, Noise lower, Noise upper, float mid, float range, Interpolation interpolation) {
		this(alpha, lower, upper, mid, range, interpolation, bounds(alpha, mid, range));
	}

	private Blend(Noise alpha, Noise lower, Noise upper, float mid, float range, Interpolation interpolation, Bounds bounds) {
		this(alpha, lower, upper, mid, range, interpolation, bounds.lower, bounds.upper, bounds.range);
	}
	
	@Override
	public float minValue() {
		return Math.min(this.lower.minValue(), this.upper.minValue());
	}

	@Override
	public float maxValue() {
		return Math.max(this.lower.maxValue(), this.upper.maxValue());
	}
	
	@Override
	public float compute(float x, float z, int seed) {
		float alpha = this.alpha.compute(x, z, seed);
        if (alpha < this.blendLower) {
            return this.lower.compute(x, z, seed);
        }
        if (alpha > this.blendUpper) {
            return this.upper.compute(x, z, seed);
        }
        return NoiseUtil.lerp(this.lower.compute(x, z, seed), this.upper.compute(x, z, seed), this.interpolation.apply((alpha - this.blendLower) / this.blendRange));
	}

	@Override
	public Noise mapAll(Visitor visitor) {
		return visitor.apply(new Blend(this.alpha.mapAll(visitor), this.lower.mapAll(visitor), this.upper.mapAll(visitor), this.mid, this.range, this.interpolation));
	}

	@Override
	public MapCodec<Blend> codec() {
		return CODEC;
	}

	private static Bounds bounds(Noise alpha, float position, float range) {
		float alphaMin = alpha.minValue();
		float alphaMax = alpha.maxValue();
		float mid = alphaMin + (alphaMax - alphaMin) * position;
		float lower = Math.max(alphaMin, mid - range / 2.0F);
		float upper = Math.min(alphaMax, mid + range / 2.0F);
		return new Bounds(lower, upper, upper - lower);
	}

	private record Bounds(float lower, float upper, float range) {
	}
}
