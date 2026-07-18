package raccoonman.reterraforged.world.worldgen.opencl;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.synth.ImprovedNoise;
import net.minecraft.world.level.levelgen.synth.NormalNoise;
import net.minecraft.world.level.levelgen.synth.PerlinNoise;

final class OpenClGraphCompiler {
	private static final String RTF_DENSITY_PACKAGE = "raccoonman.reterraforged.world.worldgen.densityfunction.";
	private static final String NOISE_CHUNK_CLASS_PREFIX = "net.minecraft.world.level.levelgen.NoiseChunk$";

	static Optional<OpenClKernelTemplate> compile(DensityFunction root) {
		return compile(List.of(root));
	}

	static Optional<OpenClKernelTemplate> compile(List<DensityFunction> roots) {
		if(roots.isEmpty()) {
			return Optional.empty();
		}
		try {
			Compiler compiler = new Compiler();
			List<String> results = roots.stream().map(compiler::emit).toList();
			if(compiler.noises.isEmpty()) {
				return Optional.empty();
			}
			return Optional.of(new OpenClKernelTemplate(compiler.source(results), compiler.cpuInputs, results.size()));
		} catch(UnsupportedGraphException e) {
			return Optional.empty();
		}
	}

	private static final class Compiler {
		private final Map<DensityFunction, String> values = new IdentityHashMap<>();
		private final Map<DensityFunction, Integer> inputIndices = new IdentityHashMap<>();
		private final Map<NormalNoise, Integer> noiseIndices = new IdentityHashMap<>();
		private final List<String> statements = new ArrayList<>();
		private final List<DensityFunction> cpuInputs = new ArrayList<>();
		private final List<NormalNoiseSnapshot> noises = new ArrayList<>();

		private String emit(DensityFunction function) {
			String existing = this.values.get(function);
			if(existing != null) {
				return existing;
			}
			if(function instanceof OpenClDensityFunction) {
				throw new UnsupportedGraphException();
			}
			if(function instanceof DensityFunctions.HolderHolder holder) {
				return this.emit(holder.function().value());
			}
			if(function instanceof DensityFunctions.MarkerOrMarked marker) {
				if(marker.type() != DensityFunctions.Marker.Type.Interpolated) {
					return this.emit(marker.wrapped());
				}
			}

			String expression;
			if(function instanceof DensityFunctions.MarkerOrMarked) {
				expression = "inputs[" + this.inputIndex(function) + " * count + gid]";
			} else if(function instanceof DensityFunctions.Constant constant) {
				expression = literal(constant.value());
			} else if(function instanceof DensityFunctions.Noise noise) {
				expression = this.emitNoise(noise.noise(), noise.xzScale(), noise.yScale(), "x", "y", "z");
			} else if(function instanceof DensityFunctions.Clamp clamp) {
				expression = "rtf_clamp(" + this.emit(clamp.input()) + ", " + literal(clamp.minValue()) + ", " + literal(clamp.maxValue()) + ")";
			} else if(function instanceof DensityFunctions.Mapped mapped) {
				expression = this.emitMapped(mapped);
			} else if(function instanceof DensityFunctions.RangeChoice range) {
				String input = this.emit(range.input());
				expression = "((" + input + " >= " + literal(range.minInclusive()) + " && " + input + " < " + literal(range.maxExclusive()) + ") ? "
					+ this.emit(range.whenInRange()) + " : " + this.emit(range.whenOutOfRange()) + ")";
			} else if(function instanceof DensityFunctions.TwoArgumentSimpleFunction binary) {
				expression = this.emitBinary(binary);
			} else if(function instanceof DensityFunctions.WeirdScaledSampler weird) {
				String input = this.emit(weird.input());
				String rarity = weird.rarityValueMapper() == DensityFunctions.WeirdScaledSampler.RarityValueMapper.TYPE1
					? "rtf_rarity_3d(" + input + ")"
					: "rtf_rarity_2d(" + input + ")";
				String sampled = this.emitNoise(weird.noise(), 1.0, 1.0, "x / " + rarity, "y / " + rarity, "z / " + rarity);
				expression = rarity + " * fabs(" + sampled + ")";
			} else if(function instanceof DensityFunctions.Shift shift) {
				expression = "4.0 * " + this.emitNoise(shift.offsetNoise(), 0.25, 0.25, "x", "y", "z");
			} else if(function instanceof DensityFunctions.ShiftA shift) {
				expression = "4.0 * " + this.emitNoise(shift.offsetNoise(), 0.25, 0.25, "x", "0.0", "z");
			} else if(function instanceof DensityFunctions.ShiftB shift) {
				expression = "4.0 * " + this.emitNoise(shift.offsetNoise(), 1.0, 1.0, "z * 0.25", "x * 0.25", "0.0");
			} else if(function instanceof DensityFunctions.ShiftedNoise shifted) {
				String shiftedX = "x * " + literal(shifted.xzScale()) + " + " + this.emit(shifted.shiftX());
				String shiftedY = "y * " + literal(shifted.yScale()) + " + " + this.emit(shifted.shiftY());
				String shiftedZ = "z * " + literal(shifted.xzScale()) + " + " + this.emit(shifted.shiftZ());
				expression = this.emitNoise(shifted.noise(), 1.0, 1.0, shiftedX, shiftedY, shiftedZ);
			} else if(function instanceof DensityFunctions.YClampedGradient gradient) {
				expression = "rtf_clamped_map(y, " + literal(gradient.fromY()) + ", " + literal(gradient.toY()) + ", "
					+ literal(gradient.fromValue()) + ", " + literal(gradient.toValue()) + ")";
			} else if(this.isCpuInput(function)) {
				expression = "inputs[" + this.inputIndex(function) + " * count + gid]";
			} else {
				throw new UnsupportedGraphException();
			}

			String variable = "v" + this.statements.size();
			this.statements.add("double " + variable + " = " + expression + ";");
			this.values.put(function, variable);
			return variable;
		}

