package raccoonman.reterraforged.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.ServerLevelData;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.biome.RTFClimateSampler;
import raccoonman.reterraforged.world.worldgen.noise.module.QuickNoiseRuntime;
import raccoonman.reterraforged.world.worldgen.opencl.OpenClManager;

@Mixin(MinecraftServer.class)
class MixinMinecraftServer {
	private static final String QUICK_NOISE_VERIFY_PROPERTY = "reterraforged.quickNoise.verifyLegacy";

	@Inject(at = @At("HEAD"), method = "stopServer")
	private void reportQuickNoiseParity(CallbackInfo callback) {
		if(!Boolean.getBoolean(QUICK_NOISE_VERIFY_PROPERTY)) {
			return;
		}
		MinecraftServer server = (MinecraftServer) (Object) this;
		ServerLevel overworld = server.getLevel(Level.OVERWORLD);
		if(overworld == null || !((Object) overworld.getChunkSource().randomState() instanceof RTFRandomState randomState)) {
			RTFCommon.LOGGER.warn("RTF QUICK_V2 server parity summary unavailable: missing overworld random state");
			return;
		}
		GeneratorContext context = randomState.generatorContext();
		if(context == null || context.noiseEngine == null) {
			RTFCommon.LOGGER.info("RTF QUICK_V2 server parity summary: inactive LEGACY terrain engine");
			return;
		}
		QuickNoiseRuntime.Engine engine = context.noiseEngine;
		var mismatches = engine.semanticMismatches();
		if(mismatches.isEmpty()) {
			RTFCommon.LOGGER.info("RTF QUICK_V2 server parity summary: exact, compiledGraphs={}, fallbackGraphs={}, mismatches=0", engine.compiledGraphCount(), engine.fallbackGraphCount());
		} else {
			RTFCommon.LOGGER.error("RTF QUICK_V2 server parity summary: FAILED, compiledGraphs={}, fallbackGraphs={}, mismatches={}, first={}", engine.compiledGraphCount(), engine.fallbackGraphCount(), mismatches.size(), mismatches.getFirst());
		}
	}

	@Inject(at = @At("TAIL"), method = "stopServer")
	private void closeOpenCl(CallbackInfo callback) {
		OpenClManager.close();
	}

	@Inject(
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/biome/Climate$Sampler;findSpawnPosition()Lnet/minecraft/core/BlockPos;"
		),
		method = "setInitialSpawn"
	)
    private static void findSpawnPosition(ServerLevel serverLevel, ServerLevelData serverLevelData, boolean bl, boolean bl2, CallbackInfo callback) {
		if(!serverLevel.dimension().equals(Level.OVERWORLD)) {
			return;
		}
		RandomState randomState = serverLevel.getChunkSource().randomState();
		Climate.Sampler sampler = randomState.sampler();
		serverLevel.registryAccess().lookup(RTFRegistries.PRESET).flatMap((registry) -> {
			return registry.get(Preset.KEY);
		}).ifPresent((preset) -> {
			if((Object) randomState instanceof RTFRandomState rtfRandomState && rtfRandomState.generatorContext() != null && (Object) sampler instanceof RTFClimateSampler rtfClimateSampler) {
				BlockPos searchCenter = preset.value().world().properties.spawnType.getSearchCenter(rtfRandomState.generatorContext());
				rtfClimateSampler.setSpawnSearchCenter(searchCenter);
			}
		});
    }
}
