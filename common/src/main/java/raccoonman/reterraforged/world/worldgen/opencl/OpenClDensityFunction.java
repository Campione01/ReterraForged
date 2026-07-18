package raccoonman.reterraforged.world.worldgen.opencl;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.DensityFunction;

final class OpenClDensityFunction implements DensityFunction {
	private final DensityFunction delegate;
	private final OpenClDensityGroup group;
	private final int slot;

	OpenClDensityFunction(DensityFunction delegate, OpenClDensityGroup group, int slot) {
		this.delegate = delegate;
		this.group = group;
		this.slot = slot;
	}

	@Override
	public double compute(FunctionContext context) {
		return this.delegate.compute(context);
	}

	@Override
	public void fillArray(double[] array, ContextProvider contextProvider) {
		if(!this.group.fill(this.slot, this.delegate, array, contextProvider)) {
			this.delegate.fillArray(array, contextProvider);
		}
	}

	@Override
	public DensityFunction mapAll(Visitor visitor) {
		DensityFunction mapped = this.delegate.mapAll(visitor);
		OpenClDensityGroup mappedGroup = this.group.mapped(visitor, this.slot, mapped);
		return visitor.apply(new OpenClDensityFunction(mapped, mappedGroup, this.slot));
	}

	@Override
	public double minValue() {
		return this.delegate.minValue();
	}

	@Override
	public double maxValue() {
		return this.delegate.maxValue();
	}

	@Override
	public KeyDispatchDataCodec<? extends DensityFunction> codec() {
		return this.delegate.codec();
	}

	@Override
	public String toString() {
		return "OpenClDensityFunction[delegate=" + this.delegate + ", group=" + this.group.description() + ", slot=" + this.slot + "]";
	}
}
