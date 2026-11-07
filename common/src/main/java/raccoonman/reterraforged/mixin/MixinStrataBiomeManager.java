package raccoonman.reterraforged.mixin;

import net.minecraft.core.Registry;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeManager;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import raccoonman.reterraforged.world.worldgen.surface.StrataBiomeContext;
import raccoonman.reterraforged.world.worldgen.surface.StrataModeReporter;

@Mixin(SurfaceSystem.class)
class MixinStrataBiomeManager {
	@ModifyVariable(method = "buildSurface", at = @At("STORE"), ordinal = 0, require = 1, allow = 1)
	private SurfaceRules.Context reterraforged$bindStrataBiomes(SurfaceRules.Context context, RandomState state,
			BiomeManager manager, Registry<Biome> biomes) {
		((StrataBiomeContext) (Object) context).reterraforged$bindStrataBiomes(manager, biomes);
		StrataModeReporter.observe(state, manager);
		return context;
	}
}
