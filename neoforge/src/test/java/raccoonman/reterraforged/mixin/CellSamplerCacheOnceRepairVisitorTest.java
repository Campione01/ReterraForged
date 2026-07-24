package raccoonman.reterraforged.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderGetter;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseRouter;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.neoforged.fml.loading.LoadingModList;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.WorldLookup;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;

class CellSamplerCacheOnceRepairVisitorTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		LoadingModList.of(List.of(), List.of(), List.of(), List.of(), Map.of());
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void actualVisitorRepairsBothHistoricalEdgesAfterMappingChildren() throws Exception {
		DensityFunction safeVanillaCache = DensityFunctions.cacheOnce(
			DensityFunctions.constant(0.25D)
		);
		DensityFunction historical = DensityFunctions.rangeChoice(
			persistedHolderCache(new CellSampler.Marker(CellSampler.Field.HEIGHT)),
			-1_000_000.0D,
			1.0D,
			persistedHolderCache(new CellSampler.Marker(CellSampler.Field.HEIGHT)),
			DensityFunctions.zero()
		);
		DensityFunction graph = DensityFunctions.add(historical, safeVanillaCache);

		RepairResult result = applyProductionRepair(graph);

		assertEquals(2, result.repaired(), "Production visitor missed a historical CacheOnce edge");
		assertEquals(1, result.cacheOnceMarkers(), "Unrelated vanilla CacheOnce was not preserved");
		assertEquals(2, result.cellSamplers(), "Historical marker mapping lost a CellSampler edge");
		assertEquals(
			2,
			result.supplierIdentities(),
			"Historical edges did not receive distinct production supplier identities"
		);
	}

	@Test
	void actualVisitorPreservesAnUnrelatedInlineCompositeContainingCellSampler() throws Exception {
		DensityFunction unrelatedComposite = DensityFunctions.cacheOnce(
			DensityFunctions.add(
				new CellSampler.Marker(CellSampler.Field.HEIGHT),
				DensityFunctions.constant(0.125D)
			)
		);

		RepairResult result = applyProductionRepair(unrelatedComposite);

		assertEquals(
			0,
			result.repaired(),
			"Inline third-party composite was treated as the persisted holder defect"
		);
		assertEquals(
			1,
			result.cacheOnceMarkers(),
			"Inline third-party CacheOnce was not preserved"
		);
		assertEquals(1, result.cellSamplers(), "Composite lost its mapped CellSampler child");
	}

	private static DensityFunction persistedHolderCache(DensityFunction function) {
		return DensityFunctions.cacheOnce(
			new DensityFunctions.HolderHolder(Holder.direct(function))
		);
	}

	private static RepairResult applyProductionRepair(DensityFunction finalDensity) throws Exception {
		MixinRandomState mixin = new MixinRandomState();
		Method redirect = MixinRandomState.class.getDeclaredMethod(
			"RandomState",
			NoiseRouter.class,
			DensityFunction.Visitor.class,
			NoiseGeneratorSettings.class,
			HolderGetter.class,
			long.class
		);
		redirect.setAccessible(true);
		NoiseRouter mapped = (NoiseRouter)redirect.invoke(
			mixin,
			router(finalDensity),
			new DensityFunction.Visitor() {
				@Override
				public DensityFunction apply(DensityFunction function) {
					return function;
				}

				@Override
				public DensityFunction.NoiseHolder visitNoise(
					DensityFunction.NoiseHolder noiseHolder
				) {
					return noiseHolder;
				}
			},
			null,
			null,
			0L
		);

		Field repairedField = MixinRandomState.class.getDeclaredField(
			"reterraforged$repairedCellSamplerCacheOnceMarkers"
		);
		repairedField.setAccessible(true);
		int repaired = repairedField.getInt(mixin);
		AtomicInteger cacheOnce = new AtomicInteger();
		AtomicInteger cellSamplers = new AtomicInteger();
		Set<Supplier<WorldLookup>> suppliers = Collections.newSetFromMap(
			new IdentityHashMap<>()
		);
		mapped.finalDensity().mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof DensityFunctions.Marker marker
					&& marker.type() == DensityFunctions.Marker.Type.CacheOnce) {
					cacheOnce.incrementAndGet();
				}
				if(function instanceof CellSampler sampler) {
					cellSamplers.incrementAndGet();
					assertTrue(
						suppliers.add(sampler.deferredLookup()),
						"Production visitor reused a CellSampler supplier identity"
					);
				}
				return function;
			}
		});
		return new RepairResult(
			repaired,
			cacheOnce.get(),
			cellSamplers.get(),
			suppliers.size()
		);
	}

	private static NoiseRouter router(DensityFunction finalDensity) {
		DensityFunction zero = DensityFunctions.zero();
		return new NoiseRouter(
			zero,
			zero,
			zero,
			zero,
			zero,
			zero,
			zero,
			zero,
			zero,
			zero,
			zero,
			finalDensity,
			zero,
			zero,
			zero
		);
	}

	private record RepairResult(
		int repaired,
		int cacheOnceMarkers,
		int cellSamplers,
		int supplierIdentities
	) {
	}
}
