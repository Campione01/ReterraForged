package raccoonman.reterraforged.mixin;

import java.util.concurrent.Executor;
import java.util.function.Supplier;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.datafixers.DataFixer;

import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.progress.ChunkProgressListener;
import net.minecraft.util.thread.BlockableEventLoop;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LightChunkGetter;
import net.minecraft.world.level.entity.ChunkStatusUpdateListener;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;
import net.minecraft.world.level.storage.DimensionDataStorage;
import net.minecraft.world.level.storage.LevelStorageSource;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionBiomeSource;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionModifier;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionParameterList;

@Mixin(ChunkMap.class)
public class MixinChunkMap {
	@Shadow
    private RandomState randomState;
	
	@Inject(
		at = @At("TAIL"),
		method = "<init>"
	)
	public void ChunkMap(ServerLevel serverLevel, LevelStorageSource.LevelStorageAccess storageAccess, DataFixer dataFixer, StructureTemplateManager templateLoader, Executor executor, BlockableEventLoop<Runnable> eventLoop, LightChunkGetter lightChunkGetter, ChunkGenerator chunkGenerator, ChunkProgressListener chunkProgressListener, ChunkStatusUpdateListener chunkStatusListener, Supplier<DimensionDataStorage> dimensionStorage, int viewDistance, boolean syncChunkWrites, CallbackInfo callback) {
		if((Object) this.randomState instanceof RTFRandomState rtfRandomState) {
			rtfRandomState.initialize(serverLevel);
			this.reterraforged$initializeBiomeSelection(serverLevel, chunkGenerator, rtfRandomState);
		}
	}

	private void reterraforged$initializeBiomeSelection(
		ServerLevel serverLevel,
		ChunkGenerator chunkGenerator,
		RTFRandomState randomState
	) {
		if(!serverLevel.dimension().equals(Level.OVERWORLD)) {
			return;
		}
		BiomeSource source = chunkGenerator.getBiomeSource();
		if(!((Object) source instanceof BiomeSelectionBiomeSource biomeSource)
			|| !((Object) biomeSource.biomeSelectionParameters() instanceof BiomeSelectionParameterList parameters)) {
			return;
		}
		GeneratorContext context = randomState.generatorContext();
		Preset preset = randomState.preset();
		if(context == null || preset == null || !BiomeSelectionModifier.hasActiveUsage(preset.miscellaneous())) {
			parameters.setBiomeSelectionModifier(null);
			return;
		}
		parameters.setBiomeSelectionModifier(BiomeSelectionModifier.create(
			serverLevel.registryAccess(),
			preset,
			context,
			source.possibleBiomes()
		));
	}
}