		private String emitMapped(DensityFunctions.Mapped mapped) {
			String input = this.emit(mapped.input());
			return switch(mapped.type()) {
				case ABS -> "fabs(" + input + ")";
				case SQUARE -> input + " * " + input;
				case CUBE -> input + " * " + input + " * " + input;
				case HALF_NEGATIVE -> "(" + input + " > 0.0 ? " + input + " : " + input + " * 0.5)";
				case QUARTER_NEGATIVE -> "(" + input + " > 0.0 ? " + input + " : " + input + " * 0.25)";
				case SQUEEZE -> "rtf_squeeze(" + input + ")";
			};
		}

		private String emitBinary(DensityFunctions.TwoArgumentSimpleFunction binary) {
			String first = this.emit(binary.argument1());
			String second = this.emit(binary.argument2());
			return switch(binary.type()) {
				case ADD -> first + " + " + second;
				case MUL -> "(" + first + " == 0.0 ? 0.0 : " + first + " * " + second + ")";
				case MIN -> "rtf_min(" + first + ", " + second + ")";
				case MAX -> "rtf_max(" + first + ", " + second + ")";
			};
		}

		private String emitNoise(DensityFunction.NoiseHolder holder, double xzScale, double yScale, String x, String y, String z) {
			NormalNoise noise = holder.noise();
			if(noise == null) {
				throw new UnsupportedGraphException();
			}
			int index = this.noiseIndices.computeIfAbsent(noise, key -> {
				int next = this.noises.size();
				this.noises.add(NormalNoiseSnapshot.capture(key, next));
				return next;
			});
			return "rtf_normal_" + index + "((" + x + ") * " + literal(xzScale) + ", (" + y + ") * " + literal(yScale) + ", (" + z + ") * " + literal(xzScale) + ")";
		}

