package raccoonman.reterraforged.world.worldgen.opencl;

import static org.lwjgl.opencl.CL10.CL_CONTEXT_PLATFORM;
import static org.lwjgl.opencl.CL10.CL_DEVICE_AVAILABLE;
import static org.lwjgl.opencl.CL10.CL_DEVICE_COMPILER_AVAILABLE;
import static org.lwjgl.opencl.CL10.CL_DEVICE_EXTENSIONS;
import static org.lwjgl.opencl.CL10.CL_DEVICE_NAME;
import static org.lwjgl.opencl.CL10.CL_DEVICE_NOT_FOUND;
import static org.lwjgl.opencl.CL10.CL_DEVICE_TYPE;
import static org.lwjgl.opencl.CL10.CL_DEVICE_TYPE_ACCELERATOR;
import static org.lwjgl.opencl.CL10.CL_DEVICE_TYPE_ALL;
import static org.lwjgl.opencl.CL10.CL_DEVICE_TYPE_CPU;
import static org.lwjgl.opencl.CL10.CL_DEVICE_TYPE_GPU;
import static org.lwjgl.opencl.CL10.CL_DEVICE_VENDOR;
import static org.lwjgl.opencl.CL10.CL_MEM_READ_ONLY;
import static org.lwjgl.opencl.CL10.CL_MEM_WRITE_ONLY;
import static org.lwjgl.opencl.CL10.CL_PROGRAM_BUILD_LOG;
import static org.lwjgl.opencl.CL10.CL_SUCCESS;
import static org.lwjgl.opencl.CL10.clBuildProgram;
import static org.lwjgl.opencl.CL10.clCreateBuffer;
import static org.lwjgl.opencl.CL10.clCreateCommandQueue;
import static org.lwjgl.opencl.CL10.clCreateContext;
import static org.lwjgl.opencl.CL10.clCreateKernel;
import static org.lwjgl.opencl.CL10.clCreateProgramWithSource;
import static org.lwjgl.opencl.CL10.clEnqueueNDRangeKernel;
import static org.lwjgl.opencl.CL10.clEnqueueReadBuffer;
import static org.lwjgl.opencl.CL10.clEnqueueWriteBuffer;
import static org.lwjgl.opencl.CL10.clGetDeviceIDs;
import static org.lwjgl.opencl.CL10.clGetDeviceInfo;
import static org.lwjgl.opencl.CL10.clGetPlatformIDs;
import static org.lwjgl.opencl.CL10.clGetProgramBuildInfo;
import static org.lwjgl.opencl.CL10.clReleaseCommandQueue;
import static org.lwjgl.opencl.CL10.clReleaseContext;
import static org.lwjgl.opencl.CL10.clReleaseKernel;
import static org.lwjgl.opencl.CL10.clReleaseMemObject;
import static org.lwjgl.opencl.CL10.clReleaseProgram;
import static org.lwjgl.opencl.CL10.clSetKernelArg1f;
import static org.lwjgl.opencl.CL10.clSetKernelArg1i;
import static org.lwjgl.opencl.CL10.clSetKernelArg1p;
import static org.lwjgl.opencl.CL12.CL_DEVICE_DOUBLE_FP_CONFIG;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.ReentrantLock;

import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opencl.CL;
import org.lwjgl.opencl.CLContextCallback;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import raccoonman.reterraforged.RTFCommon;
import raccoonman.reterraforged.config.OpenClConfig;

final class OpenClRuntime implements AutoCloseable {
	private static boolean bindingsInitialized;

	private final OpenClConfig config;
	private final long device;
	private final String deviceName;
	private final boolean supportsFp64;
	private final CLContextCallback contextCallback;
	private final long context;
	private final long commandQueue;
	private final ReentrantLock queueLock = new ReentrantLock();
	private final Map<String, ProgramHandle> programs = new HashMap<>();
	private final AtomicInteger failures = new AtomicInteger();
	private final LongAdder busyFallbacks = new LongAdder();

	private volatile boolean enabled = true;
	private long dispatchCount;
	private long sampleCount;
	private long executionNanos;
	private long coordinateBuffer;
	private long coordinateCapacity;
	private long inputBuffer;
	private long inputCapacity;
	private long outputBuffer;
	private long outputCapacity;
	private long quickSeedBuffer;
	private long quickOutputBuffer;
	private long quickOutputCapacity;
	@Nullable
	private ProgramHandle quickProgram;

