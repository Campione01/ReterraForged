package raccoonman.reterraforged.world.worldgen.biome.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.biome.BiomeSpecialEffects;
import net.minecraft.world.level.biome.MobSpawnSettings;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionModifier.Candidate;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionModifier.CandidateKind;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionModifier.CandidatePool;
import raccoonman.reterraforged.world.worldgen.biome.selection.BiomeSelectionModifier.MountainBoundary;

class BiomeSelectionModifierTest {
	private static final MountainBoundary ALWAYS_MOUNTAIN = new MountainBoundary(0.5F, 0.1F, (x, z) -> 0.0F);

	private static Holder<Biome> base;
	private static Holder<Biome> mountain;
	private static Holder<Biome> volcano;
	private static CandidatePool<Holder<Biome>> mountains;
	private static CandidatePool<Holder<Biome>> volcanoes;

	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
		base = biome(0.5F);
		mountain = biome(0.5F);
		volcano = biome(0.5F);
		mountains = pool("test:mountain", mountain, CandidateKind.MOUNTAIN);
		volcanoes = pool("test:volcano", volcano, CandidateKind.VOLCANO);
	}

	@Test
	void initialReleaseDefaultsAreAnExactCompatibilityBypass() {
		BiomeSelectionModifier modifier = modifier(0.4F, 0.4F);

		assertSame(base, modifier.modify(base, mountain(0.0F, 1.0F)));
		assertSame(base, modifier.modify(base, volcano(0.0F)));
		assertFalse(BiomeSelectionModifier.isActive(0.4F));
		assertTrue(BiomeSelectionModifier.isActive(Math.nextUp(0.4F)));
	}

	@Test
	void zeroAndNonFiniteValuesDisableOverrides() {
		assertFalse(BiomeSelectionModifier.isActive(0.0F));
		assertFalse(BiomeSelectionModifier.isActive(-1.0F));
		assertFalse(BiomeSelectionModifier.isActive(Float.NaN));
		assertFalse(BiomeSelectionModifier.isActive(Float.POSITIVE_INFINITY));

		BiomeSelectionModifier modifier = modifier(0.0F, 0.0F);
		assertSame(base, modifier.modify(base, mountain(0.0F, 1.0F)));
		assertSame(base, modifier.modify(base, volcano(0.0F)));
	}

	@Test
	void strictUsageThresholdDoesNotIncludeTheUpperEndpoint() {
		BiomeSelectionModifier modifier = modifier(1.0F, 1.0F);

		assertSame(mountain, modifier.modify(base, mountain(0.999F, 1.0F)));
		assertSame(base, modifier.modify(base, mountain(1.0F, 1.0F)));
		assertSame(volcano, modifier.modify(base, target(false, true, 0.0F, 0.999F, 0.5F, 0.0F)));
		assertSame(base, modifier.modify(base, target(false, true, 0.0F, 1.0F, 0.5F, 0.0F)));
		assertTrue(BiomeSelectionModifier.passesThreshold(0.999F, 1.0F));
		assertFalse(BiomeSelectionModifier.passesThreshold(1.0F, 1.0F));
	}

	@Test
	void mountainBoundaryMatchesTheLegacyGroundAndTransitionContract() {
		MountainBoundary boundary = new MountainBoundary(0.5F, 0.1F, (x, z) -> x == 7.0F ? 0.02F : 0.0F);
		BiomeSelectionModifier modifier = modifier(1.0F, 0.0F, boundary, mountains, volcanoes);

		assertSame(mountain, modifier.modify(base, target(true, false, 0.0F, 0.0F, 0.5F, 0.51F)));
		assertSame(base, modifier.modify(base, target(true, false, 0.0F, 0.0F, 0.5F, 0.39F)));
		assertSame(base, modifier.modify(base, target(true, false, 0.0F, 0.0F, 0.5F, 0.5F)));
		assertSame(mountain, modifier.modify(base, target(true, false, 0.0F, 0.0F, 0.5F, 0.49F, 7, 11)));
	}

	@Test
	void mountainRunsBeforeVolcanoAndExitEarlyMatchesLegacyModifiers() {
		BiomeSelectionModifier modifier = modifier(0.5F, 1.0F);

		assertSame(mountain, modifier.modify(base, target(true, true, 0.0F, 0.0F, 0.5F, 1.0F)));
		assertSame(base, modifier.modify(base, target(true, true, 0.0F, 0.0F, 0.5F, 0.0F)));
		assertSame(volcano, modifier.modify(base, target(true, true, 0.5F, 0.0F, 0.5F, 1.0F)));

		BiomeSelectionModifier missingMountain = modifier(
			0.5F,
			1.0F,
			ALWAYS_MOUNTAIN,
			new CandidatePool<>(List.of(), CandidateKind.MOUNTAIN),
			volcanoes
		);
		assertSame(base, missingMountain.modify(base, target(true, true, 0.0F, 0.0F, 0.5F, 1.0F)));
	}

	@Test
	void activeUsageNeedsAnEligibleCandidateToChangeTheBiome() {
		BiomeSelectionModifier modifier = modifier(
			0.0F,
			1.0F,
			ALWAYS_MOUNTAIN,
			mountains,
			new CandidatePool<>(List.of(), CandidateKind.VOLCANO)
		);

		assertTrue(modifier.isActive());
		assertSame(base, modifier.modify(base, volcano(0.0F)));
	}

	@Test
	void candidateOutsideTheBiomeSourcesPossibleSetIsNeverReturned() {
		BiomeSelectionModifier modifier = new BiomeSelectionModifier(
			1.0F,
			0.0F,
			ALWAYS_MOUNTAIN,
			mountains,
			volcanoes,
			Set.of(base, volcano)
		);

		assertSame(base, modifier.modify(base, mountain(0.0F, 1.0F)));
	}

	@Test
	void mountainCandidatesAndTargetsUseTheOriginalTemperatureEdges() {
		CandidatePool<String> candidates = new CandidatePool<>(List.of(
			candidate("test:cold", 0.25F, "cold"),
			candidate("test:medium", 0.5F, "medium"),
			candidate("test:warm", 0.75F, "warm")
		), CandidateKind.MOUNTAIN);

		assertEquals("cold", candidates.select(0.249F, 0.5F));
		assertEquals("medium", candidates.select(0.25F, 0.5F));
		assertEquals("medium", candidates.select(0.75F, 0.5F));
		assertEquals("warm", candidates.select(0.751F, 0.5F));
	}

	@Test
	void volcanoCandidatesUseTemperatureThenLegacyNameFallback() {
		CandidatePool<String> candidates = new CandidatePool<>(List.of(
			candidate("test:neutral", 0.5F, "medium"),
			candidate("test:frozen_caldera", 0.5F, "cold-name"),
			candidate("test:desert_caldera", 0.5F, "warm-name"),
			candidate("test:cold-by-temperature", 0.3F, "cold-temperature"),
			candidate("test:warm-by-temperature", 0.81F, "warm-temperature")
		), CandidateKind.VOLCANO);

		assertTrue(List.of("cold-name", "cold-temperature").contains(candidates.select(0.0F, 0.0F)));
		assertEquals("medium", candidates.select(0.5F, 0.5F));
		assertTrue(List.of("warm-name", "warm-temperature").contains(candidates.select(1.0F, 0.0F)));
	}

	@Test
	void candidateSelectionIsRegistryIdSortedAndWeightExpanded() {
		CandidatePool<String> first = new CandidatePool<>(List.of(
			candidate("test:z", 0.5F, 1, "z"),
			candidate("test:a", 0.5F, 1, "a"),
			candidate("test:m", 0.5F, 2, "m")
		), CandidateKind.MOUNTAIN);
		CandidatePool<String> reversed = new CandidatePool<>(List.of(
			candidate("test:m", 0.5F, 2, "m"),
			candidate("test:a", 0.5F, 1, "a"),
			candidate("test:z", 0.5F, 1, "z")
		), CandidateKind.MOUNTAIN);

		assertEquals("a", first.select(0.5F, 0.0F));
		assertEquals("m", first.select(0.5F, 0.34F));
		assertEquals("m", first.select(0.5F, 0.66F));
		assertEquals("z", first.select(0.5F, 1.0F));
		assertEquals(first.select(0.5F, 0.73F), reversed.select(0.5F, 0.73F));
		assertNull(first.select(0.5F, Float.NaN));
		assertNull(first.select(0.5F, 2.0F));
	}

	private static BiomeSelectionModifier modifier(float mountainUsage, float volcanoUsage) {
		return modifier(mountainUsage, volcanoUsage, ALWAYS_MOUNTAIN, mountains, volcanoes);
	}

	private static BiomeSelectionModifier modifier(
		float mountainUsage,
		float volcanoUsage,
		MountainBoundary mountainBoundary,
		CandidatePool<Holder<Biome>> mountainCandidates,
		CandidatePool<Holder<Biome>> volcanoCandidates
	) {
		return new BiomeSelectionModifier(
			mountainUsage,
			volcanoUsage,
			mountainBoundary,
			mountainCandidates,
			volcanoCandidates,
			Set.of(base, mountain, volcano)
		);
	}

	private static BiomeSelectionTarget mountain(float macroBiomeId, float height) {
		return target(true, false, macroBiomeId, 0.0F, 0.5F, height);
	}

	private static BiomeSelectionTarget volcano(float terrainRegionId) {
		return target(false, true, 0.0F, terrainRegionId, 0.5F, 0.0F);
	}

	private static BiomeSelectionTarget target(
		boolean mountain,
		boolean volcano,
		float macroBiomeId,
		float terrainRegionId,
		float temperature,
		float height
	) {
		return target(mountain, volcano, macroBiomeId, terrainRegionId, temperature, height, 0, 0);
	}

	private static BiomeSelectionTarget target(
		boolean mountain,
		boolean volcano,
		float macroBiomeId,
		float terrainRegionId,
		float temperature,
		float height,
		int blockX,
		int blockZ
	) {
		return new BiomeSelectionTarget(
			mountain,
			volcano,
			macroBiomeId,
			terrainRegionId,
			0.5F,
			temperature,
			height,
			blockX,
			blockZ
		);
	}

	private static CandidatePool<Holder<Biome>> pool(String id, Holder<Biome> biome, CandidateKind kind) {
		return new CandidatePool<>(List.of(candidate(id, biome.value().getBaseTemperature(), biome)), kind);
	}

	private static <T> Candidate<T> candidate(String id, float temperature, T value) {
		return new Candidate<>(ResourceLocation.parse(id), temperature, value);
	}

	private static <T> Candidate<T> candidate(String id, float temperature, int weight, T value) {
		return new Candidate<>(ResourceLocation.parse(id), temperature, weight, value);
	}

	private static Holder<Biome> biome(float temperature) {
		BiomeSpecialEffects effects = new BiomeSpecialEffects.Builder()
			.fogColor(0)
			.waterColor(0)
			.waterFogColor(0)
			.skyColor(0)
			.build();
		Biome biome = new Biome.BiomeBuilder()
			.hasPrecipitation(true)
			.temperature(temperature)
			.downfall(0.5F)
			.specialEffects(effects)
			.mobSpawnSettings(MobSpawnSettings.EMPTY)
			.generationSettings(BiomeGenerationSettings.EMPTY)
			.build();
		return Holder.direct(biome);
	}
}
