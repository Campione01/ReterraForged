package raccoonman.reterraforged.world.worldgen.opencl;

import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseRouter;

public final class OpenClDensityFunctions {
	private OpenClDensityFunctions() {
	}

	@Nullable
	public static DensityFunction wrapFinalDensity(DensityFunction density) {
		return wrapFinalDensity(density, true);
	}

	@Nullable
	static DensityFunction wrapFinalDensityAllowingCpuInputs(DensityFunction density) {
		return wrapFinalDensity(density, false);
	}

	@Nullable
	private static DensityFunction wrapFinalDensity(DensityFunction density, boolean selfContainedOnly) {
		AtomicInteger candidates = new AtomicInteger();
		OpenClDensityGroup group = new OpenClDensityGroup(selfContainedOnly);
		DensityFunction mapped = density.mapAll(new DensityFunction.Visitor() {
			@Override
			public DensityFunction apply(DensityFunction function) {
				if(function instanceof DensityFunctions.Marker marker
					&& marker.type() == DensityFunctions.Marker.Type.Interpolated
					&& !(marker.wrapped() instanceof OpenClDensityFunction)) {
					return OpenClGraphCompiler.compile(marker.wrapped())
						.filter(template -> !selfContainedOnly || template.inputCount() == 0)
						.map(template -> {
							int slot = group.add(marker.wrapped());
							candidates.incrementAndGet();
							return DensityFunctions.interpolated(new OpenClDensityFunction(marker.wrapped(), group, slot));
						}).orElse(function);
				}
				return function;
			}
		});
		return candidates.get() > 0 && group.seal() ? mapped : null;
	}

	public static NoiseRouter withFinalDensity(NoiseRouter router, DensityFunction finalDensity) {
		return new NoiseRouter(
			router.barrierNoise(),
			router.fluidLevelFloodednessNoise(),
			router.fluidLevelSpreadNoise(),
			router.lavaNoise(),
			router.temperature(),
			router.vegetation(),
			router.continents(),
			router.erosion(),
			router.depth(),
			router.ridges(),
			router.initialDensityWithoutJaggedness(),
			finalDensity,
			router.veinToggle(),
			router.veinRidged(),
			router.veinGap()
		);
	}
}
