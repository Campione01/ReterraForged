package raccoonman.reterraforged.world.worldgen.biome.selection;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;

public interface BiomeSelectionParameterList {
	void setBiomeSelectionModifier(@Nullable BiomeSelectionModifier modifier);

	boolean hasActiveBiomeSelection();

	Holder<Biome> modifyBiomeSelection(Holder<Biome> selected, BiomeSelectionTarget target);
}
