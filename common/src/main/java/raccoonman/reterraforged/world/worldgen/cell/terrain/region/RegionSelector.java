package raccoonman.reterraforged.world.worldgen.cell.terrain.region;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.CellPopulator;
import raccoonman.reterraforged.world.worldgen.cell.terrain.populator.WeightedPopulator;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;

public class RegionSelector implements CellPopulator {
	private int maxIndex;
	private CellPopulator[] nodes;
	private CellPopulator[] legacyNodes;
	private double[] cumulativeMasses;
	private double totalMass;
	private int lastPositiveIndex;
	private boolean legacyLookup;

	public RegionSelector(List<CellPopulator> populators) {
		if (!this.configureContinuous(populators)) {
			this.legacyNodes = getWeightedArray(populators);
			this.maxIndex = this.legacyNodes.length - 1;
			this.legacyLookup = true;
		}
	}

	@Override
	public void apply(Cell cell, float x, float y) {
		this.get(cell.terrainRegionId).apply(cell, x, y);
	}

	public CellPopulator get(float identity) {
		if (this.legacyLookup) {
			int index = NoiseUtil.round(identity * this.maxIndex);
			return this.legacyNodes[index];
		}

		double value = identity;
		if (!(value > 0.0D)) {
			value = 0.0D;
		} else if (value >= 1.0D) {
			value = 1.0D;
		}
		double target = value * this.totalMass;
		int low = 0;
		int high = this.cumulativeMasses.length;
		while (low < high) {
			int mid = (low + high) >>> 1;
			if (target < this.cumulativeMasses[mid]) {
				high = mid;
			} else {
				low = mid + 1;
			}
		}
		if (low < this.nodes.length) {
			return this.nodes[low];
		}
		return this.nodes[this.lastPositiveIndex];
	}

	public static CellPopulator weighted(CellPopulator delegate, int legacyMultiplicity, double configuredWeight, double defaultWeight) {
		return new WeightedNode(delegate, legacyMultiplicity, configuredWeight, defaultWeight);
	}

	private boolean configureContinuous(List<CellPopulator> populators) {
		if (populators.isEmpty()) {
			return false;
		}

		List<WeightedNode> weightedNodes = new ArrayList<>(populators.size());
		for (CellPopulator populator : populators) {
			if (!(populator instanceof WeightedNode weightedNode)) {
				return false;
			}
			weightedNodes.add(weightedNode);
		}

		this.nodes = new CellPopulator[weightedNodes.size()];
		this.cumulativeMasses = new double[weightedNodes.size()];
		List<CellPopulator> legacy = new ArrayList<>();
		boolean defaults = true;
		double cumulativeMass = 0.0D;
		for (int i = 0; i < weightedNodes.size(); ++i) {
			WeightedNode weightedNode = weightedNodes.get(i);
			this.nodes[i] = weightedNode.delegate();
			for (int count = 0; count < weightedNode.legacyMultiplicity(); ++count) {
				legacy.add(weightedNode.delegate());
			}

			double baselineMass = weightedNode.legacyMultiplicity();
			// round(identity * maxIndex) gives the first and last legacy slots half-width intervals.
			if (i == 0) {
				baselineMass -= 0.5D;
			}
			if (i == weightedNodes.size() - 1) {
				baselineMass -= 0.5D;
			}
			double mass = baselineMass * weightedNode.configuredWeight() / weightedNode.defaultWeight();
			cumulativeMass += mass;
			this.cumulativeMasses[i] = cumulativeMass;
			if (mass > 0.0D) {
				this.lastPositiveIndex = i;
			}
			defaults &= Double.compare(weightedNode.configuredWeight(), weightedNode.defaultWeight()) == 0;
		}

		this.legacyNodes = legacy.toArray(CellPopulator[]::new);
		this.maxIndex = this.legacyNodes.length - 1;
		this.totalMass = cumulativeMass;
		// The legacy lookup is the deterministic fallback for an undefined all-zero distribution.
		this.legacyLookup = defaults || cumulativeMass == 0.0D;
		return true;
	}

	private static CellPopulator[] getWeightedArray(List<CellPopulator> modules) {
		float smallest = Float.MAX_VALUE;
		for (CellPopulator p : modules) {
			if (p instanceof WeightedPopulator tp) {
				if (tp.weight() == 0.0F) {
					continue;
				}
				smallest = Math.min(smallest, tp.weight());
			} else {
				smallest = Math.min(smallest, 1.0F);
			}
		}
		if (smallest == Float.MAX_VALUE) {
			return modules.toArray(CellPopulator[]::new);
		}
		List<CellPopulator> result = new LinkedList<>();
		for (CellPopulator p2 : modules) {
			int count;
			if (p2 instanceof WeightedPopulator tp2) {
				if (tp2.weight() == 0.0F) {
					continue;
				}
				count = Math.round(tp2.weight() / smallest);
			} else {
				count = Math.round(1.0F / smallest);
			}
			while (count-- > 0) {
				result.add(p2);
			}
		}
		if (result.isEmpty()) {
			return modules.toArray(CellPopulator[]::new);
		}
		return result.toArray(CellPopulator[]::new);
	}

	private record WeightedNode(CellPopulator delegate, int legacyMultiplicity, double configuredWeight, double defaultWeight) implements CellPopulator {

		private WeightedNode {
			if (legacyMultiplicity < 1) {
				throw new IllegalArgumentException("Legacy multiplicity must be positive");
			}
			if (!Double.isFinite(configuredWeight) || configuredWeight < 0.0D) {
				configuredWeight = 0.0D;
			}
			if (!Double.isFinite(defaultWeight) || defaultWeight <= 0.0D) {
				throw new IllegalArgumentException("Default weight must be finite and positive");
			}
		}

		@Override
		public void apply(Cell cell, float x, float z) {
			this.delegate.apply(cell, x, z);
		}
	}
}