	private OpenClRuntime(OpenClConfig config, long device, String deviceName, boolean supportsFp64, CLContextCallback contextCallback, long context, long commandQueue) {
		this.config = config;
		this.device = device;
		this.deviceName = deviceName;
		this.supportsFp64 = supportsFp64;
		this.contextCallback = contextCallback;
		this.context = context;
		this.commandQueue = commandQueue;
	}

	@Nullable
	static OpenClRuntime open(OpenClConfig config) {
		CLContextCallback callback = null;
		long context = 0L;
		long queue = 0L;
		try {
			initializeBindings();
			DeviceSelection selection = selectDevice(config.allowCpuDevice());
			if(selection == null) {
				RTFCommon.LOGGER.info("RTF OpenCL is unavailable: no compiler-enabled GPU or accelerator was found");
				return null;
			}

			callback = CLContextCallback.create((errInfo, privateInfo, size, userData) ->
				RTFCommon.LOGGER.error("RTF OpenCL context reported an asynchronous device error")
			);
			try(MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer error = stack.callocInt(1);
				PointerBuffer properties = stack.pointers(CL_CONTEXT_PLATFORM, selection.platform, 0L);
				PointerBuffer devices = stack.pointers(selection.device);
				context = clCreateContext(properties, devices, callback, MemoryUtil.NULL, error);
				check(error.get(0), "clCreateContext");
				queue = clCreateCommandQueue(context, selection.device, 0L, error);
				check(error.get(0), "clCreateCommandQueue");
			}
			OpenClRuntime runtime = new OpenClRuntime(config, selection.device, selection.displayName(), selection.supportsFp64, callback, context, queue);
			RTFCommon.LOGGER.info("RTF OpenCL initialized on {} ({})", selection.name, selection.vendor);
			return runtime;
		} catch(Throwable t) {
			if(queue != 0L) {
				clReleaseCommandQueue(queue);
			}
			if(context != 0L) {
				clReleaseContext(context);
			}
			if(callback != null) {
				callback.close();
			}
			RTFCommon.LOGGER.warn("RTF OpenCL initialization failed; density generation will use CPU backends ({})", t.toString());
			RTFCommon.LOGGER.debug("RTF OpenCL initialization failure", t);
			return null;
		}
	}

	boolean isEnabled() {
		return this.enabled;
	}

	void disable() {
		this.enabled = false;
	}

	boolean tryExecute(OpenClKernelTemplate template, OpenClKernelTemplate.Batch batch, double[] output) {
		if(output.length != batch.size() * template.outputCount()) {
			throw new IllegalArgumentException("OpenCL output size does not match the kernel template");
		}
		if(!this.enabled || !this.supportsFp64) {
			return false;
		}
		if(!this.queueLock.tryLock()) {
			this.busyFallbacks.increment();
			return false;
		}
		try {
			if(!this.enabled) {
				return false;
			}
			long start = System.nanoTime();
			ProgramHandle program = this.programs.computeIfAbsent(template.id(), key -> this.compile(template));
			this.ensureBuffers(batch.coordinates().length, batch.inputs().length, output.length);
			check(clEnqueueWriteBuffer(this.commandQueue, this.coordinateBuffer, true, 0L, batch.coordinates(), null, null), "clEnqueueWriteBuffer(coordinates)");
			if(batch.inputs().length > 0) {
				check(clEnqueueWriteBuffer(this.commandQueue, this.inputBuffer, true, 0L, batch.inputs(), null, null), "clEnqueueWriteBuffer(inputs)");
			}
			check(clSetKernelArg1p(program.kernel, 0, this.coordinateBuffer), "clSetKernelArg(coordinates)");
			check(clSetKernelArg1p(program.kernel, 1, this.inputBuffer), "clSetKernelArg(inputs)");
			check(clSetKernelArg1p(program.kernel, 2, this.outputBuffer), "clSetKernelArg(output)");
			check(clSetKernelArg1i(program.kernel, 3, batch.size()), "clSetKernelArg(count)");
			try(MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer globalSize = stack.pointers(batch.size());
				check(clEnqueueNDRangeKernel(this.commandQueue, program.kernel, 1, null, globalSize, null, null, null), "clEnqueueNDRangeKernel");
			}
			check(clEnqueueReadBuffer(this.commandQueue, this.outputBuffer, true, 0L, output, null, null), "clEnqueueReadBuffer(output)");
			this.dispatchCount++;
			this.sampleCount += output.length;
			this.executionNanos += System.nanoTime() - start;
			return true;
		} catch(RuntimeException e) {
			int failureCount = this.failures.incrementAndGet();
			RTFCommon.LOGGER.error("RTF OpenCL execution failed on {}; using CPU fallback ({}/{})", this.deviceName, failureCount, this.config.failureLimit(), e);
			if(failureCount >= this.config.failureLimit()) {
				this.enabled = false;
				RTFCommon.LOGGER.error("RTF OpenCL disabled for this server session after repeated failures");
			}
			return false;
		} finally {
			this.queueLock.unlock();
		}
	}

