package raccoonman.reterraforged.data.worldgen.preset;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.core.HolderLookup;
import net.minecraft.data.worldgen.BootstrapContext;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import raccoonman.reterraforged.data.worldgen.preset.settings.StructureSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.StructureSettings.StructureSetEntry;

public final class PresetStructureSetData {
	private static final int MAX_SPACING = 4096;

	private PresetStructureSetData() {
	}

	public static Map<ResourceKey<StructureSet>, StructureSet> buildPatches(
		StructureSettings settings,
		HolderLookup.RegistryLookup<StructureSet> source
	) {
		Map<ResourceKey<StructureSet>, StructureSet> patches = new LinkedHashMap<>();
		settings.entries.entrySet().stream()
			.sorted(Map.Entry.comparingByKey((left, right) -> left.location().compareTo(right.location())))
			.forEach(entry -> source.get(entry.getKey()).ifPresent(holder -> {
				StructureSet original = holder.value();
				StructureSet replacement = patch(original, entry.getValue());
				if(replacement != original) {
					patches.put(entry.getKey(), replacement);
				}
			}));
		return patches;
	}

	public static void bootstrap(Map<ResourceKey<StructureSet>, StructureSet> patches, BootstrapContext<StructureSet> ctx) {
		patches.forEach(ctx::register);
	}

	static StructureSet patch(StructureSet original, StructureSetEntry settings) {
		if(!(original.placement() instanceof RandomSpreadStructurePlacement placement) || !isValid(settings)) {
			return original;
		}

		boolean placementChanged = settings.spacing != placement.spacing()
			|| settings.separation != placement.separation()
			|| settings.salt != placement.salt();
		boolean structuresChanged = settings.disabled && !original.structures().isEmpty();
		if(!placementChanged && !structuresChanged) {
			return original;
		}

		RandomSpreadStructurePlacement replacementPlacement = placement;
		if(placementChanged) {
			replacementPlacement = new RandomSpreadStructurePlacement(
				placement.locateOffset(),
				placement.frequencyReductionMethod(),
				placement.frequency(),
				settings.salt,
				placement.exclusionZone(),
				settings.spacing,
				settings.separation,
				placement.spreadType()
			);
		}
		return new StructureSet(settings.disabled ? List.of() : original.structures(), replacementPlacement);
	}

	private static boolean isValid(StructureSetEntry settings) {
		return settings.spacing > settings.separation
			&& settings.spacing <= MAX_SPACING
			&& settings.separation >= 0
			&& settings.salt >= 0;
	}
}
