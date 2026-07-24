package raccoonman.reterraforged.world.worldgen.cell.terrain.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.Presets;
import raccoonman.reterraforged.data.worldgen.preset.settings.TerrainSettings;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;
import raccoonman.reterraforged.world.worldgen.cell.terrain.provider.TerrainProvider.SelectionWeight;
import raccoonman.reterraforged.world.worldgen.cell.terrain.region.RegionSelector;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

class TerrainWeightSelectionTest {
	private static final long SHUFFLE_SEED = 0x4D415053454544L;
	private static final List<SliderCase> SLIDERS = List.of(
		new SliderCase("steppe", TerrainProvider.STEPPE_MASK, (settings) -> settings.steppe),
		new SliderCase("plains", TerrainProvider.PLAINS_MASK, (settings) -> settings.plains),
		new SliderCase("hills", TerrainProvider.HILLS_MASK, (settings) -> settings.hills),
		new SliderCase("dales", TerrainProvider.DALES_MASK, (settings) -> settings.dales),
		new SliderCase("plateau", TerrainProvider.PLATEAU_MASK, (settings) -> settings.plateau),
		new SliderCase("badlands", TerrainProvider.BADLANDS_MASK, (settings) -> settings.badlands),
		new SliderCase("torridonian", TerrainProvider.TORRIDONIAN_MASK, (settings) -> settings.torridonian),
		new SliderCase("mountains", TerrainProvider.MOUNTAINS_MASK, (settings) -> settings.mountains),
		new SliderCase("volcano", TerrainProvider.VOLCANO_MASK, (settings) -> settings.volcano)
	);

	@Test
	void defaultWeightsMatchEveryLegacyBoundaryAndLargeDeterministicSample() {
		TerrainSettings defaults = defaultSettings();
		List<Marker> markers = markers();
		SelectorCase selection = selector(defaults, markers);
		List<Marker> legacyNodes = legacyNodes(selection.entries);

		assertEquals(41, selection.entries.size());
		assertEquals(80, legacyNodes.size());
		for (int i = 0; i < legacyNodes.size(); ++i) {
			float identity = (float) i / (legacyNodes.size() - 1);
			assertLegacySelection(selection.selector, legacyNodes, identity);
		}
		for (int i = 0; i < legacyNodes.size() - 1; ++i) {
			float boundary = (i + 0.5F) / (legacyNodes.size() - 1);
			assertLegacySelection(selection.selector, legacyNodes, Math.nextDown(boundary));
			assertLegacySelection(selection.selector, legacyNodes, boundary);
			assertLegacySelection(selection.selector, legacyNodes, Math.nextUp(boundary));
		}

		Random random = new Random(0x504152495459L);
		for (int i = 0; i < 1_000_000; ++i) {
			assertLegacySelection(selection.selector, legacyNodes, random.nextFloat());
		}
	}

	@Test
	void everyTerrainSliderStepMovesSelectionsWithoutGlobalDiscontinuity() {
		TerrainSettings defaults = defaultSettings();
		List<Marker> markers = markers();
		SelectorCase baseline = selector(defaults, markers);
		double[] baselineBoundaries = boundaries(baseline.entries);

		for (SliderCase slider : SLIDERS) {
			for (float delta : new float[] { -0.001F, 0.001F }) {
				TerrainSettings changed = defaults.copy();
				TerrainSettings.Terrain terrain = slider.terrain.apply(changed);
				terrain.weight += delta;
				SelectorCase adjusted = selector(changed, markers);
				double[] adjustedBoundaries = boundaries(adjusted.entries);
				double maxMovement = maxBoundaryMovement(baselineBoundaries, adjustedBoundaries);
				String context = slider.name + (delta > 0.0F ? " +0.001" : " -0.001");

				assertTrue(maxMovement > 0.0D, context);
				assertTrue(maxMovement < 0.001D, () -> context + " moved a boundary by " + maxMovement);
				assertTrue(hasChangedSelection(baseline, adjusted, baselineBoundaries, adjustedBoundaries), context);
			}
		}
	}

	@Test
	void everyDisplayedWeightStepIncreasesThatTerrainsSelectionShare() {
		TerrainSettings defaults = defaultSettings();
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < 41; ++i) {
			order.add(i);
		}
		Collections.shuffle(order, new Random(SHUFFLE_SEED));

