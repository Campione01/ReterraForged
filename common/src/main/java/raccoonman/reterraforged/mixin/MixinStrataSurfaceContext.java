package raccoonman.reterraforged.mixin;

import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.SurfaceRules;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import raccoonman.reterraforged.world.worldgen.surface.StrataBiomeContext;
import raccoonman.reterraforged.world.worldgen.surface.StrataBiomeExclusions;

@Mixin(SurfaceRules.Context.class)
class MixinStrataSurfaceContext implements StrataBiomeContext {
	@Unique private BiomeManager reterraforged$strataManager;
	@Unique private Registry<Biome> reterraforged$strataRegistry;
	@Unique private StrataBiomeExclusions reterraforged$strataFilter;
	@Unique private boolean reterraforged$strataFilterReady;

	@Override
	public void reterraforged$bindStrataBiomes(BiomeManager manager, Registry<Biome> registry) {
		this.reterraforged$strataManager = manager;
		this.reterraforged$strataRegistry = registry;
	}

	@Override
	public @Nullable StrataBiomeExclusions reterraforged$strataExclusions() {
		if (!this.reterraforged$strataFilterReady) {
			var manager = this.reterraforged$strataManager;
			if (manager == null || !StrataBiomeExclusions.hasVanillaBounds(manager)) {
				// Synthetic contexts can use holders from a different registry than the context constructor receives.
				this.reterraforged$strataFilter = StrataBiomeExclusions.EXACT;
			} else if (this.reterraforged$strataRegistry.getTag(StrataBiomeExclusions.TAG).filter(tag -> tag.size() > 0).isPresent()) {
				this.reterraforged$strataFilter = StrataBiomeExclusions.forChunk(manager, ((SurfaceRules.Context) (Object) this).chunk);
			}
			this.reterraforged$strataFilterReady = true;
		}
		return this.reterraforged$strataFilter;
	}
}