	boolean tryFillQuickNoiseTile(int[] seeds, int tileX, int tileY, int tileZ, float chamberBias, float spaghettiWidth, float noodleWidth, float[] output) {
		if(!this.enabled) {
			return false;
		}
		if(!this.queueLock.tryLock()) {
			this.busyFallbacks.increment();
			return false;
		}
		try {
			if(!this.enabled) {
				return false;
			}
			long start = System.nanoTime();
			if(this.quickProgram == null) {
				this.quickProgram = this.compileProgram(QuickNoiseOpenClKernel.SOURCE, QuickNoiseOpenClKernel.KERNEL_NAME, "-cl-std=CL1.2");
			}
			this.ensureQuickBuffers(seeds.length, output.length);
			check(clEnqueueWriteBuffer(this.commandQueue, this.quickSeedBuffer, true, 0L, seeds, null, null), "clEnqueueWriteBuffer(quickSeeds)");
			long kernel = this.quickProgram.kernel;
			check(clSetKernelArg1p(kernel, 0, this.quickSeedBuffer), "clSetKernelArg(quickSeeds)");
			check(clSetKernelArg1p(kernel, 1, this.quickOutputBuffer), "clSetKernelArg(quickOutput)");
			check(clSetKernelArg1i(kernel, 2, tileX), "clSetKernelArg(tileX)");
			check(clSetKernelArg1i(kernel, 3, tileY), "clSetKernelArg(tileY)");
			check(clSetKernelArg1i(kernel, 4, tileZ), "clSetKernelArg(tileZ)");
			check(clSetKernelArg1f(kernel, 5, chamberBias), "clSetKernelArg(chamberBias)");
			check(clSetKernelArg1f(kernel, 6, spaghettiWidth), "clSetKernelArg(spaghettiWidth)");
			check(clSetKernelArg1f(kernel, 7, noodleWidth), "clSetKernelArg(noodleWidth)");
			try(MemoryStack stack = MemoryStack.stackPush()) {
				PointerBuffer globalSize = stack.pointers(output.length);
				check(clEnqueueNDRangeKernel(this.commandQueue, kernel, 1, null, globalSize, null, null, null), "clEnqueueNDRangeKernel(quickNoise)");
			}
			check(clEnqueueReadBuffer(this.commandQueue, this.quickOutputBuffer, true, 0L, output, null, null), "clEnqueueReadBuffer(quickOutput)");
			this.dispatchCount++;
			this.sampleCount += output.length;
			this.executionNanos += System.nanoTime() - start;
			return true;
		} catch(RuntimeException e) {
			int failureCount = this.failures.incrementAndGet();
			RTFCommon.LOGGER.error("RTF quick-noise OpenCL execution failed on {}; using CPU fallback ({}/{})", this.deviceName, failureCount, this.config.failureLimit(), e);
			if(failureCount >= this.config.failureLimit()) {
				this.enabled = false;
				RTFCommon.LOGGER.error("RTF OpenCL disabled for this server session after repeated failures");
			}
			return false;
		} finally {
			this.queueLock.unlock();
		}
	}

	@Override
	public void close() {
		this.enabled = false;
		this.queueLock.lock();
		try {
			this.programs.values().forEach(ProgramHandle::close);
			this.programs.clear();
			if(this.quickProgram != null) {
				this.quickProgram.close();
				this.quickProgram = null;
			}
			this.releaseBuffers();
			clReleaseCommandQueue(this.commandQueue);
			clReleaseContext(this.context);
			this.contextCallback.close();
			RTFCommon.LOGGER.info(
				"RTF OpenCL resources released for {}; dispatched {} batches ({} samples) in {} ms, with {} busy CPU fallbacks",
				this.deviceName,
				this.dispatchCount,
				this.sampleCount,
				this.executionNanos / 1_000_000L,
				this.busyFallbacks.sum()
			);
		} finally {
			this.queueLock.unlock();
		}
	}

	private ProgramHandle compile(OpenClKernelTemplate template) {
		return this.compileProgram(template.source(), "rtf_density", "-cl-std=CL1.2 -cl-opt-disable");
	}

