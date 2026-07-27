package raccoonman.reterraforged.mixin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.mojang.datafixers.util.Pair;

import net.minecraft.SharedConstants;
import net.minecraft.core.Holder;
import net.minecraft.server.Bootstrap;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import raccoonman.reterraforged.world.worldgen.densityfunction.CellSampler;
import raccoonman.reterraforged.world.worldgen.densityfunction.ClampToNearestUnit;
import raccoonman.reterraforged.world.worldgen.densityfunction.ConditionalFlatCache;
import raccoonman.reterraforged.world.worldgen.densityfunction.LinearSplineFunction;

class C2MEShallowVisitorCompatibilityTest {
	@BeforeAll
	static void bootstrapMinecraft() {
		SharedConstants.tryDetectVersion();
		Bootstrap.bootStrap();
	}

	@Test
	void repairsCellSamplerHiddenByClampAndHolder() {
		CellSampler sampler = sampler(CellSampler.Field.HEIGHT);
		ClampToNearestUnit root = new ClampToNearestUnit(holder(sampler), 64);
		AtomicInteger mapped = new AtomicInteger();

		DensityFunction repaired = MixinNoiseChunk.repairC2MEShallowMapping(root, (cellSampler) -> {
			mapped.incrementAndGet();
			return cellSampler.new CacheChunk(null, new CellSampler.Cache2d(), 0, 0);
		});

		assertEquals(1, mapped.get());
		assertEquals(64, ((ClampToNearestUnit) repaired).resolution());
		assertEquals(0, countCellSamplers(repaired));
		assertEquals(1, countCacheChunks(repaired));
	}

	@Test
	void repairsEveryRtfCompositeChildRecursively() {
		CellSampler height = sampler(CellSampler.Field.HEIGHT);
		CellSampler erosion = sampler(CellSampler.Field.EROSION);
		CellSampler weirdness = sampler(CellSampler.Field.WEIRDNESS);
		LinearSplineFunction spline = new LinearSplineFunction(
			new ConditionalFlatCache(holder(height)),
			List.of(
				Pair.of(0.0D, holder(erosion)),
				Pair.of(1.0D, new ClampToNearestUnit(holder(weirdness), 32))
			)
		);
		ClampToNearestUnit root = new ClampToNearestUnit(holder(spline), 128);
		AtomicInteger mapped = new AtomicInteger();

		DensityFunction repaired = MixinNoiseChunk.repairC2MEShallowMapping(root, (cellSampler) -> {
			mapped.incrementAndGet();
			return DensityFunctions.constant(cellSampler.field().ordinal() / 16.0D);
		});

		assertEquals(3, mapped.get());
		assertEquals(0, countCellSamplers(repaired));
		ClampToNearestUnit repairedRoot = (ClampToNearestUnit) repaired;
		assertEquals(128, repairedRoot.resolution());
	}

	@Test
	void leavesVanillaAndAlreadyMappedRootsUntouched() {
		CellSampler hiddenVanillaSampler = sampler(CellSampler.Field.HEIGHT);
		DensityFunction vanilla = DensityFunctions.add(
			holder(hiddenVanillaSampler),
			DensityFunctions.constant(0.5D)
		);
		AtomicInteger mapped = new AtomicInteger();

		DensityFunction repaired = MixinNoiseChunk.repairC2MEShallowMapping(vanilla, (cellSampler) -> {
			mapped.incrementAndGet();
			return DensityFunctions.zero();
		});

		assertSame(vanilla, repaired);
		assertEquals(0, mapped.get());

		DensityFunction thirdParty = new ThirdPartyComposite(
			holder(sampler(CellSampler.Field.EROSION))
		);
		DensityFunction thirdPartyRepair = MixinNoiseChunk.repairC2MEShallowMapping(thirdParty, (cellSampler) -> {
			mapped.incrementAndGet();
			return DensityFunctions.zero();
		});

		assertSame(thirdParty, thirdPartyRepair);
		assertEquals(0, mapped.get());

		CellSampler sampler = sampler(CellSampler.Field.HEIGHT);
		CellSampler.CacheChunk cacheChunk = sampler.new CacheChunk(null, new CellSampler.Cache2d(), 0, 0);
		ClampToNearestUnit cachedRoot = new ClampToNearestUnit(cacheChunk, 64);
		DensityFunction cachedRepair = MixinNoiseChunk.repairC2MEShallowMapping(cachedRoot, (cellSampler) -> {
			mapped.incrementAndGet();
			return DensityFunctions.zero();
		});

		assertEquals(0, mapped.get());
		assertEquals(0, countCellSamplers(cachedRepair));
		assertTrue(((ClampToNearestUnit) cachedRepair).function() instanceof CellSampler.CacheChunk);
	}

	private static DensityFunction holder(DensityFunction function) {
		return new DensityFunctions.HolderHolder(Holder.direct(function));
	}

	private static CellSampler sampler(CellSampler.Field field) {
		return new CellSampler(() -> null, field);
	}

	private static int countCellSamplers(DensityFunction function) {
		AtomicInteger count = new AtomicInteger();
		function.mapAll((child) -> {
			if(child instanceof CellSampler) {
				count.incrementAndGet();
			}
			return child;
		});
		return count.get();
	}

	private static int countCacheChunks(DensityFunction function) {
		AtomicInteger count = new AtomicInteger();
		function.mapAll((child) -> {
			if(child instanceof CellSampler.CacheChunk) {
				count.incrementAndGet();
			}
			return child;
		});
		return count.get();
	}

	private record ThirdPartyComposite(DensityFunction child) implements DensityFunction {
		@Override
		public double compute(FunctionContext context) {
			return this.child.compute(context);
		}

		@Override
		public void fillArray(double[] array, ContextProvider contextProvider) {
			this.child.fillArray(array, contextProvider);
		}

		@Override
		public DensityFunction mapAll(Visitor visitor) {
			return visitor.apply(new ThirdPartyComposite(this.child.mapAll(visitor)));
		}

		@Override
		public double minValue() {
			return this.child.minValue();
		}

		@Override
		public double maxValue() {
			return this.child.maxValue();
		}

		@Override
		public KeyDispatchDataCodec<ThirdPartyComposite> codec() {
			throw new UnsupportedOperationException();
		}
	}
}