		for (SliderCase slider : SLIDERS) {
			TerrainSettings changed = defaults.copy();
			TerrainSettings.Terrain terrain = slider.terrain.apply(changed);
			double previousShare = -1.0D;
			for (int step = 0; step <= 10_000; ++step) {
				terrain.weight = step / 1000.0F;
				double share = selectionShare(TerrainProvider.selectionWeights(changed), order, slider.sourceMask);
				if (step > 0) {
					int currentStep = step;
					assertTrue(share > previousShare, () -> slider.name + " did not increase at step " + currentStep);
				}
				previousShare = share;
			}
		}
	}

	@Test
	void continuousBinaryLookupMatchesLinearReferenceAtEndpointsAndThresholds() {
		List<Marker> markers = List.of(new Marker(0), new Marker(1), new Marker(2), new Marker(3));
		int[] multiplicities = { 1, 2, 1, 3 };
		double[] configuredWeights = { 0.0D, 1.001D, 0.333D, 0.0D };
		List<CellPopulator> weighted = new ArrayList<>();
		for (int i = 0; i < markers.size(); ++i) {
			weighted.add(RegionSelector.weighted(markers.get(i), multiplicities[i], configuredWeights[i], 1.0D));
		}
		RegionSelector selector = new RegionSelector(weighted);
		double[] cumulativeMasses = cumulativeMasses(multiplicities, configuredWeights);
		double totalMass = cumulativeMasses[cumulativeMasses.length - 1];
		List<Float> identities = new ArrayList<>(List.of(0.0F, Math.nextUp(0.0F), Math.nextDown(1.0F), 1.0F));
		for (double cumulativeMass : cumulativeMasses) {
			float threshold = (float) (cumulativeMass / totalMass);
			if (threshold >= 0.0F && threshold <= 1.0F) {
				identities.add(Math.max(0.0F, Math.nextDown(threshold)));
				identities.add(threshold);
				identities.add(Math.min(1.0F, Math.nextUp(threshold)));
			}
		}

		for (float identity : identities) {
			int expectedIndex = linearSelectionIndex(identity, cumulativeMasses, totalMass, 2);
			assertSame(markers.get(expectedIndex), selector.get(identity), () -> "identity=" + identity);
		}
	}

	@Test
	void zeroWeightRemovesEveryModuleInvolvingThatTerrain() {
		TerrainSettings defaults = defaultSettings();
		List<Marker> markers = markers();

		for (SliderCase slider : SLIDERS) {
			TerrainSettings changed = defaults.copy();
			slider.terrain.apply(changed).weight = 0.0F;
			SelectorCase selection = selector(changed, markers);

			for (Entry entry : selection.entries) {
				if ((entry.weight.sourceMask() & slider.sourceMask) != 0) {
					assertEquals(0.0D, entry.weight.configuredWeight(), slider.name);
				}
			}

			Random random = new Random(0x5A45524F0000L + slider.sourceMask);
			for (int i = 0; i < 100_000; ++i) {
				Marker selected = (Marker) selection.selector.get(random.nextFloat());
				SelectionWeight selectedWeight = selection.weightsByMarker.get(selected.index);
				assertEquals(0, selectedWeight.sourceMask() & slider.sourceMask, slider.name);
			}
		}
	}

	@Test
	void moduleGraphIsStableAtZeroAndAllZeroFallsBackToDefaultLegacyMap() {
		TerrainSettings defaults = defaultSettings();
		TerrainSettings zero = defaults.copy();
		for (SliderCase slider : SLIDERS) {
			slider.terrain.apply(zero).weight = 0.0F;
		}

		List<SelectionWeight> defaultWeights = TerrainProvider.selectionWeights(defaults);
		List<SelectionWeight> zeroWeights = TerrainProvider.selectionWeights(zero);
		assertEquals(41, defaultWeights.size());
		assertEquals(defaultWeights.size(), zeroWeights.size());
		for (int i = 0; i < defaultWeights.size(); ++i) {
			assertEquals(defaultWeights.get(i).sourceMask(), zeroWeights.get(i).sourceMask());
			assertEquals(defaultWeights.get(i).legacyMultiplicity(), zeroWeights.get(i).legacyMultiplicity());
			assertEquals(0.0D, zeroWeights.get(i).configuredWeight());
		}

		List<Marker> markers = markers();
		SelectorCase baseline = selector(defaults, markers);
		SelectorCase fallback = selector(zero, markers);
		Random random = new Random(0x46414C4C4241434BL);
		for (int i = 0; i < 250_000; ++i) {
			float identity = random.nextFloat();
			assertSame(baseline.selector.get(identity), fallback.selector.get(identity));
		}
	}

	private static TerrainSettings defaultSettings() {
		return Presets.makeRTFDefault().terrain().copy();
	}

	private static List<Marker> markers() {
		List<Marker> markers = new ArrayList<>();
		for (int i = 0; i < 41; ++i) {
			markers.add(new Marker(i));
		}
		return markers;
	}

	private static SelectorCase selector(TerrainSettings settings, List<Marker> markers) {
		List<SelectionWeight> weights = TerrainProvider.selectionWeights(settings);
		assertEquals(markers.size(), weights.size());
		List<Entry> entries = new ArrayList<>(weights.size());
		for (int i = 0; i < weights.size(); ++i) {
			entries.add(new Entry(markers.get(i), weights.get(i)));
		}
		Collections.shuffle(entries, new Random(SHUFFLE_SEED));

		List<CellPopulator> weighted = new ArrayList<>(entries.size());
		List<SelectionWeight> weightsByMarker = new ArrayList<>(Collections.nCopies(markers.size(), null));
		for (Entry entry : entries) {
			weighted.add(RegionSelector.weighted(
				entry.marker,
				entry.weight.legacyMultiplicity(),
				entry.weight.configuredWeight(),
				entry.weight.defaultWeight()
			));
			weightsByMarker.set(entry.marker.index, entry.weight);
		}
		return new SelectorCase(new RegionSelector(weighted), entries, weightsByMarker);
	}

	private static List<Marker> legacyNodes(List<Entry> entries) {
		List<Marker> nodes = new ArrayList<>();
		for (Entry entry : entries) {
			for (int i = 0; i < entry.weight.legacyMultiplicity(); ++i) {
				nodes.add(entry.marker);
			}
		}
		return nodes;
	}

	private static void assertLegacySelection(RegionSelector selector, List<Marker> legacyNodes, float identity) {
		int index = NoiseUtil.round(identity * (legacyNodes.size() - 1));
		assertSame(legacyNodes.get(index), selector.get(identity), () -> "identity=" + identity);
	}

	private static double[] boundaries(List<Entry> entries) {
		double[] cumulative = new double[entries.size() - 1];
		double[] masses = new double[entries.size()];
		double total = 0.0D;
		for (int i = 0; i < entries.size(); ++i) {
			SelectionWeight weight = entries.get(i).weight;
			double baselineMass = weight.legacyMultiplicity();
			if (i == 0 || i == entries.size() - 1) {
				baselineMass -= 0.5D;
			}
			masses[i] = baselineMass * weight.configuredWeight() / weight.defaultWeight();
			total += masses[i];
		}
		assertTrue(total > 0.0D);
		double value = 0.0D;
		for (int i = 0; i < cumulative.length; ++i) {
			value += masses[i];
			cumulative[i] = value / total;
		}
		return cumulative;
	}

	private static double maxBoundaryMovement(double[] first, double[] second) {
		assertEquals(first.length, second.length);
		double max = 0.0D;
		for (int i = 0; i < first.length; ++i) {
			max = Math.max(max, Math.abs(first[i] - second[i]));
		}
		return max;
	}

	private static double selectionShare(List<SelectionWeight> weights, List<Integer> order, int sourceMask) {
		double selectedMass = 0.0D;
		double totalMass = 0.0D;
		for (int i = 0; i < order.size(); ++i) {
			SelectionWeight weight = weights.get(order.get(i));
			double baselineMass = weight.legacyMultiplicity();
			if (i == 0 || i == order.size() - 1) {
				baselineMass -= 0.5D;
			}
			double mass = baselineMass * weight.configuredWeight() / weight.defaultWeight();
			totalMass += mass;
			if ((weight.sourceMask() & sourceMask) != 0) {
				selectedMass += mass;
			}
		}
		return selectedMass / totalMass;
	}

	private static double[] cumulativeMasses(int[] multiplicities, double[] configuredWeights) {
		assertEquals(multiplicities.length, configuredWeights.length);
		double[] cumulativeMasses = new double[multiplicities.length];
		double cumulativeMass = 0.0D;
		for (int i = 0; i < multiplicities.length; ++i) {
			double baselineMass = multiplicities[i];
			if (i == 0 || i == multiplicities.length - 1) {
				baselineMass -= 0.5D;
			}
			cumulativeMass += baselineMass * configuredWeights[i];
			cumulativeMasses[i] = cumulativeMass;
		}
		return cumulativeMasses;
	}

	private static int linearSelectionIndex(float identity, double[] cumulativeMasses, double totalMass, int lastPositiveIndex) {
		double value = identity;
		if (!(value > 0.0D)) {
			value = 0.0D;
		} else if (value >= 1.0D) {
			value = 1.0D;
		}
		double target = value * totalMass;
		for (int i = 0; i < cumulativeMasses.length; ++i) {
			if (target < cumulativeMasses[i]) {
				return i;
			}
		}
		return lastPositiveIndex;
	}

	private static boolean hasChangedSelection(SelectorCase first, SelectorCase second, double[] firstBoundaries, double[] secondBoundaries) {
		for (int i = 0; i < firstBoundaries.length; ++i) {
			double low = Math.min(firstBoundaries[i], secondBoundaries[i]);
			double high = Math.max(firstBoundaries[i], secondBoundaries[i]);
			if (low == high) {
				continue;
			}
			float midpoint = (float) ((low + high) * 0.5D);
			for (float identity : new float[] { midpoint, Math.nextDown(midpoint), Math.nextUp(midpoint) }) {
				if (first.selector.get(identity) != second.selector.get(identity)) {
					return true;
				}
			}
		}
		return false;
	}

	private record SliderCase(String name, int sourceMask, Function<TerrainSettings, TerrainSettings.Terrain> terrain) {
	}

	private record Marker(int index) implements CellPopulator {

		@Override
		public void apply(Cell cell, float x, float z) {
		}
	}

	private record Entry(Marker marker, SelectionWeight weight) {
	}

	private record SelectorCase(RegionSelector selector, List<Entry> entries, List<SelectionWeight> weightsByMarker) {
	}
}
