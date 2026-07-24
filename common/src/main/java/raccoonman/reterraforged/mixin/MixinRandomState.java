package raccoonman.reterraforged.mixin; 

import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Implements;
import org.spongepowered.asm.mixin.Interface;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.core.HolderGetter;
import net.minecraft.core.HolderLookup.RegistryLookup;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Climate;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunction.NoiseHolder;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.concurrent.ThreadPools;
import raccoonman.reterraforged.config.PerformanceConfig;
import raccoonman.reterraforged.data.worldgen.compat.terrablender.TBNoiseRouterData;
import raccoonman.reterraforged.data.worldgen.preset.settings.CaveSettings.DensityAlgorithm;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.registries.RTFRegistries;
import raccoonman.reterraforged.tags.RTFDensityFunctionTags;
import raccoonman.reterraforged.world.worldgen.GeneratorContext;
import raccoonman.reterraforged.world.worldgen.RTFRandomState;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.NoiseFunction;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;
import raccoonman.reterraforged.world.worldgen.opencl.OpenClManager;
import raccoonman.reterraforged.world.worldgen.quicknoise.QuickCaveDensity;
import raccoonman.reterraforged.world.worldgen.terrablender.TBClimateSampler;
import raccoonman.reterraforged.world.worldgen.terrablender.TBCompat;

@Mixin(RandomState.class)
@Implements(@Interface(iface = RTFRandomState.class, prefix = "reterraforged$RTFRandomState$"))
class MixinRandomState {
	private DensityFunction.Visitor densityFunctionWrapper;
	@Shadow
	@Final
	private Climate.Sampler sampler;
	@Shadow
	@Final
    private SurfaceSystem surfaceSystem;
	
	@Deprecated
	private boolean hasContext;
	@Nullable
	private GeneratorContext generatorContext;
	@Nullable
	private Preset preset;
	
	private long seed;
	private NoiseGeneratorSettings noiseGeneratorSettings;
	private int reterraforged$repairedCellSamplerCacheOnceMarkers;
	private boolean reterraforged$reportedCellSamplerCacheOnceRepair;
	
	@Redirect(
		at = @At(
			value = "INVOKE",
			target = "Lnet/minecraft/world/level/levelgen/NoiseRouter;mapAll(Lnet/minecraft/world/level/levelgen/DensityFunction$Visitor;)Lnet/minecraft/world/level/levelgen/NoiseRouter;"
		),
		method = "<init>",
		require = 1
	)
	private NoiseRouter RandomState(NoiseRouter router, DensityFunction.Visitor visitor, NoiseGeneratorSettings noiseGeneratorSettings, HolderGetter<NormalNoise.NoiseParameters> params, final long seed) {
		this.seed = seed;
		this.noiseGeneratorSettings = noiseGeneratorSettings;
		this.densityFunctionWrapper = new DensityFunction.Visitor() {
			
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof QuickCaveDensity.Marker marker) {
					return marker.seeded(seed);
				}
				if(function instanceof NoiseFunction.Marker marker) {
					return new NoiseFunction(marker.noise(), (int) seed);
				}
				if(function instanceof CellSampler.Marker marker) {
					MixinRandomState.this.hasContext |= true;
					return new CellSampler(() -> {
						GeneratorContext context = MixinRandomState.this.generatorContext;
						return context != null ? context.lookup : null;
					}, marker.field());
				}
				// Older Quickterraforged preset packs persisted this exact holder-backed
				// cache shape around sloped_cheese, where it aliases a mapped CellSampler.
				if(function instanceof DensityFunctions.Marker marker
					&& marker.type() == DensityFunctions.Marker.Type.CacheOnce
					&& marker.wrapped() instanceof DensityFunctions.HolderHolder holder
					&& reterraforged$containsCellSampler(holder.function().value())) {
					MixinRandomState.this.reterraforged$repairedCellSamplerCacheOnceMarkers++;
					return marker.wrapped();
				}
				return visitor.apply(function);
			}

