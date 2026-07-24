package raccoonman.reterraforged.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.Climate.TargetPoint;
import net.minecraft.world.level.levelgen.DensityFunction;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionClimateSampler;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionSampler;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionTarget;

@Mixin(Climate.Sampler.class)
@Implements({
	@Interface(iface = RTFClimateSampler.class, prefix = "reterraforged$RTFClimateSampler$"),
	@Interface(iface = BiomeSelectionClimateSampler.class, prefix = "reterraforged$BiomeSelectionClimateSampler$")
})
class MixinClimateSampler {
	private BlockPos spawnSearchCenter = BlockPos.ZERO;
	@Nullable
	private volatile BiomeSelectionSampler biomeSelectionSampler;
	@Unique
	private final ThreadLocal<SampledBiomeSelectionTarget> reterraforged$lastBiomeSelectionTarget = new ThreadLocal<>();

	@Inject(
		at = @At("RETURN"),
		method = "sample",
		locals = LocalCapture.CAPTURE_FAILHARD
	)
	private void reterraforged$captureBiomeSelectionTarget(
		int quartX,
		int quartY,
		int quartZ,
		CallbackInfoReturnable<TargetPoint> callback,
		int blockX,
		int blockY,
		int blockZ,
		DensityFunction.SinglePointContext context
	) {
		BiomeSelectionSampler sampler = this.biomeSelectionSampler;
		if(sampler == null) {
			this.reterraforged$lastBiomeSelectionTarget.remove();
			return;
		}
		TargetPoint targetPoint = callback.getReturnValue();
		float temperature = Climate.unquantizeCoord(targetPoint.temperature());
		BiomeSelectionTarget target = sampler.sample(context, temperature);
		this.reterraforged$lastBiomeSelectionTarget.set(new SampledBiomeSelectionTarget(quartX, quartY, quartZ, target));
	}
	
	public void reterraforged$RTFClimateSampler$setSpawnSearchCenter(BlockPos spawnSearchCenter) {
		this.spawnSearchCenter = spawnSearchCenter;
	}
	
	public BlockPos reterraforged$RTFClimateSampler$getSpawnSearchCenter() {
		return this.spawnSearchCenter;
	}

	public void reterraforged$BiomeSelectionClimateSampler$setBiomeSelectionSampler(@Nullable BiomeSelectionSampler sampler) {
		this.biomeSelectionSampler = sampler;
		this.reterraforged$lastBiomeSelectionTarget.remove();
	}

	@Nullable
	public BiomeSelectionSampler reterraforged$BiomeSelectionClimateSampler$getBiomeSelectionSampler() {
		return this.biomeSelectionSampler;
	}

	@Nullable
	public BiomeSelectionTarget reterraforged$BiomeSelectionClimateSampler$getLastBiomeSelectionTarget(int quartX, int quartY, int quartZ) {
		if(this.biomeSelectionSampler == null) {
			return null;
		}
		SampledBiomeSelectionTarget sampled = this.reterraforged$lastBiomeSelectionTarget.get();
		if(sampled == null || sampled.quartX() != quartX || sampled.quartY() != quartY || sampled.quartZ() != quartZ) {
			return null;
		}
		return sampled.target();
	}

	@Unique
	private record SampledBiomeSelectionTarget(int quartX, int quartY, int quartZ, BiomeSelectionTarget target) {
	}
}
