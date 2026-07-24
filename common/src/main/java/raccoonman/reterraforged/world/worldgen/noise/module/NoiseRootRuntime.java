package raccoonman.reterraforged.world.worldgen.noise.module;

import java.util.concurrent.atomic.AtomicInteger;

import org.jetbrains.annotations.Nullable;

import raccoonman.reterraforged.concurrent.ThreadPools;

/**
 * Selects one root-noise evaluator for a tile worker. Nested noise nodes always
 * call {@link Noise#compute(float, float, int)} directly.
 */
public final class NoiseRootRuntime {
	private static final ThreadLocal<Session> CURRENT = new ThreadLocal<>();
	private static final AtomicInteger ACTIVE_SESSIONS = new AtomicInteger();
	private static final CoordinateScope NO_COORDINATE_SCOPE = new CoordinateScope(null, null);
	private static final LegacyScope NO_LEGACY_SCOPE = new LegacyScope(null, null);

	private NoiseRootRuntime() {
	}

	static float computeRoot(Noise noise, float x, float z, int seed) {
		if(ACTIVE_SESSIONS.get() == 0) {
			return noise.compute(x, z, seed);
		}
		Session session = current();
		return session != null ? session.computeRoot(noise, x, z, seed) : noise.compute(x, z, seed);
	}

	public static Scope bind(@Nullable Engine engine) {
		Thread owner = Thread.currentThread();
		Session previous = current(owner);
		Session current = engine == null ? null : engine.openSession();
		setCurrent(owner, current);
		boolean counted = current != null;
		if(counted) {
			ACTIVE_SESSIONS.incrementAndGet();
		}
		return new Scope(owner, previous, current, counted);
	}

	public static void prepareSample(int sampleX, int sampleZ) {
		if(ACTIVE_SESSIONS.get() == 0) {
			return;
		}
		Session session = current();
		if(session != null) {
			session.prepareSample(sampleX, sampleZ, 1.0F, 0.0F, 1.0F, 0.0F);
		}
	}

	public static void prepareSample(int sampleX, int sampleZ, float xScale, float xOffset, float zScale, float zOffset) {
		if(ACTIVE_SESSIONS.get() == 0) {
			return;
		}
		Session session = current();
		if(session != null) {
			session.prepareSample(sampleX, sampleZ, xScale, xOffset, zScale, zOffset);
		}
	}

	public static CoordinateScope scaleCoordinates(float scale) {
		if(!Float.isFinite(scale) || scale == 0.0F) {
			throw new IllegalArgumentException("Coordinate scale must be finite and non-zero");
		}
		if(ACTIVE_SESSIONS.get() == 0) {
			return NO_COORDINATE_SCOPE;
		}
		Session session = current();
		return session == null ? NO_COORDINATE_SCOPE : new CoordinateScope(session, session.scaleCoordinates(scale));
	}

	public static LegacyScope legacyCoordinates() {
		if(ACTIVE_SESSIONS.get() == 0) {
			return NO_LEGACY_SCOPE;
		}
		Session session = current();
		return session == null ? NO_LEGACY_SCOPE : new LegacyScope(session, session.legacyCoordinates());
	}

	public interface Engine extends AutoCloseable {
		Session openSession();

		default void afterTileGeneration() {
		}
	}

	public interface Session extends AutoCloseable {
		float computeRoot(Noise noise, float x, float z, int seed);

		void prepareSample(int sampleX, int sampleZ, float xScale, float xOffset, float zScale, float zOffset);

		AutoCloseable scaleCoordinates(float scale);

		AutoCloseable legacyCoordinates();

		@Override
		default void close() {
		}
	}

	public static final class Scope implements AutoCloseable {
		@Nullable
		private Thread owner;
		@Nullable
		private Session previous;
		@Nullable
		private Session current;
		private final boolean counted;
		private boolean closed;

		private Scope(Thread owner, @Nullable Session previous, @Nullable Session current, boolean counted) {
			this.owner = owner;
			this.previous = previous;
			this.current = current;
			this.counted = counted;
		}

		@Override
		public void close() {
			if(this.closed) {
				return;
			}
			this.closed = true;
			Thread owner = this.owner;
			if(owner == null || Thread.currentThread() != owner || current(owner) != this.current) {
				throw new IllegalStateException("Root-noise scopes must close on the creating thread and in reverse order");
			}
			try {
				if(this.current != null) {
					this.current.close();
				}
			} finally {
				setCurrent(owner, this.previous);
				if(this.counted) {
					ACTIVE_SESSIONS.decrementAndGet();
				}
				this.owner = null;
				this.current = null;
				this.previous = null;
			}
		}
	}

	public static final class CoordinateScope implements AutoCloseable {
		@Nullable
		private Session session;
		@Nullable
		private AutoCloseable delegate;

		private CoordinateScope(@Nullable Session session, @Nullable AutoCloseable delegate) {
			this.session = session;
			this.delegate = delegate;
		}

		@Override
		public void close() {
			if(this.session == null) {
				return;
			}
			if(current() != this.session) {
				throw new IllegalStateException("Root-noise coordinate scopes must close on the creating thread");
			}
			NoiseRootRuntime.close(this.delegate, "coordinate");
			this.delegate = null;
			this.session = null;
		}
	}

	public static final class LegacyScope implements AutoCloseable {
		@Nullable
		private Session session;
		@Nullable
		private AutoCloseable delegate;

		private LegacyScope(@Nullable Session session, @Nullable AutoCloseable delegate) {
			this.session = session;
			this.delegate = delegate;
		}

		@Override
		public void close() {
			if(this.session == null) {
				return;
			}
			if(current() != this.session) {
				throw new IllegalStateException("Root-noise legacy scopes must close on the creating thread");
			}
			NoiseRootRuntime.close(this.delegate, "legacy");
			this.delegate = null;
			this.session = null;
		}
	}

	private static void close(@Nullable AutoCloseable closeable, String name) {
		if(closeable == null) {
			return;
		}
		try {
			closeable.close();
		} catch(RuntimeException exception) {
			throw exception;
		} catch(Exception exception) {
			throw new IllegalStateException("Failed to close a root-noise " + name + " scope", exception);
		}
	}

	@Nullable
	private static Session current() {
		return current(Thread.currentThread());
	}

	@Nullable
	private static Session current(Thread thread) {
		if(thread instanceof ThreadPools.WorkerThread worker) {
			Object session = worker.rootNoiseSession();
			if(session == null || session instanceof Session) {
				return (Session) session;
			}
			throw new IllegalStateException("RTF worker contains an invalid root-noise session");
		}
		return CURRENT.get();
	}

	private static void setCurrent(Thread thread, @Nullable Session session) {
		if(thread instanceof ThreadPools.WorkerThread worker) {
			worker.rootNoiseSession(session);
		} else if(session == null) {
			CURRENT.remove();
		} else {
			CURRENT.set(session);
		}
	}
}