		private boolean isCpuInput(DensityFunction function) {
			String name = function.getClass().getName();
			if(name.startsWith(RTF_DENSITY_PACKAGE) || name.startsWith(NOISE_CHUNK_CLASS_PREFIX)) {
				return true;
			}
			return switch(name) {
				case "net.minecraft.world.level.levelgen.DensityFunctions$BlendAlpha",
					"net.minecraft.world.level.levelgen.DensityFunctions$BlendOffset",
					"net.minecraft.world.level.levelgen.DensityFunctions$Spline",
					"net.minecraft.world.level.levelgen.synth.BlendedNoise" -> true;
				default -> false;
			};
		}

		private int inputIndex(DensityFunction function) {
			return this.inputIndices.computeIfAbsent(function, key -> {
				int index = this.cpuInputs.size();
				this.cpuInputs.add(key);
				return index;
			});
		}

		private String source(List<String> results) {
			StringBuilder source = new StringBuilder(65536);
			source.append(PRELUDE);
			for(NormalNoiseSnapshot noise : this.noises) {
				noise.appendSource(source);
			}
			source.append("\n__kernel void rtf_density(__global const int *coordinates, __global const double *inputs, __global double *output, int count) {\n")
				.append("    int gid = (int)get_global_id(0);\n")
				.append("    if (gid >= count) return;\n")
				.append("    int coordinateIndex = gid * 3;\n")
				.append("    double x = (double)coordinates[coordinateIndex];\n")
				.append("    double y = (double)coordinates[coordinateIndex + 1];\n")
				.append("    double z = (double)coordinates[coordinateIndex + 2];\n");
			for(String statement : this.statements) {
				source.append("    ").append(statement).append('\n');
			}
			for(int i = 0; i < results.size(); i++) {
				source.append("    output[").append(i).append(" * count + gid] = ").append(results.get(i)).append(";\n");
			}
			source.append("}\n");
			return source.toString();
		}
	}

	private record NormalNoiseSnapshot(int index, PerlinSnapshot first, PerlinSnapshot second, double valueFactor) {
		private static NormalNoiseSnapshot capture(NormalNoise noise, int index) {
			return new NormalNoiseSnapshot(index, PerlinSnapshot.capture(noise.first), PerlinSnapshot.capture(noise.second), noise.valueFactor);
		}

		private void appendSource(StringBuilder source) {
			this.first.appendSource(source, "rtf_perlin_" + this.index + "_a");
			this.second.appendSource(source, "rtf_perlin_" + this.index + "_b");
			source.append("static inline double rtf_normal_").append(this.index).append("(double x, double y, double z) {\n")
				.append("    double sx = x * ").append(literal(1.0181268882175227)).append(";\n")
				.append("    double sy = y * ").append(literal(1.0181268882175227)).append(";\n")
				.append("    double sz = z * ").append(literal(1.0181268882175227)).append(";\n")
				.append("    return (rtf_perlin_").append(this.index).append("_a(x, y, z) + rtf_perlin_").append(this.index)
				.append("_b(sx, sy, sz)) * ").append(literal(this.valueFactor)).append(";\n}\n");
		}
	}

	private record PerlinSnapshot(List<OctaveSnapshot> octaves, double inputFactor, double valueFactor) {
		private static PerlinSnapshot capture(PerlinNoise noise) {
			List<OctaveSnapshot> octaves = new ArrayList<>(noise.noiseLevels.length);
			for(int i = 0; i < noise.noiseLevels.length; i++) {
				ImprovedNoise level = noise.noiseLevels[i];
				octaves.add(level == null ? null : OctaveSnapshot.capture(level, noise.amplitudes.getDouble(i)));
			}
			return new PerlinSnapshot(octaves, noise.lowestFreqInputFactor, noise.lowestFreqValueFactor);
		}

