package raccoonman.reterraforged.data.worldgen.preset;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mojang.serialization.JsonOps;

import net.minecraft.SharedConstants;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.data.registries.VanillaRegistries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.structure.BuiltinStructureSets;
import net.minecraft.world.level.levelgen.structure.StructureSet;
import net.minecraft.world.level.levelgen.structure.placement.RandomSpreadStructurePlacement;
import raccoonman.reterraforged.data.worldgen.preset.settings.StructureSettings;
import raccoonman.reterraforged.data.worldgen.preset.settings.StructureSettings.StructureSetEntry;

class PresetStructureSetDataTest {
	private static HolderLookup.Provider vanilla;
	private static HolderLookup.RegistryLookup<StructureSet> structureSets;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		vanilla = VanillaRegistries.createLookup();
		structureSets = vanilla.lookupOrThrow(Registries.STRUCTURE_SET);
	}

	@Test
	void emptyAndSourceIdenticalSettingsProduceNoPatches() {
		assertTrue(PresetStructureSetData.buildPatches(new StructureSettings(), structureSets).isEmpty());

		StructureSet original = get(BuiltinStructureSets.PILLAGER_OUTPOSTS);
		RandomSpreadStructurePlacement placement = randomSpread(original);
		StructureSettings settings = settings(
			BuiltinStructureSets.PILLAGER_OUTPOSTS,
			new StructureSetEntry(placement.spacing(), placement.separation(), placement.salt(), false)
		);

		assertTrue(PresetStructureSetData.buildPatches(settings, structureSets).isEmpty());
	}

	@Test
	void changedValuesPreserveEveryOtherRandomSpreadField() {
		StructureSet original = get(BuiltinStructureSets.PILLAGER_OUTPOSTS);
		RandomSpreadStructurePlacement placement = randomSpread(original);
		StructureSettings settings = settings(
			BuiltinStructureSets.PILLAGER_OUTPOSTS,
			new StructureSetEntry(placement.spacing() + 5, placement.separation() + 1, placement.salt() + 1, false)
		);

		Map<?, StructureSet> patches = PresetStructureSetData.buildPatches(settings, structureSets);
		assertEquals(1, patches.size());
		StructureSet replacement = patches.get(BuiltinStructureSets.PILLAGER_OUTPOSTS);
		RandomSpreadStructurePlacement replacementPlacement = randomSpread(replacement);

		assertSame(original.structures(), replacement.structures());
		assertEquals(placement.spacing() + 5, replacementPlacement.spacing());
		assertEquals(placement.separation() + 1, replacementPlacement.separation());
		assertEquals(placement.salt() + 1, replacementPlacement.salt());
		assertEquals(placement.locateOffset(), replacementPlacement.locateOffset());
		assertEquals(placement.frequencyReductionMethod(), replacementPlacement.frequencyReductionMethod());
		assertEquals(placement.frequency(), replacementPlacement.frequency());
		assertEquals(placement.exclusionZone(), replacementPlacement.exclusionZone());
		assertEquals(placement.spreadType(), replacementPlacement.spreadType());
	}

	@Test
	void disabledSetHasNoStructureSelectionsAndKeepsItsPlacement() {
		StructureSet original = get(BuiltinStructureSets.PILLAGER_OUTPOSTS);
		RandomSpreadStructurePlacement placement = randomSpread(original);
		StructureSettings settings = settings(
			BuiltinStructureSets.PILLAGER_OUTPOSTS,
			new StructureSetEntry(placement.spacing(), placement.separation(), placement.salt(), true)
		);

		StructureSet replacement = PresetStructureSetData.buildPatches(settings, structureSets)
			.get(BuiltinStructureSets.PILLAGER_OUTPOSTS);

		assertTrue(replacement.structures().isEmpty());
		assertSame(placement, replacement.placement());

		var encoded = StructureSet.DIRECT_CODEC
			.encodeStart(vanilla.createSerializationContext(JsonOps.INSTANCE), replacement)
			.getOrThrow();
		assertTrue(encoded.getAsJsonObject().getAsJsonArray("structures").isEmpty());
	}

	@Test
	void unknownAndNonRandomSpreadSettingsAreLeftUnpatched() {
		StructureSettings settings = new StructureSettings(Map.of(
			BuiltinStructureSets.STRONGHOLDS, new StructureSetEntry(32, 8, 1, true),
			ResourceKey.create(
				Registries.STRUCTURE_SET,
				ResourceLocation.fromNamespaceAndPath("example", "unknown")
			),
			new StructureSetEntry(32, 8, 1, true)
		));

		assertTrue(PresetStructureSetData.buildPatches(settings, structureSets).isEmpty());
	}

	private static StructureSettings settings(
		ResourceKey<StructureSet> key,
		StructureSetEntry entry
	) {
		return new StructureSettings(Map.of(key, entry));
	}

	private static StructureSet get(ResourceKey<StructureSet> key) {
		return structureSets.getOrThrow(key).value();
	}

	private static RandomSpreadStructurePlacement randomSpread(StructureSet set) {
		return (RandomSpreadStructurePlacement)set.placement();
	}
}