	private ProgramHandle compileProgram(String source, String kernelName, String options) {
		long program = 0L;
		long kernel = 0L;
		try {
			try(MemoryStack stack = MemoryStack.stackPush()) {
				IntBuffer error = stack.callocInt(1);
				program = clCreateProgramWithSource(this.context, source, error);
				check(error.get(0), "clCreateProgramWithSource");
				int buildResult = clBuildProgram(program, this.device, options, null, MemoryUtil.NULL);
				if(buildResult != CL_SUCCESS) {
					String buildLog = programBuildLog(program, this.device);
					throw new OpenClException("clBuildProgram failed with " + buildResult + ": " + buildLog);
				}
				kernel = clCreateKernel(program, kernelName, error);
				check(error.get(0), "clCreateKernel");
			}
			return new ProgramHandle(program, kernel);
		} catch(RuntimeException e) {
			if(kernel != 0L) {
				clReleaseKernel(kernel);
			}
			if(program != 0L) {
				clReleaseProgram(program);
			}
			throw e;
		}
	}

	private void ensureBuffers(int coordinateCount, int inputCount, int outputCount) {
		long coordinateBytes = Math.max(Integer.BYTES, (long)coordinateCount * Integer.BYTES);
		long inputBytes = Math.max(Double.BYTES, (long)inputCount * Double.BYTES);
		long outputBytes = Math.max(Double.BYTES, (long)outputCount * Double.BYTES);
		if(coordinateBytes > this.coordinateCapacity) {
			this.coordinateBuffer = this.resizeBuffer(this.coordinateBuffer, coordinateBytes, CL_MEM_READ_ONLY);
			this.coordinateCapacity = coordinateBytes;
		}
		if(inputBytes > this.inputCapacity) {
			this.inputBuffer = this.resizeBuffer(this.inputBuffer, inputBytes, CL_MEM_READ_ONLY);
			this.inputCapacity = inputBytes;
		}
		if(outputBytes > this.outputCapacity) {
			this.outputBuffer = this.resizeBuffer(this.outputBuffer, outputBytes, CL_MEM_WRITE_ONLY);
			this.outputCapacity = outputBytes;
		}
	}

	private void ensureQuickBuffers(int seedCount, int outputCount) {
		if(this.quickSeedBuffer == 0L) {
			this.quickSeedBuffer = this.resizeBuffer(0L, (long)seedCount * Integer.BYTES, CL_MEM_READ_ONLY);
		}
		long outputBytes = (long)outputCount * Float.BYTES;
		if(outputBytes > this.quickOutputCapacity) {
			this.quickOutputBuffer = this.resizeBuffer(this.quickOutputBuffer, outputBytes, CL_MEM_WRITE_ONLY);
			this.quickOutputCapacity = outputBytes;
		}
	}

	private long resizeBuffer(long current, long size, long flags) {
		if(current != 0L) {
			clReleaseMemObject(current);
		}
		try(MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer error = stack.callocInt(1);
			long buffer = clCreateBuffer(this.context, flags, size, error);
			check(error.get(0), "clCreateBuffer");
			return buffer;
		}
	}

	private void releaseBuffers() {
		if(this.coordinateBuffer != 0L) {
			clReleaseMemObject(this.coordinateBuffer);
			this.coordinateBuffer = 0L;
		}
		if(this.inputBuffer != 0L) {
			clReleaseMemObject(this.inputBuffer);
			this.inputBuffer = 0L;
		}
		if(this.outputBuffer != 0L) {
			clReleaseMemObject(this.outputBuffer);
			this.outputBuffer = 0L;
		}
		if(this.quickSeedBuffer != 0L) {
			clReleaseMemObject(this.quickSeedBuffer);
			this.quickSeedBuffer = 0L;
		}
		if(this.quickOutputBuffer != 0L) {
			clReleaseMemObject(this.quickOutputBuffer);
			this.quickOutputBuffer = 0L;
		}
	}

	private static synchronized void initializeBindings() {
		if(!bindingsInitialized) {
			if(CL.getFunctionProvider() == null) {
				CL.create();
			}
			bindingsInitialized = true;
		}
	}

