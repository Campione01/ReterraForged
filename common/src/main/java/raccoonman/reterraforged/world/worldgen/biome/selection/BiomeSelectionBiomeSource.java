package raccoonman.reterraforged.world.worldgen.biome.selection;

import net.minecraft.core.Holder;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Climate;

public interface BiomeSelectionBiomeSource {
	Climate.ParameterList<Holder<Biome>> biomeSelectionParameters();
}
