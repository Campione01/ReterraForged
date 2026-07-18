package raccoonman.reterraforged.concurrent.task;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

public abstract class LazyCallable<T> implements Callable<T>, Future<T>, Supplier<T> {
	private final AtomicReference<CompletableFuture<T>> ref = new AtomicReference<>();

	public LazyCallable() {
	}

	@Override
	public T call() {
		CompletableFuture<T> f = this.ref.get();
		if (f == null) {
			CompletableFuture<T> nf = new CompletableFuture<>();
			if (this.ref.compareAndSet(null, nf)) {
				try {
					T created = this.create();
					Objects.requireNonNull(created);
					nf.complete(created);
				} catch (Throwable t) {
					nf.completeExceptionally(t);
					this.ref.compareAndSet(nf, null);
					throw t instanceof RuntimeException r ? r : new RuntimeException(t);
				}
				f = nf;
			} else {
				f = this.ref.get();
			}
		}
		try {
			return f.join();
		} catch (CompletionException e) {
			Throwable c = e.getCause();
			if (c instanceof RuntimeException r) throw r;
			if (c instanceof Error err) throw err;
			throw e;
		}
	}

	@Override
	public boolean cancel(boolean mayInterruptIfRunning) {
		return false;
	}

	@Override
	public boolean isCancelled() {
		return false;
	}

	@Override
	public boolean isDone() {
		CompletableFuture<T> f = this.ref.get();
		return f != null && f.isDone();
	}

	@Override
	public T get() {
		return this.call();
	}

	@Override
	public T get(long timeout, TimeUnit unit) {
		return this.call();
	}

	protected abstract T create();

	public static LazyCallable<Void> adapt(Runnable runnable) {
		return new RunnableAdapter(runnable);
	}

	public static <T> LazyCallable<T> adapt(Callable<T> callable) {
		if (callable instanceof LazyCallable<T> c) {
			return c;
		}
		return new CallableAdapter<>(callable);
	}

	public static <T> LazyCallable<T> adaptComplete(Callable<T> callable) {
		return new CompleteAdapter<>(callable);
	}

	public static class CallableAdapter<T> extends LazyCallable<T> {
		private Callable<T> callable;

		public CallableAdapter(Callable<T> callable) {
			this.callable = callable;
		}

		@Override
		protected T create() {
			try {
				return this.callable.call();
			} catch (Throwable t) {
				t.printStackTrace();
				return null;
			}
		}
	}

	public static class FutureAdapter<T> extends LazyCallable<T> {
		private Future<T> future;

		FutureAdapter(Future<T> future) {
			this.future = future;
		}

		@Override
		public boolean isDone() {
			return this.future.isDone();
		}

		@Override
		protected T create() {
			try {
				return this.future.get();
			} catch (Throwable t) {
				t.printStackTrace();
				return null;
			}
		}
	}

	public static class RunnableAdapter extends LazyCallable<Void> {
		private Runnable runnable;

		RunnableAdapter(Runnable runnable) {
			this.runnable = runnable;
		}

		@Override
		protected Void create() {
			this.runnable.run();
			return null;
		}
	}

	public static class CompleteAdapter<T> extends LazyCallable<T> {
		private Callable<T> callable;

		public CompleteAdapter(Callable<T> callable) {
			this.callable = callable;
		}

		@Override
		protected T create() {
			try {
				return this.callable.call();
			} catch (Exception e) {
				return null;
			}
		}

		@Override
		public boolean isDone() {
			return true;
		}
	}
}
