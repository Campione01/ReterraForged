package raccoonman.reterraforged.world.worldgen.noise.module;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;

import org.jetbrains.annotations.Nullable;

import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.world.worldgen.noise.function.Interpolation;
import raccoonman.reterraforged.world.worldgen.quicknoise.QuickNoiseGraph;
import raccoonman.reterraforged.world.worldgen.quicknoise.QuickNoiseGraph.Opcode;
import raccoonman.reterraforged.world.worldgen.quicknoise.QuickNoiseNative;

/** Exact low-level kernels invoked by the owning original RTF primitive node. */
final class LegacyV2NativeKernels implements AutoCloseable {
	private static final String ENABLED_PROPERTY = "reterraforged.legacyV2.nativeKernels";

	private final ConcurrentHashMap<ProgramKey, QuickNoiseNative.Program> programs = new ConcurrentHashMap<>();
	private final LongAdder nativeCalls = new LongAdder();
	private final LongAdder javaFallbacks = new LongAdder();
	private volatile boolean available = true;
	private volatile boolean closed;

	@Nullable
	static LegacyV2NativeKernels createIfEnabled() {
		return Boolean.parseBoolean(System.getProperty(ENABLED_PROPERTY, "false")) ? new LegacyV2NativeKernels() : null;
	}

	boolean fill(Noise noise, NoiseBatch batch, int seed, float[] output) {
		if(this.closed) {
			throw new IllegalStateException("The LEGACY_V2 native kernel cache is closed");
		}
		if(!this.available) {
			this.javaFallbacks.increment();
			return false;
		}
		Transform transform = new Transform(batch.xScale(), batch.xOffset(), batch.zScale(), batch.zOffset());
		QuickNoiseNative.Program program = this.program(noise, transform);
		if(program == null) {
			this.javaFallbacks.increment();
			return false;
		}
		program.fill(seed, batch.originX(), batch.originZ(), batch.width(), batch.height(), output);
		this.nativeCalls.increment();
		return true;
	}

	Snapshot snapshot() {
		return new Snapshot(true, this.available, this.programs.size(), this.nativeCalls.sum(), this.javaFallbacks.sum());
	}

	@Nullable
	private QuickNoiseNative.Program program(Noise noise, Transform transform) {
		ProgramKey key = new ProgramKey(noise, transform);
		QuickNoiseNative.Program existing = this.programs.get(key);
		if(existing != null) {
			return existing;
		}
		synchronized(this.programs) {
			existing = this.programs.get(key);
			if(existing != null) {
				return existing;
			}
			try {
				QuickNoiseNative.Program created = QuickNoiseNative.compileProgram(build(noise, transform).encode());
				this.programs.put(key, created);
				return created;
			} catch(RuntimeException | LinkageError exception) {
				this.available = false;
				RTFCommon.LOGGER.warn("RTF LEGACY_V2 exact native kernels are unavailable; original Java primitives remain active: {}", exception.toString());
				return null;
			}
		}
	}

	private static QuickNoiseGraph build(Noise noise, Transform transform) {
		QuickNoiseGraph.Builder builder = QuickNoiseGraph.builder();
		int x = affine(builder, builder.coordinateX(), transform.xScale, transform.xOffset);
		int z = affine(builder, builder.coordinateZ(), transform.zScale, transform.zOffset);
		int root;
		if(noise instanceof Perlin perlin) {
			root = builder.rtfPrimitive(Opcode.RTF_PERLIN_FIXED, perlin.octaves(), x, z, perlin.seed(), perlin.frequency(), perlin.lacunarity(), perlin.gain(), perlin.min(), perlin.max(), interpolationCode(perlin.interpolation()));
		} else if(noise instanceof Perlin2 perlin) {
			root = builder.rtfPrimitive(Opcode.RTF_PERLIN2_FIXED, perlin.octaves(), x, z, perlin.seed(), perlin.frequency(), perlin.lacunarity(), perlin.gain(), perlin.min(), perlin.max(), interpolationCode(perlin.interpolation()));
		} else if(noise instanceof PerlinRidge ridge) {
			root = builder.rtfPrimitive(Opcode.RTF_PERLIN_RIDGE, ridge.octaves(), x, z, 0L, ridge.frequency(), ridge.lacunarity(), ridge.gain(), ridge.min(), ridge.max(), interpolationCode(ridge.interpolation()));
		} else {
			throw new IllegalArgumentException("Unsupported LEGACY_V2 primitive kernel " + noise.getClass().getName());
		}
		return builder.build(root);
	}

	private static int affine(QuickNoiseGraph.Builder builder, int coordinate, float scale, float offset) {
		if(scale != 1.0F) {
			coordinate = builder.binary(Opcode.MULTIPLY, coordinate, builder.constant(scale));
		}
		if(offset != 0.0F) {
			coordinate = builder.binary(Opcode.ADD, coordinate, builder.constant(offset));
		}
		return coordinate;
	}

	private static float interpolationCode(Interpolation interpolation) {
		return switch(interpolation) {
			case LINEAR -> 0.0F;
			case CURVE3 -> 1.0F;
			case CURVE4 -> 2.0F;
		};
	}

	@Override
	public void close() {
		if(this.closed) {
			return;
		}
		this.closed = true;
		synchronized(this.programs) {
			this.programs.values().forEach(QuickNoiseNative.Program::close);
			this.programs.clear();
		}
	}

	record Snapshot(boolean enabled, boolean available, int programs, long nativeCalls, long javaFallbacks) {
	}

	private record Transform(float xScale, float xOffset, float zScale, float zOffset) {
	}

	private static final class ProgramKey {
		private final Noise noise;
		private final Transform transform;
		private final int hash;

		private ProgramKey(Noise noise, Transform transform) {
			this.noise = noise;
			this.transform = transform;
			this.hash = 31 * System.identityHashCode(noise) + transform.hashCode();
		}

		@Override
		public boolean equals(Object object) {
			return object instanceof ProgramKey other && this.noise == other.noise && this.transform.equals(other.transform);
		}

		@Override
		public int hashCode() {
			return this.hash;
		}
	}
}
