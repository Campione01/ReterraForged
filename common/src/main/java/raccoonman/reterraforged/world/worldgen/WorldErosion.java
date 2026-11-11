package raccoonman.reterraforged.world.worldgen;

import java.lang.ref.SoftReference;
import java.util.concurrent.locks.StampedLock;
import java.util.function.IntFunction;

public class WorldErosion<T> {
	private volatile T value;
	private IntFunction<T> factory;
	private Validator<T> validator;
	private StampedLock lock;
	// After clear: the tables are no longer kept, only remembered while memory allows.
	private boolean cleared;
	private SoftReference<T> late;

	public WorldErosion(IntFunction<T> factory, Validator<T> validator) {
		this.value = null;
		this.lock = new StampedLock();
		this.factory = factory;
		this.validator = validator;
	}

	public T get(int ctx) {
		T value = this.readValue();
		if (this.validate(value, ctx)) {
			return value;
		}
		return this.writeValue(ctx);
	}

	public void clear() {
		long stamp = this.lock.writeLock();
		try {
			this.cleared = true;
			this.value = null;
		} finally {
			this.lock.unlockWrite(stamp);
		}
	}

	private T readValue() {
		long optRead = this.lock.tryOptimisticRead();
		T value = this.value;
		if (!this.lock.validate(optRead)) {
			long stamp = this.lock.readLock();
			try {
				return this.value;
			} finally {
				this.lock.unlockRead(stamp);
			}
		}
		return value;
	}

	private T writeValue(int ctx) {
		long stamp = this.lock.writeLock();
		try {
			if (this.cleared) {
				T late = this.late != null ? this.late.get() : null;
				if (!this.validate(late, ctx)) {
					late = this.factory.apply(ctx);
					this.late = new SoftReference<>(late);
				}
				return late;
			}
			if (this.validate(this.value, ctx)) {
				return this.value;
			}
			return this.value = this.factory.apply(ctx);
		} finally {
			this.lock.unlockWrite(stamp);
		}
	}

	private boolean validate(T value, int ctx) {
		return value != null && this.validator.validate(value, ctx);
	}

	public interface Validator<T> {
		boolean validate(T value, int ctx);
	}
}
