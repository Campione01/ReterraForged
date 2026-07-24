package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionBiomeSource;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionClimateSampler;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionParameterList;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionTarget;

@Mixin(value = MultiNoiseBiomeSource.class, priority = 900)
@Implements(@Interface(iface = BiomeSelectionBiomeSource.class, prefix = "reterraforged$BiomeSelectionBiomeSource$"))
abstract class MixinMultiNoiseBiomeSource {

	@Shadow
	private Climate.ParameterList<Holder<Biome>> parameters() {
		throw new UnsupportedOperationException();
	}

	public Climate.ParameterList<Holder<Biome>> reterraforged$BiomeSelectionBiomeSource$biomeSelectionParameters() {
		return this.parameters();
	}

	@Inject(
		method = "getNoiseBiome(IIILnet/minecraft/world/level/biome/Climate$Sampler;)Lnet/minecraft/core/Holder;",
		at = @At("RETURN"),
		cancellable = true,
		require = 1
	)
	private void reterraforged$modifySelectedBiome(
		int quartX,
		int quartY,
		int quartZ,
		Climate.Sampler sampler,
		CallbackInfoReturnable<Holder<Biome>> callback
	) {
		if(!((Object) this.parameters() instanceof BiomeSelectionParameterList parameterList)
			|| !parameterList.hasActiveBiomeSelection()
			|| !((Object) sampler instanceof BiomeSelectionClimateSampler biomeSelectionSampler)) {
			return;
		}
		BiomeSelectionTarget target = biomeSelectionSampler.getLastBiomeSelectionTarget(quartX, quartY, quartZ);
		if(target == null) {
			return;
		}
		Holder<Biome> selected = callback.getReturnValue();
		Holder<Biome> modified = parameterList.modifyBiomeSelection(selected, target);
		if(modified != selected) {
			callback.setReturnValue(modified);
		}
	}
}
