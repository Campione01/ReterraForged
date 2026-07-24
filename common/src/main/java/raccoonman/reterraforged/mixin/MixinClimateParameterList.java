package raccoonman.reterraforged.mixin;

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionModifier;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionParameterList;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionTarget;

@Mixin(Climate.ParameterList.class)
@Implements(@Interface(iface = BiomeSelectionParameterList.class, prefix = "reterraforged$BiomeSelectionParameterList$"))
class MixinClimateParameterList {
	@Unique
	@Nullable
	private volatile BiomeSelectionModifier reterraforged$biomeSelectionModifier;

	public void reterraforged$BiomeSelectionParameterList$setBiomeSelectionModifier(@Nullable BiomeSelectionModifier modifier) {
		this.reterraforged$biomeSelectionModifier = modifier;
	}

	public boolean reterraforged$BiomeSelectionParameterList$hasActiveBiomeSelection() {
		BiomeSelectionModifier modifier = this.reterraforged$biomeSelectionModifier;
		return modifier != null && modifier.isActive();
	}

	public Holder<Biome> reterraforged$BiomeSelectionParameterList$modifyBiomeSelection(
		Holder<Biome> selected,
		BiomeSelectionTarget target
	) {
		BiomeSelectionModifier modifier = this.reterraforged$biomeSelectionModifier;
		return modifier != null ? modifier.modify(selected, target) : selected;
	}
}