			@Override
			public NoiseHolder visitNoise(NoiseHolder noiseHolder) {
	            return visitor.visitNoise(noiseHolder);
	        }
		};
		NoiseRouter mapped = router.mapAll(this.densityFunctionWrapper);
		this.reterraforged$reportCellSamplerCacheOnceRepair();
		return mapped;
	}

	public void reterraforged$RTFRandomState$initialize(ServerLevel level) {
		RegistryAccess registries = level.registryAccess();

		if(!level.dimension().equals(Level.OVERWORLD)) {
			this.clearGeneratorContext();
			return;
		}

		// 只在主世界应用RTF地形
		RegistryLookup<Preset> presets = registries.lookupOrThrow(RTFRegistries.PRESET);
		RegistryLookup<Noise> noises = registries.lookupOrThrow(RTFRegistries.NOISE);
		RegistryLookup<DensityFunction> functions = registries.lookupOrThrow(Registries.DENSITY_FUNCTION);

		functions.get(RTFDensityFunctionTags.ADDITIONAL_NOISE_ROUTER_FUNCTIONS).ifPresent((set) -> {
			set.forEach((function) -> function.value().mapAll(this.densityFunctionWrapper));
		});
		
		if((Object) this.sampler instanceof TBClimateSampler tbClimateSampler && TBCompat.isEnabled()) {
			functions.get(TBNoiseRouterData.UNIQUENESS).ifPresent((uniqueness) -> {
				tbClimateSampler.setUniqueness(uniqueness.value().mapAll(this.densityFunctionWrapper));
			});
		}
		this.reterraforged$reportCellSamplerCacheOnceRepair();
		
		presets.get(Preset.KEY).ifPresentOrElse((presetHolder) -> {
			this.preset = presetHolder.value();
			if(OpenClManager.isEnabledByConfig() && this.preset.caves().densityAlgorithm != DensityAlgorithm.QUICK_V1) {
				RTFCommon.LOGGER.info(
					"RTF OpenCL final-density acceleration is disabled for {} until full-world CPU parity is established; using the CPU cave backend",
					this.preset.caves().densityAlgorithm
				);
			}

			if(this.hasContext) {
				PerformanceConfig config = PerformanceConfig.read(PerformanceConfig.DEFAULT_FILE_PATH)
					.resultOrPartial(RTFCommon.LOGGER::error)
					.orElseGet(PerformanceConfig::makeDefault);
				this.generatorContext = GeneratorContext.makeCached(this.preset, noises, (int) this.seed, config.tileSize(), config.batchCount(), ThreadPools.availableProcessors() > 4);
			}
		}, () -> {
			if(this.hasContext) {
//				throw new IllegalStateException("Missing preset!");
			}
		});
	}
	
	@Nullable
	public Preset reterraforged$RTFRandomState$preset() {
		return this.preset;
	}
	
	@Nullable
	public GeneratorContext reterraforged$RTFRandomState$generatorContext() {
		return this.generatorContext;
	}

	@Nullable
	public DensityFunction reterraforged$RTFRandomState$wrap(DensityFunction function) {
		DensityFunction mapped = function.mapAll(this.densityFunctionWrapper);
		this.reterraforged$reportCellSamplerCacheOnceRepair();
		return mapped;
	}

	public Noise reterraforged$RTFRandomState$seed(Noise noise) {
		return Noises.shiftSeed(noise, (int) this.seed);
	}

	private static boolean reterraforged$containsCellSampler(DensityFunction root) {
		if(root instanceof CellSampler) {
			return true;
		}
		boolean[] found = { false };
		root.mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof CellSampler) {
					found[0] = true;
				}
				return function;
			}
		});
		return found[0];
	}

	private void reterraforged$reportCellSamplerCacheOnceRepair() {
		if(this.reterraforged$reportedCellSamplerCacheOnceRepair || this.reterraforged$repairedCellSamplerCacheOnceMarkers == 0) {
			return;
		}
		this.reterraforged$reportedCellSamplerCacheOnceRepair = true;
		RTFCommon.LOGGER.warn(
			"Repaired {} persisted cache_once density marker(s) containing RTF CellSampler nodes for this RandomState",
			this.reterraforged$repairedCellSamplerCacheOnceMarkers
		);
	}

	private void clearGeneratorContext() {
		if(this.generatorContext != null) {
			this.generatorContext.close();
		}
		this.hasContext = false;
		this.generatorContext = null;
		this.preset = null;
	}
}
