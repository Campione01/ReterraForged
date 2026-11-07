package raccoonman.reterraforged.data.worldgen.preset;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.OptionalLong;

import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.world.level.dimension.BuiltinDimensionTypes;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.Preset;
import raccoonman.reterraforged.data.worldgen.preset.settings.WorldSettings;

public final class PresetDimensionTypes {
	private static final List<ResourceKey<DimensionType>> SHARED_OVERWORLD_TYPES = List.of(
		ResourceKey.create(Registries.DIMENSION_TYPE, ResourceLocation.fromNamespaceAndPath("confluence_dimension_patch", "otherworld")),
		ResourceKey.create(Registries.DIMENSION_TYPE, ResourceLocation.fromNamespaceAndPath("rotp_d4c", "parallel_world_type"))
	);
	
	public static void bootstrap(Preset preset, BootstrapContext<DimensionType> ctx) {
		bootstrap(preset, ctx, RegistryAccess.EMPTY);
	}

	public static void bootstrap(Preset preset, BootstrapContext<DimensionType> ctx, RegistryAccess registries) {
		WorldSettings worldSettings = preset.world();
		WorldSettings.Properties properties = worldSettings.properties;
		int worldHeight = properties.worldHeight;
		int worldDepth = properties.worldDepth;
		int totalHeight = worldDepth + worldHeight;
		
        ctx.register(BuiltinDimensionTypes.OVERWORLD, new DimensionType(OptionalLong.empty(), true, false, false, true, 1.0, true, false, -worldDepth, totalHeight, totalHeight, BlockTags.INFINIBURN_OVERWORLD, BuiltinDimensionTypes.OVERWORLD_EFFECTS, 0.0f, new DimensionType.MonsterSettings(false, true, UniformInt.of(0, 7), 0)));

		registries.registry(Registries.DIMENSION_TYPE).ifPresent(types -> {
			for (ResourceKey<DimensionType> key : sharedOverworldTypes(registries)) {
				DimensionType original = types.get(key);
				if (original != null) {
					ctx.register(key, new DimensionType(original.fixedTime(), original.hasSkyLight(), original.hasCeiling(),
						original.ultraWarm(), original.natural(), original.coordinateScale(), original.bedWorks(),
						original.respawnAnchorWorks(), -worldDepth, totalHeight, totalHeight, original.infiniburn(),
						original.effectsLocation(), original.ambientLight(), original.monsterSettings()));
				}
			}
		});
	}

	private static Collection<ResourceKey<DimensionType>> sharedOverworldTypes(RegistryAccess registries) {
		var stems = registries.registry(Registries.LEVEL_STEM);
		if (stems.isEmpty() || stems.get().entrySet().isEmpty()) {
			// World creation loads the type registry before the separate dimension stem registry.
			return SHARED_OVERWORLD_TYPES;
		}

		var matching = new LinkedHashSet<ResourceKey<DimensionType>>();
		var other = new HashSet<ResourceKey<DimensionType>>();
		for (var entry : stems.get().entrySet()) {
			var stem = entry.getValue();
			var type = stem.type().unwrapKey().orElse(null);
			if (type == null || type.equals(BuiltinDimensionTypes.OVERWORLD)) continue;
			if (!entry.getKey().equals(LevelStem.OVERWORLD)
					&& stem.generator() instanceof NoiseBasedChunkGenerator generator
					&& generator.generatorSettings().is(NoiseGeneratorSettings.OVERWORLD)) {
				matching.add(type);
			} else {
				other.add(type);
			}
		}
		// A type-level patch must not also resize a known nonmatching stem.
		matching.removeAll(other);
		return matching;
	}
}