		private void appendSource(StringBuilder source, String name) {
			for(int i = 0; i < this.octaves.size(); i++) {
				OctaveSnapshot octave = this.octaves.get(i);
				if(octave != null) {
					octave.appendPermutation(source, name + "_p" + i);
				}
			}
			source.append("static inline double ").append(name).append("(double x, double y, double z) {\n")
				.append("    double result = 0.0;\n");
			double frequency = this.inputFactor;
			double amplitudeFactor = this.valueFactor;
			for(int i = 0; i < this.octaves.size(); i++) {
				OctaveSnapshot octave = this.octaves.get(i);
				if(octave != null) {
					source.append("    double n").append(i).append(" = rtf_improved(").append(name).append("_p").append(i)
						.append(", ").append(literal(octave.xo)).append(", ").append(literal(octave.yo)).append(", ").append(literal(octave.zo))
						.append(", rtf_wrap(x * ").append(literal(frequency)).append("), rtf_wrap(y * ").append(literal(frequency))
						.append("), rtf_wrap(z * ").append(literal(frequency)).append("));\n")
						.append("    result = result + (").append(literal(octave.amplitude)).append(" * n").append(i).append(") * ")
						.append(literal(amplitudeFactor)).append(";\n");
				}
				frequency *= 2.0;
				amplitudeFactor /= 2.0;
			}
			source.append("    return result;\n}\n");
		}
	}

	private record OctaveSnapshot(byte[] permutation, double xo, double yo, double zo, double amplitude) {
		private static OctaveSnapshot capture(ImprovedNoise noise, double amplitude) {
			return new OctaveSnapshot(noise.p.clone(), noise.xo, noise.yo, noise.zo, amplitude);
		}

		private void appendPermutation(StringBuilder source, String name) {
			source.append("__constant uchar ").append(name).append("[256] = {");
			for(int i = 0; i < this.permutation.length; i++) {
				if(i > 0) {
					source.append(',');
				}
				source.append(Byte.toUnsignedInt(this.permutation[i]));
			}
			source.append("};\n");
		}
	}

	private static String literal(double value) {
		return "rtf_bits((ulong)0x" + Long.toUnsignedString(Double.doubleToRawLongBits(value), 16) + "UL)";
	}