	@Nullable
	private static DeviceSelection selectDevice(boolean allowCpuDevice) {
		List<DeviceSelection> candidates = new ArrayList<>();
		try(MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer count = stack.callocInt(1);
			int platformResult = clGetPlatformIDs(null, count);
			if(platformResult != CL_SUCCESS || count.get(0) == 0) {
				return null;
			}
			PointerBuffer platforms = stack.mallocPointer(count.get(0));
			check(clGetPlatformIDs(platforms, (IntBuffer)null), "clGetPlatformIDs");
			for(int platformIndex = 0; platformIndex < platforms.capacity(); platformIndex++) {
				long platform = platforms.get(platformIndex);
				count.put(0, 0);
				int deviceResult = clGetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, null, count);
				if(deviceResult == CL_DEVICE_NOT_FOUND || count.get(0) == 0) {
					continue;
				}
				check(deviceResult, "clGetDeviceIDs(count)");
				PointerBuffer devices = stack.mallocPointer(count.get(0));
				check(clGetDeviceIDs(platform, CL_DEVICE_TYPE_ALL, devices, (IntBuffer)null), "clGetDeviceIDs");
				for(int deviceIndex = 0; deviceIndex < devices.capacity(); deviceIndex++) {
					long device = devices.get(deviceIndex);
					long type = deviceInfoLong(device, CL_DEVICE_TYPE);
					boolean acceptedType = (type & (CL_DEVICE_TYPE_GPU | CL_DEVICE_TYPE_ACCELERATOR)) != 0L
						|| (allowCpuDevice && (type & CL_DEVICE_TYPE_CPU) != 0L);
					if(!acceptedType || !deviceInfoBoolean(device, CL_DEVICE_AVAILABLE) || !deviceInfoBoolean(device, CL_DEVICE_COMPILER_AVAILABLE)) {
						continue;
					}
					String extensions = deviceInfoString(device, CL_DEVICE_EXTENSIONS);
					boolean supportsFp64 = deviceInfoLong(device, CL_DEVICE_DOUBLE_FP_CONFIG) != 0L && extensions.contains("cl_khr_fp64");
					String name = deviceInfoString(device, CL_DEVICE_NAME);
					String vendor = deviceInfoString(device, CL_DEVICE_VENDOR);
					int score = ((type & CL_DEVICE_TYPE_GPU) != 0L ? 300 : (type & CL_DEVICE_TYPE_ACCELERATOR) != 0L ? 200 : 100) + (supportsFp64 ? 10 : 0);
					candidates.add(new DeviceSelection(platform, device, name, vendor, supportsFp64, score));
				}
			}
		}
		return candidates.stream().max(Comparator.comparingInt(DeviceSelection::score)).orElse(null);
	}

	private static boolean deviceInfoBoolean(long device, int parameter) {
		try(MemoryStack stack = MemoryStack.stackPush()) {
			IntBuffer value = stack.callocInt(1);
			check(clGetDeviceInfo(device, parameter, value, null), "clGetDeviceInfo(boolean)");
			return value.get(0) != 0;
		}
	}

	private static long deviceInfoLong(long device, int parameter) {
		try(MemoryStack stack = MemoryStack.stackPush()) {
			LongBuffer value = stack.callocLong(1);
			check(clGetDeviceInfo(device, parameter, value, null), "clGetDeviceInfo(long)");
			return value.get(0);
		}
	}

	private static String deviceInfoString(long device, int parameter) {
		try(MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer size = stack.callocPointer(1);
			check(clGetDeviceInfo(device, parameter, (ByteBuffer)null, size), "clGetDeviceInfo(size)");
			ByteBuffer value = stack.malloc((int)size.get(0));
			check(clGetDeviceInfo(device, parameter, value, null), "clGetDeviceInfo(string)");
			return MemoryUtil.memUTF8(value, Math.max(0, value.remaining() - 1));
		}
	}

	private static String programBuildLog(long program, long device) {
		try(MemoryStack stack = MemoryStack.stackPush()) {
			PointerBuffer size = stack.callocPointer(1);
			clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, (ByteBuffer)null, size);
			ByteBuffer value = stack.malloc((int)size.get(0));
			clGetProgramBuildInfo(program, device, CL_PROGRAM_BUILD_LOG, value, null);
			return MemoryUtil.memUTF8(value, Math.max(0, value.remaining() - 1)).trim();
		}
	}

	private static void check(int result, String operation) {
		if(result != CL_SUCCESS) {
			throw new OpenClException(operation + " failed with OpenCL error " + result);
		}
	}

	private record DeviceSelection(long platform, long device, String name, String vendor, boolean supportsFp64, int score) {
		private String displayName() {
			return this.vendor + " " + this.name;
		}
	}

	private record ProgramHandle(long program, long kernel) {
		private void close() {
			clReleaseKernel(this.kernel);
			clReleaseProgram(this.program);
		}
	}

	private static final class OpenClException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		private OpenClException(String message) {
			super(message);
		}
	}
}
