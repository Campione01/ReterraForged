package raccoonman.reterraforged.world.worldgen.surface;

import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import org.jetbrains.annotations.Nullable;

public interface StrataBiomeContext {

	void reterraforged$bindStrataBiomes(BiomeManager manager, Registry<Biome> registry);

	@Nullable StrataBiomeExclusions reterraforged$strataExclusions();
}
