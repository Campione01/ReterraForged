package raccoonman.reterraforged.world.worldgen.opencl;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

import net.minecraft.world.level.levelgen.DensityFunction;

final class OpenClKernelTemplate {
	private final String id;
	private final String source;
	private final List<DensityFunction> cpuInputs;
	private final int outputCount;

	OpenClKernelTemplate(String source, List<DensityFunction> cpuInputs, int outputCount) {
		this.id = hash(source);
		this.source = source;
		this.cpuInputs = List.copyOf(cpuInputs);
		this.outputCount = outputCount;
	}

	String id() {
		return this.id;
	}

	String source() {
		return this.source;
	}

	int inputCount() {
		return this.cpuInputs.size();
	}

	int outputCount() {
		return this.outputCount;
	}

	Batch collect(DensityFunction.ContextProvider contextProvider, int size) {
		int[] coordinates = new int[size * 3];
		double[] inputs = new double[this.cpuInputs.size() * size];
		for(int i = 0; i < size; i++) {
			DensityFunction.FunctionContext context = contextProvider.forIndex(i);
			int coordinateIndex = i * 3;
			coordinates[coordinateIndex] = context.blockX();
			coordinates[coordinateIndex + 1] = context.blockY();
			coordinates[coordinateIndex + 2] = context.blockZ();
			for(int inputIndex = 0; inputIndex < this.cpuInputs.size(); inputIndex++) {
				inputs[inputIndex * size + i] = this.cpuInputs.get(inputIndex).compute(context);
			}
		}
		return new Batch(coordinates, inputs, size);
	}

	private static String hash(String source) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(source.getBytes(StandardCharsets.UTF_8));
			return HexFormat.of().formatHex(digest);
		} catch(NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	record Batch(int[] coordinates, double[] inputs, int size) {
		boolean matches(Batch other) {
			if(this.size != other.size || !java.util.Arrays.equals(this.coordinates, other.coordinates)
				|| this.inputs.length != other.inputs.length
			) {
				return false;
			}
			for(int i = 0; i < this.inputs.length; i++) {
				if(Double.doubleToRawLongBits(this.inputs[i]) != Double.doubleToRawLongBits(other.inputs[i])) {
					return false;
				}
			}
			return true;
		}
	}
}
