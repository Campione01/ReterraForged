package raccoonman.reterraforged.concurrent.pool;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import java.util.function.Supplier;

import raccoonman.reterraforged.concurrent.Resource;

public class ArrayPool<T> {
	private final int capacity;
	private final IntFunction<T[]> constructor;
	private final ConcurrentLinkedDeque<Item<T>> pool;
	private final AtomicInteger poolSize;

	public ArrayPool(int size, IntFunction<T[]> constructor) {
		this.capacity = size;
		this.constructor = constructor;
		this.pool = new ConcurrentLinkedDeque<>();
		this.poolSize = new AtomicInteger(0);
	}

	public Resource<T[]> get(int arraySize) {
		Item<T> item = this.pool.pollLast();
		if(item != null) {
			this.poolSize.decrementAndGet();
			if(item.get().length >= arraySize) {
				return item.retain();
			}
		}
		return new Item<>(this.constructor.apply(arraySize), this);
	}

	private void restore(Item<T> item) {
		if(this.poolSize.get() < this.capacity) {
			this.pool.addLast(item);
			this.poolSize.incrementAndGet();
		}
	}

	public static <T> ArrayPool<T> of(int size, IntFunction<T[]> constructor) {
		return new ArrayPool<>(size, constructor);
	}

	public static <T> ArrayPool<T> of(int size, Supplier<T> supplier, IntFunction<T[]> constructor) {
		return new ArrayPool<>(size, new ArrayConstructor<>(supplier, constructor));
	}

	public static class Item<T> implements Resource<T[]> {
		private T[] value;
		private ArrayPool<T> pool;
		private boolean released;

		private Item(T[] value, ArrayPool<T> pool) {
			this.released = false;
			this.value = value;
			this.pool = pool;
		}

		@Override
		public T[] get() {
			return this.value;
		}

		@Override
		public boolean isOpen() {
			return !this.released;
		}

		@Override
		public void close() {
			if(!this.released) {
				this.released = true;
				this.pool.restore(this);
			}
		}

		private Item<T> retain() {
			this.released = false;
			return this;
		}
	}

	private static class ArrayConstructor<T> implements IntFunction<T[]> {
		private Supplier<T> element;
		private IntFunction<T[]> array;

		private ArrayConstructor(Supplier<T> element, IntFunction<T[]> array) {
			this.element = element;
			this.array = array;
		}

		@Override
		public T[] apply(int size) {
			T[] t = this.array.apply(size);
			for (int i = 0; i < t.length; ++i) {
				t[i] = this.element.get();
			}
			return t;
		}
	}
}