	private static final String PRELUDE = """
		#pragma OPENCL EXTENSION cl_khr_fp64 : enable
		#pragma OPENCL FP_CONTRACT OFF

		__constant int rtf_gradient[48] = {
		    1,1,0, -1,1,0, 1,-1,0, -1,-1,0,
		    1,0,1, -1,0,1, 1,0,-1, -1,0,-1,
		    0,1,1, 0,-1,1, 0,1,-1, 0,-1,-1,
		    1,1,0, 0,-1,1, -1,1,0, 0,-1,-1
		};

		static inline double rtf_bits(ulong bits) {
		    return as_double(bits);
		}

		static inline double rtf_min(double a, double b) {
		    if (isnan(a)) return a;
		    if (isnan(b)) return b;
		    if (a == 0.0 && b == 0.0) return signbit(a) ? a : b;
		    return a < b ? a : b;
		}

		static inline double rtf_max(double a, double b) {
		    if (isnan(a)) return a;
		    if (isnan(b)) return b;
		    if (a == 0.0 && b == 0.0) return signbit(a) ? b : a;
		    return a > b ? a : b;
		}

		static inline double rtf_clamp(double value, double minValue, double maxValue) {
		    return value < minValue ? minValue : rtf_min(value, maxValue);
		}

		static inline double rtf_lerp(double delta, double start, double end) {
		    return start + delta * (end - start);
		}

		static inline double rtf_lerp2(double dx, double dy, double v00, double v10, double v01, double v11) {
		    return rtf_lerp(dy, rtf_lerp(dx, v00, v10), rtf_lerp(dx, v01, v11));
		}

		static inline double rtf_lerp3(double dx, double dy, double dz, double v000, double v100, double v010, double v110, double v001, double v101, double v011, double v111) {
		    return rtf_lerp(dz, rtf_lerp2(dx, dy, v000, v100, v010, v110), rtf_lerp2(dx, dy, v001, v101, v011, v111));
		}

		static inline double rtf_smooth(double value) {
		    return ((value * value) * value) * (value * (value * 6.0 - 15.0) + 10.0);
		}

		static inline double rtf_grad(int hash, double x, double y, double z) {
		    int index = (hash & 15) * 3;
		    return ((double)rtf_gradient[index] * x + (double)rtf_gradient[index + 1] * y) + (double)rtf_gradient[index + 2] * z;
		}

		static inline int rtf_perm(__constant const uchar *permutation, int index) {
		    return (int)permutation[index & 255];
		}

		static inline double rtf_improved(__constant const uchar *permutation, double xo, double yo, double zo, double x, double y, double z) {
		    double px = x + xo;
		    double py = y + yo;
		    double pz = z + zo;
		    int ix = (int)floor(px);
		    int iy = (int)floor(py);
		    int iz = (int)floor(pz);
		    double fx = px - (double)ix;
		    double fy = py - (double)iy;
		    double fz = pz - (double)iz;
		    int p0 = rtf_perm(permutation, ix);
		    int p1 = rtf_perm(permutation, ix + 1);
		    int p00 = rtf_perm(permutation, p0 + iy);
		    int p01 = rtf_perm(permutation, p0 + iy + 1);
		    int p10 = rtf_perm(permutation, p1 + iy);
		    int p11 = rtf_perm(permutation, p1 + iy + 1);
		    double v000 = rtf_grad(rtf_perm(permutation, p00 + iz), fx, fy, fz);
		    double v100 = rtf_grad(rtf_perm(permutation, p10 + iz), fx - 1.0, fy, fz);
		    double v010 = rtf_grad(rtf_perm(permutation, p01 + iz), fx, fy - 1.0, fz);
		    double v110 = rtf_grad(rtf_perm(permutation, p11 + iz), fx - 1.0, fy - 1.0, fz);
		    double v001 = rtf_grad(rtf_perm(permutation, p00 + iz + 1), fx, fy, fz - 1.0);
		    double v101 = rtf_grad(rtf_perm(permutation, p10 + iz + 1), fx - 1.0, fy, fz - 1.0);
		    double v011 = rtf_grad(rtf_perm(permutation, p01 + iz + 1), fx, fy - 1.0, fz - 1.0);
		    double v111 = rtf_grad(rtf_perm(permutation, p11 + iz + 1), fx - 1.0, fy - 1.0, fz - 1.0);
		    return rtf_lerp3(rtf_smooth(fx), rtf_smooth(fy), rtf_smooth(fz), v000, v100, v010, v110, v001, v101, v011, v111);
		}

		static inline double rtf_wrap(double value) {
		    return value - floor(value / 33554432.0 + 0.5) * 33554432.0;
		}

		static inline double rtf_squeeze(double value) {
		    double clamped = rtf_clamp(value, -1.0, 1.0);
		    return clamped / 2.0 - (clamped * clamped) * clamped / 24.0;
		}

		static inline double rtf_clamped_map(double value, double from, double to, double fromValue, double toValue) {
		    double delta = (value - from) / (to - from);
		    if (delta < 0.0) return fromValue;
		    if (delta > 1.0) return toValue;
		    return rtf_lerp(delta, fromValue, toValue);
		}

		static inline double rtf_rarity_2d(double value) {
		    if (value < -0.75) return 0.5;
		    if (value < -0.5) return 0.75;
		    if (value < 0.5) return 1.0;
		    return value < 0.75 ? 2.0 : 3.0;
		}

		static inline double rtf_rarity_3d(double value) {
		    if (value < -0.5) return 0.75;
		    if (value < 0.0) return 1.0;
		    return value < 0.5 ? 1.5 : 2.0;
		}

		""";

	private static final class UnsupportedGraphException extends RuntimeException {
		private static final long serialVersionUID = 1L;
	}
}
