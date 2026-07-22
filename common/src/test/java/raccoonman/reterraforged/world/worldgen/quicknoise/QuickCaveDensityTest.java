package raccoonman.reterraforged.world.worldgen.quicknoise;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;

class QuickCaveDensityTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void fillArrayMatchesPointSampling() {
		DensityFunction density = marker().seeded(12345L);
		List<DensityFunction.SinglePointContext> contexts = contexts();
		double[] expected = contexts.stream().mapToDouble(density::compute).toArray();
		double[] actual = new double[contexts.size()];
		density.fillArray(actual, provider(contexts));

		assertArrayEquals(expected, actual);
	}

	@Test
	void worldSeedChangesTheCaveField() {
		DensityFunction first = marker().seeded(12345L);
		DensityFunction second = marker().seeded(12346L);
		assertTrue(contexts().stream().anyMatch(context -> first.compute(context) != second.compute(context)));
	}

	@Test
	void externalVisitorsMapTheTerrainChildWithoutReplacingTheQuickNode() {
		DensityFunction density = marker().seeded(12345L);
		DensityFunction mapped = density.mapAll(function -> function instanceof DensityFunctions.Constant ? DensityFunctions.constant(-8.0D) : function);

		for(DensityFunction.SinglePointContext context : contexts()) {
			assertEquals(-8.0D, mapped.compute(context));
		}
	}

	@Test
	void disabledEntrancesProtectTheTerrainSurface() {
		DensityFunction density = marker(0.0D, 0.0F).seeded(12345L);

		assertTrue(surfaceContexts().stream().allMatch(context -> density.compute(context) >= 0.0D));
	}

	@Test
	void configuredEntrancesCanOpenAtTheTerrainSurface() {
		DensityFunction density = marker(0.0D, 1.0F).seeded(12345L);

		assertTrue(surfaceContexts().stream().anyMatch(context -> density.compute(context) < 0.0D));
	}

	private static QuickCaveDensity.Marker marker() {
		return marker(4.0D, 1.0F);
	}

	private static QuickCaveDensity.Marker marker(double terrainDensity, float entranceProbability) {
		return new QuickCaveDensity.Marker(DensityFunctions.constant(terrainDensity), -64, entranceProbability, 1.5625F, 1.0F, 1.0F, 1.0F);
	}

	private static List<DensityFunction.SinglePointContext> contexts() {
		List<DensityFunction.SinglePointContext> contexts = new ArrayList<>();
		for(int i = 0; i < 96; i++) {
			contexts.add(new DensityFunction.SinglePointContext(-513 + i * 19, -32 + (i * 13) % 192, 777 - i * 23));
		}
		return contexts;
	}

	private static List<DensityFunction.SinglePointContext> surfaceContexts() {
		List<DensityFunction.SinglePointContext> contexts = new ArrayList<>();
		for(int z = -1024; z <= 1024; z += 64) {
			for(int x = -1024; x <= 1024; x += 64) {
				contexts.add(new DensityFunction.SinglePointContext(x, 64, z));
			}
		}
		return contexts;
	}

	private static DensityFunction.ContextProvider provider(List<DensityFunction.SinglePointContext> contexts) {
		return new DensityFunction.ContextProvider() {
			@Override
			public DensityFunction.FunctionContext forIndex(int index) {
				return contexts.get(index);
			}

			@Override
			public void fillAllDirectly(double[] values, DensityFunction function) {
				for(int i = 0; i < values.length; i++) {
					values[i] = function.compute(this.forIndex(i));
				}
			}
		};
	}
}
