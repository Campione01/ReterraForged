package raccoonman.reterraforged.world.worldgen.quicknoise;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.util.Mth;
import net.minecraft.world.level.levelgen.DensityFunction;
import raccoonman.reterraforged.world.worldgen.densityfunction.MarkerFunction;

public final class QuickCaveDensity {
	private static final float CHAMBER_BIAS_DISABLED = 1.25F;
	private static final float CHAMBER_BIAS_ENABLED = 0.32F;
	private static final float SPAGHETTI_WIDTH = 0.085F;
	private static final float NOODLE_WIDTH = 0.055F;
	private static final int BOTTOM_PROTECTION_HEIGHT = 24;

	private QuickCaveDensity() {
	}

	public record Marker(DensityFunction terrain, int minY, float entranceProbability, float cheeseDepthOffset, float cheeseProbability, float spaghettiProbability, float noodleProbability) implements MarkerFunction {
		public static final MapCodec<Marker> CODEC = RecordCodecBuilder.mapCodec(instance -> instance.group(
			DensityFunction.HOLDER_HELPER_CODEC.fieldOf("terrain").forGetter(Marker::terrain),
			Codec.INT.fieldOf("minY").forGetter(Marker::minY),
			Codec.FLOAT.fieldOf("entranceProbability").forGetter(Marker::entranceProbability),
			Codec.FLOAT.fieldOf("cheeseDepthOffset").forGetter(Marker::cheeseDepthOffset),
			Codec.FLOAT.fieldOf("cheeseProbability").forGetter(Marker::cheeseProbability),
			Codec.FLOAT.fieldOf("spaghettiProbability").forGetter(Marker::spaghettiProbability),
			Codec.FLOAT.fieldOf("noodleProbability").forGetter(Marker::noodleProbability)
		).apply(instance, Marker::new));

		public DensityFunction seeded(long seed) {
			float cheese = Mth.clamp(this.cheeseProbability, 0.0F, 1.0F);
			float chamberBias = Mth.lerp(cheese, CHAMBER_BIAS_DISABLED, CHAMBER_BIAS_ENABLED);
			float spaghettiWidth = SPAGHETTI_WIDTH * Mth.clamp(this.spaghettiProbability, 0.0F, 1.0F);
			float noodleWidth = NOODLE_WIDTH * Mth.clamp(this.noodleProbability, 0.0F, 1.0F);
			QuickNoiseTileCache cache = new QuickNoiseTileCache(seed, chamberBias, spaghettiWidth, noodleWidth);
			return new Runtime(this.terrain, cache, this.minY, this.entranceProbability, this.cheeseDepthOffset);
		}

		@Override
		public DensityFunction mapAll(Visitor visitor) {
			return visitor.apply(new Marker(this.terrain.mapAll(visitor), this.minY, this.entranceProbability, this.cheeseDepthOffset, this.cheeseProbability, this.spaghettiProbability, this.noodleProbability));
		}

		@Override
		public double minValue() {
			return Math.min(this.terrain.minValue(), -4.0D);
		}

		@Override
		public double maxValue() {
			return this.terrain.maxValue();
		}

		@Override
		public KeyDispatchDataCodec<Marker> codec() {
			return new KeyDispatchDataCodec<>(CODEC);
		}
	}

	private record Runtime(DensityFunction terrain, QuickNoiseTileCache cache, int minY, float entranceProbability, float cheeseDepthOffset) implements MarkerFunction.Mapped {
		@Override
		public double compute(FunctionContext ctx) {
			return this.combine(this.terrain.compute(ctx), this.cache.sample(ctx.blockX(), ctx.blockY(), ctx.blockZ()), ctx.blockY());
		}

		@Override
		public void fillArray(double[] values, ContextProvider contextProvider) {
			this.terrain.fillArray(values, contextProvider);
			for(int i = 0; i < values.length; i++) {
				FunctionContext ctx = contextProvider.forIndex(i);
				values[i] = this.combine(values[i], this.cache.sample(ctx.blockX(), ctx.blockY(), ctx.blockZ()), ctx.blockY());
			}
		}

		@Override
		public DensityFunction mapAll(Visitor visitor) {
			return visitor.apply(new Runtime(this.terrain.mapAll(visitor), this.cache, this.minY, this.entranceProbability, this.cheeseDepthOffset));
		}

		@Override
		public double minValue() {
			return Math.min(this.terrain.minValue(), -4.0D);
		}

		@Override
		public double maxValue() {
			return this.terrain.maxValue();
		}

		private double combine(double terrainDensity, float caveDensity, int blockY) {
			double entrance = Mth.clamp(this.entranceProbability, 0.0F, 1.0F);
			double surfaceProtection = Math.max(0.0D, this.cheeseDepthOffset - terrainDensity) * (1.0D - entrance);
			double bottomAlpha = Mth.clamp((this.minY + BOTTOM_PROTECTION_HEIGHT - blockY) / (double) BOTTOM_PROTECTION_HEIGHT, 0.0D, 1.0D);
			double protectedCaveDensity = caveDensity + surfaceProtection + bottomAlpha;
			return Math.min(terrainDensity, protectedCaveDensity);
		}
	}
}
