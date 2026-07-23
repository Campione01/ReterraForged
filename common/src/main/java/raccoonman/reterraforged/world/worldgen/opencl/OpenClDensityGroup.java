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

	private final boolean selfContainedOnly;
	private final List<DensityFunction> pending = new ArrayList<>();
	private final Map<DensityFunction.Visitor, Mapping> mappings = new WeakHashMap<>();
	@Nullable
	private volatile DensityFunction[] delegates;
	@Nullable
	private volatile OpenClKernelTemplate template;

	OpenClDensityGroup(boolean selfContainedOnly) {
		this.selfContainedOnly = selfContainedOnly;
	}

	private OpenClDensityGroup(int outputCount, boolean selfContainedOnly) {
		this.selfContainedOnly = selfContainedOnly;
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
		this.template = OpenClGraphCompiler.compile(Arrays.asList(this.delegates))
			.filter(this::accepts)
			.orElse(null);
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
		OpenClKernelTemplate currentTemplate = this.template;
		DensityFunction[] currentDelegates = this.delegates;
		if(currentTemplate == null || currentDelegates == null || slot < 0 || slot >= currentDelegates.length || !OpenClManager.canUse(currentTemplate)) {
			ACTIVE_CYCLE.remove();
			return false;
		}

		OpenClKernelTemplate.Batch batch;
		try {
			batch = currentTemplate.collect(contextProvider, output.length);
		} catch(RuntimeException e) {
			ACTIVE_CYCLE.remove();
			OpenClManager.recordCollectionFailure(currentTemplate, e);
			return false;
		}

		Cycle active = ACTIVE_CYCLE.get();
		if(active != null && active.matches(this, batch, output.length, slot)) {
			return active.consume(slot, delegate, output, contextProvider);
		}
		ACTIVE_CYCLE.remove();

		boolean verify = OpenClManager.requiresVerification(currentTemplate);
		double[] firstExpected = null;
		if(verify) {
			firstExpected = new double[output.length];
			delegate.fillArray(firstExpected, contextProvider);
		}

		double[] accelerated = new double[output.length * currentTemplate.outputCount()];
		if(!OpenClManager.executeUnchecked(currentTemplate, batch, accelerated)) {
			if(firstExpected != null) {
				System.arraycopy(firstExpected, 0, output, 0, output.length);
				return true;
			}
			return false;
		}

		Cycle cycle = new Cycle(this, currentTemplate, batch, output.length, accelerated, verify, slot, firstExpected);
		ACTIVE_CYCLE.set(cycle);
		return cycle.consume(slot, delegate, output, contextProvider);
	}

	String description() {
		OpenClKernelTemplate currentTemplate = this.template;
		return currentTemplate == null ? "none" : currentTemplate.id().substring(0, 12) + "/" + currentTemplate.outputCount();
	}

	private boolean accepts(OpenClKernelTemplate template) {
		return !this.selfContainedOnly || template.inputCount() == 0;
	}

	private final class Mapping {
		private final OpenClDensityGroup group;
		private int count;

		private Mapping(int outputCount) {
			this.group = new OpenClDensityGroup(outputCount, OpenClDensityGroup.this.selfContainedOnly);
		}

		private void add(int slot, DensityFunction delegate) {
			DensityFunction[] mappedDelegates = this.group.delegates;
			if(mappedDelegates == null || slot < 0 || slot >= mappedDelegates.length || mappedDelegates[slot] != null) {
				return;
			}
			mappedDelegates[slot] = delegate;
			this.count++;
			if(this.count == mappedDelegates.length) {
				this.group.template = OpenClGraphCompiler.compile(Arrays.asList(mappedDelegates))
					.filter(this.group::accepts)
					.orElse(null);
			}
		}
	}

	private static final class Cycle {
		private final OpenClDensityGroup group;
		private final OpenClKernelTemplate template;
		private final OpenClKernelTemplate.Batch batch;
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

		private Cycle(OpenClDensityGroup group, OpenClKernelTemplate template, OpenClKernelTemplate.Batch batch, int size,
			double[] accelerated, boolean verification, int firstSlot, @Nullable double[] firstExpected) {
			this.group = group;
			this.template = template;
			this.batch = batch;
			this.size = size;
			this.accelerated = accelerated;
			this.verification = verification;
			this.consumed = new boolean[template.outputCount()];
			this.firstSlot = firstSlot;
			this.firstExpected = firstExpected;
		}

		private boolean matches(OpenClDensityGroup group, OpenClKernelTemplate.Batch batch, int size, int slot) {
			return this.group == group && this.size == size && slot >= 0
				&& slot < this.consumed.length && !this.consumed[slot] && this.batch.matches(batch);
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
