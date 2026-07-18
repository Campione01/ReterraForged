package raccoonman.reterraforged.world.worldgen.opencl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;

import org.jetbrains.annotations.Nullable;

import net.minecraft.world.level.levelgen.DensityFunction;

final class OpenClDensityGroup {
	private static final ThreadLocal<Cycle> ACTIVE_CYCLE = new ThreadLocal<>();

	private final List<DensityFunction> pending = new ArrayList<>();
	private final Map<DensityFunction.Visitor, Mapping> mappings = new WeakHashMap<>();
	@Nullable
	private volatile DensityFunction[] delegates;
	@Nullable
	private volatile OpenClKernelTemplate template;

	OpenClDensityGroup() {
	}

	private OpenClDensityGroup(int outputCount) {
		this.delegates = new DensityFunction[outputCount];
	}

	int add(DensityFunction delegate) {
		if(this.delegates != null) {
			throw new IllegalStateException("OpenCL density group is already sealed");
		}
		int slot = this.pending.size();
		this.pending.add(delegate);
		return slot;
	}

	boolean seal() {
		if(this.delegates != null) {
			return this.template != null;
		}
		this.delegates = this.pending.toArray(DensityFunction[]::new);
		this.pending.clear();
		this.template = OpenClGraphCompiler.compile(Arrays.asList(this.delegates)).orElse(null);
		return this.template != null;
	}

	OpenClDensityGroup mapped(DensityFunction.Visitor visitor, int slot, DensityFunction delegate) {
		DensityFunction[] currentDelegates = this.delegates;
		if(currentDelegates == null) {
			return this;
		}
		synchronized(this.mappings) {
			Mapping mapping = this.mappings.computeIfAbsent(visitor, key -> new Mapping(currentDelegates.length));
			mapping.add(slot, delegate);
			return mapping.group;
		}
	}

	boolean fill(int slot, DensityFunction delegate, double[] output, DensityFunction.ContextProvider contextProvider) {
		Cycle active = ACTIVE_CYCLE.get();
		if(active != null && active.matches(this, contextProvider, output.length, slot)) {
			return active.consume(slot, delegate, output, contextProvider);
		}
		ACTIVE_CYCLE.remove();

		OpenClKernelTemplate currentTemplate = this.template;
		DensityFunction[] currentDelegates = this.delegates;
		if(currentTemplate == null || currentDelegates == null || slot >= currentDelegates.length || !OpenClManager.canUse(currentTemplate)) {
			return false;
		}

		boolean verify = OpenClManager.requiresVerification(currentTemplate);
		double[] firstExpected = null;
		if(verify) {
			firstExpected = new double[output.length];
			delegate.fillArray(firstExpected, contextProvider);
		}

		double[] accelerated = null;
		boolean failed = false;
		try {
			OpenClKernelTemplate.Batch batch = currentTemplate.collect(contextProvider, output.length);
			accelerated = new double[output.length * currentTemplate.outputCount()];
			if(!OpenClManager.executeUnchecked(currentTemplate, batch, accelerated)) {
				accelerated = null;
				failed = true;
			}
		} catch(RuntimeException e) {
			OpenClManager.recordCollectionFailure(currentTemplate, e);
			failed = true;
		}

		Cycle cycle = new Cycle(this, currentTemplate, contextProvider, output.length, accelerated, verify, failed, slot, firstExpected);
		ACTIVE_CYCLE.set(cycle);
		return cycle.consume(slot, delegate, output, contextProvider);
	}

	String description() {
		OpenClKernelTemplate currentTemplate = this.template;
		return currentTemplate == null ? "none" : currentTemplate.id().substring(0, 12) + "/" + currentTemplate.outputCount();
	}

	private final class Mapping {
		private final OpenClDensityGroup group;
		private int count;

		private Mapping(int outputCount) {
			this.group = new OpenClDensityGroup(outputCount);
		}

		private void add(int slot, DensityFunction delegate) {
			DensityFunction[] mappedDelegates = this.group.delegates;
			if(mappedDelegates == null || slot < 0 || slot >= mappedDelegates.length || mappedDelegates[slot] != null) {
				return;
			}
			mappedDelegates[slot] = delegate;
			this.count++;
			if(this.count == mappedDelegates.length) {
				this.group.template = OpenClGraphCompiler.compile(Arrays.asList(mappedDelegates)).orElse(null);
			}
		}
	}

	private static final class Cycle {
		private final OpenClDensityGroup group;
		private final OpenClKernelTemplate template;
		private final DensityFunction.ContextProvider contextProvider;
		private final int size;
		@Nullable
		private final double[] accelerated;
		private final boolean verification;
		private final boolean[] consumed;
		private final int firstSlot;
		@Nullable
		private final double[] firstExpected;
		private int consumedCount;
		private boolean failed;

		private Cycle(OpenClDensityGroup group, OpenClKernelTemplate template, DensityFunction.ContextProvider contextProvider, int size,
			@Nullable double[] accelerated, boolean verification, boolean failed, int firstSlot, @Nullable double[] firstExpected) {
			this.group = group;
			this.template = template;
			this.contextProvider = contextProvider;
			this.size = size;
			this.accelerated = accelerated;
			this.verification = verification;
			this.failed = failed;
			this.consumed = new boolean[template.outputCount()];
			this.firstSlot = firstSlot;
			this.firstExpected = firstExpected;
		}

		private boolean matches(OpenClDensityGroup group, DensityFunction.ContextProvider contextProvider, int size, int slot) {
			return this.group == group && this.contextProvider == contextProvider && this.size == size && slot >= 0
				&& slot < this.consumed.length && !this.consumed[slot];
		}

		private boolean consume(int slot, DensityFunction delegate, double[] output, DensityFunction.ContextProvider contextProvider) {
			this.consumed[slot] = true;
			this.consumedCount++;
			boolean supplied = false;
			if(this.verification && !this.failed && this.accelerated != null) {
				double[] expected;
				if(slot == this.firstSlot && this.firstExpected != null) {
					expected = this.firstExpected;
				} else {
					expected = new double[this.size];
					delegate.fillArray(expected, contextProvider);
				}
				int offset = slot * this.size;
				for(int i = 0; i < this.size; i++) {
					if(Double.doubleToRawLongBits(expected[i]) != Double.doubleToRawLongBits(this.accelerated[offset + i])) {
						this.failed = true;
						OpenClManager.rejectMismatch(this.template, offset + i, expected[i], this.accelerated[offset + i]);
						break;
					}
				}
				System.arraycopy(expected, 0, output, 0, this.size);
				supplied = true;
			} else if(this.accelerated != null && !this.failed) {
				System.arraycopy(this.accelerated, slot * this.size, output, 0, this.size);
				supplied = true;
			} else if(slot == this.firstSlot && this.firstExpected != null) {
				System.arraycopy(this.firstExpected, 0, output, 0, this.size);
				supplied = true;
			}

			if(this.consumedCount == this.consumed.length) {
				if(this.verification && !this.failed) {
					OpenClManager.markVerified(this.template);
				}
				ACTIVE_CYCLE.remove();
			}
			return supplied;
		}
	}
}
