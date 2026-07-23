package raccoonman.reterraforged.world.worldgen.noise.module;

import java.util.ArrayDeque;

/** A regular or explicitly transformed two-dimensional sample batch. */
public final class NoiseBatch {
	private final int originX;
	private final int originZ;
	private final int width;
	private final int height;
	private final float xScale;
	private final float xOffset;
	private final float zScale;
	private final float zOffset;
	private final float[] xCoordinates;
	private final float[] zCoordinates;
	private final Scratch scratch;
	private final LegacyV2NativeKernels nativeKernels;

	private NoiseBatch(int originX, int originZ, int width, int height, float xScale, float xOffset, float zScale, float zOffset, float[] xCoordinates, float[] zCoordinates, Scratch scratch) {
		this(originX, originZ, width, height, xScale, xOffset, zScale, zOffset, xCoordinates, zCoordinates, scratch, null);
	}

	private NoiseBatch(int originX, int originZ, int width, int height, float xScale, float xOffset, float zScale, float zOffset, float[] xCoordinates, float[] zCoordinates, Scratch scratch, LegacyV2NativeKernels nativeKernels) {
		if(width <= 0 || height <= 0) {
			throw new IllegalArgumentException("Noise batch dimensions must be positive");
		}
		this.originX = originX;
		this.originZ = originZ;
		this.width = width;
		this.height = height;
		this.xScale = xScale;
		this.xOffset = xOffset;
		this.zScale = zScale;
		this.zOffset = zOffset;
		this.xCoordinates = xCoordinates;
		this.zCoordinates = zCoordinates;
		this.scratch = scratch;
		this.nativeKernels = nativeKernels;
	}

	static NoiseBatch regular(int originX, int originZ, int width, int height, float xScale, float xOffset, float zScale, float zOffset) {
		return new NoiseBatch(originX, originZ, width, height, xScale, xOffset, zScale, zOffset, null, null, new Scratch(width * height));
	}

	static NoiseBatch regular(int originX, int originZ, int width, int height, float xScale, float xOffset, float zScale, float zOffset, LegacyV2NativeKernels nativeKernels) {
		return new NoiseBatch(originX, originZ, width, height, xScale, xOffset, zScale, zOffset, null, null, new Scratch(width * height), nativeKernels);
	}

	public NoiseBatch transformed(float[] xCoordinates, float[] zCoordinates) {
		this.validate(xCoordinates);
		this.validate(zCoordinates);
		return new NoiseBatch(0, 0, this.width, this.height, 1.0F, 0.0F, 1.0F, 0.0F, xCoordinates, zCoordinates, this.scratch, this.nativeKernels);
	}

	public int size() {
		return this.width * this.height;
	}

	public float x(int index) {
		this.validateIndex(index);
		return this.xAt(index);
	}

	public float xAt(int index) {
		if(this.xCoordinates != null) {
			return this.xCoordinates[index];
		}
		int sampleX = this.originX + index % this.width;
		return sampleX * this.xScale + this.xOffset;
	}

	public float z(int index) {
		this.validateIndex(index);
		return this.zAt(index);
	}

	public float zAt(int index) {
		if(this.zCoordinates != null) {
			return this.zCoordinates[index];
		}
		int sampleZ = this.originZ + index / this.width;
		return sampleZ * this.zScale + this.zOffset;
	}

	public float[] acquire() {
		return this.scratch.acquire();
	}

	public void release(float[] values) {
		this.validate(values);
		this.scratch.release(values);
	}

	boolean fillNative(Noise noise, int seed, float[] output) {
		this.validate(output);
		return this.nativeKernels != null && this.xCoordinates == null && this.nativeKernels.fill(noise, this, seed, output);
	}

	int originX() {
		return this.originX;
	}

	int originZ() {
		return this.originZ;
	}

	int width() {
		return this.width;
	}

	int height() {
		return this.height;
	}

	float xScale() {
		return this.xScale;
	}

	float xOffset() {
		return this.xOffset;
	}

	float zScale() {
		return this.zScale;
	}

	float zOffset() {
		return this.zOffset;
	}

	void validate(float[] output) {
		if(output.length != this.size()) {
			throw new IllegalArgumentException("Expected " + this.size() + " noise values but received " + output.length);
		}
	}

	private void validateIndex(int index) {
		if(index < 0 || index >= this.size()) {
			throw new IndexOutOfBoundsException(index);
		}
	}

	private static final class Scratch {
		private final int size;
		private final ArrayDeque<float[]> available = new ArrayDeque<>();

		private Scratch(int size) {
			this.size = size;
		}

		private float[] acquire() {
			float[] values = this.available.pollFirst();
			return values == null ? new float[this.size] : values;
		}

		private void release(float[] values) {
			this.available.addFirst(values);
		}
	}
}
