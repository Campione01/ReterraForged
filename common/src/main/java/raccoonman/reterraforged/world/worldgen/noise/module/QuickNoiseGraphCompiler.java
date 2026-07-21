package raccoonman.reterraforged.world.worldgen.noise.module;

import java.util.HashMap;
import java.util.Optional;

import raccoonman.reterraforged.world.worldgen.noise.domain.AddWarp;
import raccoonman.reterraforged.world.worldgen.noise.domain.CompoundWarp;
import raccoonman.reterraforged.world.worldgen.noise.domain.DirectWarp;
import raccoonman.reterraforged.world.worldgen.noise.domain.DirectionWarp;
import raccoonman.reterraforged.world.worldgen.noise.domain.Domain;
import raccoonman.reterraforged.world.worldgen.noise.domain.DomainWarp;
import raccoonman.reterraforged.world.worldgen.noise.function.CellFunction;
import raccoonman.reterraforged.world.worldgen.noise.function.DistanceFunction;
import raccoonman.reterraforged.world.worldgen.noise.function.EdgeFunction;
import raccoonman.reterraforged.world.worldgen.noise.function.Interpolation;
import raccoonman.reterraforged.world.worldgen.quicknoise.QuickNoiseGraph;
import raccoonman.reterraforged.world.worldgen.quicknoise.QuickNoiseGraph.Opcode;

public final class QuickNoiseGraphCompiler {
	private QuickNoiseGraphCompiler() {
	}

	public static Optional<CompiledGraph> compile(Noise noise) {
		return compile(noise, 1.0F, 0.0F, 1.0F, 0.0F);
	}

	public static Optional<CompiledGraph> compile(Noise noise, float xScale, float xOffset, float zScale, float zOffset) {
		try {
			Compiler compiler = new Compiler();
			int root = compiler.compile(noise, compiler.rootCoordinates(xScale, xOffset, zScale, zOffset), 0L);
			return Optional.of(new CompiledGraph(compiler.builder.build(root), noise.minValue(), noise.maxValue()));
		} catch(UnsupportedGraph ignored) {
			return Optional.empty();
		}
	}

	public record CompiledGraph(QuickNoiseGraph graph, float minValue, float maxValue) {
	}

	private static final class Compiler {
		private final QuickNoiseGraph.Builder builder = QuickNoiseGraph.builder();
		private final java.util.Map<CompileKey, Integer> compiled = new HashMap<>();
		private int coordinateX = -1;
		private int coordinateZ = -1;

		private Coordinates rootCoordinates(float xScale, float xOffset, float zScale, float zOffset) {
			if(!Float.isFinite(xScale) || !Float.isFinite(xOffset) || !Float.isFinite(zScale) || !Float.isFinite(zOffset) || xScale == 0.0F || zScale == 0.0F) {
				throw UnsupportedGraph.INSTANCE;
			}
			if(xScale == 1.0F && xOffset == 0.0F && zScale == 1.0F && zOffset == 0.0F) {
				return Coordinates.ROOT;
			}
			return new Coordinates(this.affine(this.coordinateX(Coordinates.ROOT), xScale, xOffset), this.affine(this.coordinateZ(Coordinates.ROOT), zScale, zOffset));
		}

		private int affine(int coordinate, float scale, float offset) {
			if(scale != 1.0F) {
				coordinate = this.builder.binary(Opcode.MULTIPLY, coordinate, this.builder.constant(scale));
			}
			if(offset != 0.0F) {
				coordinate = this.builder.binary(Opcode.ADD, coordinate, this.builder.constant(offset));
			}
			return coordinate;
		}

		private int compile(Noise noise, Coordinates coordinates, long seedOffset) {
			noise = unwrap(noise);
			CompileKey key = new CompileKey(noise, coordinates, seedOffset);
			Integer compiled = this.compiled.get(key);
			if(compiled != null) {
				return compiled;
			}
			int result = this.compileNode(noise, coordinates, seedOffset);
			this.compiled.put(key, result);
			return result;
		}

		private int compileNode(Noise noise, Coordinates coordinates, long seedOffset) {
			if(noise instanceof Constant constant) {
				return this.builder.constant(constant.value());
			}
			if(noise instanceof ShiftSeed shifted) {
				return this.compile(shifted.input(), coordinates, seedOffset + shifted.shift());
			}
			if(noise instanceof Frequency frequency) {
				int scaledX = this.builder.binary(Opcode.MULTIPLY, this.coordinateX(coordinates), this.compile(frequency.xFreq(), coordinates, seedOffset));
				int scaledZ = this.builder.binary(Opcode.MULTIPLY, this.coordinateZ(coordinates), this.compile(frequency.zFreq(), coordinates, seedOffset));
				return this.compile(frequency.input(), new Coordinates(scaledX, scaledZ), seedOffset);
			}
			if(noise instanceof Perlin perlin) {
				return this.rtfPrimitive(Opcode.RTF_PERLIN, perlin.octaves(), coordinates, perlin.seed(), perlin.frequency(), perlin.lacunarity(), perlin.gain(), perlin.min(), perlin.max(), curveCode(perlin.interpolation()));
			}
			if(noise instanceof Perlin2 perlin) {
				return this.rtfPrimitive(Opcode.RTF_PERLIN2, perlin.octaves(), coordinates, perlin.seed(), perlin.frequency(), perlin.lacunarity(), perlin.gain(), perlin.min(), perlin.max(), curveCode(perlin.interpolation()));
			}
			if(noise instanceof Simplex simplex) {
				return this.rtfPrimitive(Opcode.RTF_SIMPLEX, simplex.octaves(), coordinates, seedOffset, simplex.frequency(), simplex.lacunarity(), simplex.gain(), simplex.min(), simplex.max(), 0.0F);
			}
			if(noise instanceof Simplex2 simplex) {
				return this.rtfPrimitive(Opcode.RTF_SIMPLEX2, simplex.octaves(), coordinates, seedOffset, simplex.frequency(), simplex.lacunarity(), simplex.gain(), simplex.min(), simplex.max(), 0.0F);
			}
			if(noise instanceof PerlinRidge ridge) {
				return this.rtfPrimitive(Opcode.RTF_PERLIN_RIDGE, ridge.octaves(), coordinates, seedOffset, ridge.frequency(), ridge.lacunarity(), ridge.gain(), ridge.min(), ridge.max(), curveCode(ridge.interpolation()));
			}
			if(noise instanceof SimplexRidge ridge) {
				return this.rtfPrimitive(Opcode.RTF_SIMPLEX_RIDGE, ridge.octaves(), coordinates, seedOffset, ridge.frequency(), ridge.lacunarity(), ridge.gain(), ridge.min(), ridge.max(), 0.0F);
			}
			if(noise instanceof Billow billow) {
				return this.rtfPrimitive(Opcode.RTF_BILLOW, billow.octaves(), coordinates, seedOffset, billow.frequency(), billow.lacunarity(), billow.gain(), billow.min(), billow.max(), curveCode(billow.interpolation()));
			}
			if(noise instanceof Cubic cubic) {
				return this.rtfPrimitive(Opcode.RTF_CUBIC, cubic.octaves(), coordinates, seedOffset, cubic.frequency(), cubic.lacunarity(), cubic.gain(), cubic.minValue(), cubic.maxValue(), 0.0F);
			}
			if(noise instanceof White white) {
				return this.rtfPrimitive(Opcode.RTF_WHITE, 1, coordinates, seedOffset, white.frequency(), 0.0F, 0.0F, 0.0F, 1.0F, 0.0F);
			}
			if(noise instanceof Sin sin) {
				int x = this.builder.binary(Opcode.MULTIPLY, this.coordinateX(coordinates), this.builder.constant(sin.frequency()));
				int z = this.builder.binary(Opcode.MULTIPLY, this.coordinateZ(coordinates), this.builder.constant(sin.frequency()));
				int sinX = this.builder.unary(Opcode.RTF_SIN, x);
				int sinZ = this.builder.unary(Opcode.RTF_SIN, z);
				int value = this.builder.ternary(Opcode.LERP, this.compile(sin.alpha(), coordinates, seedOffset), sinX, sinZ);
				return this.map(value, -1.0F, 1.0F, 0.0F, 1.0F);
			}
			if(noise instanceof Worley worley) {
				if(worley.cellFunction() == CellFunction.NOISE_LOOKUP) {
					throw UnsupportedGraph.INSTANCE;
				}
				return this.rtfPrimitive(Opcode.RTF_WORLEY, 1, coordinates, seedOffset, worley.frequency(), worley.distance(), cellFunctionCode(worley.cellFunction()), distanceFunctionCode(worley.distanceFunction()), worley.min(), worley.max());
			}
			if(noise instanceof WorleyEdge worley) {
				return this.rtfPrimitive(Opcode.RTF_WORLEY_EDGE, 1, coordinates, seedOffset, worley.frequency(), worley.distance(), edgeFunctionCode(worley.edgeFunction()), distanceFunctionCode(worley.distanceFunction()), worley.edgeFunction().min(), worley.edgeFunction().max());
			}
			if(noise instanceof Add add) {
				return this.binary(Opcode.ADD, add.input1(), add.input2(), coordinates, seedOffset);
			}
			if(noise instanceof Multiply multiply) {
				return this.binary(Opcode.MULTIPLY, multiply.input1(), multiply.input2(), coordinates, seedOffset);
			}
			if(noise instanceof Min min) {
				return this.binary(Opcode.MIN, min.input1(), min.input2(), coordinates, seedOffset);
			}
			if(noise instanceof Max max) {
				return this.binary(Opcode.MAX, max.input1(), max.input2(), coordinates, seedOffset);
			}
			if(noise instanceof Abs abs) {
				return this.builder.unary(Opcode.ABS, this.compile(abs.input(), coordinates, seedOffset));
			}
			if(noise instanceof Power power) {
				return this.builder.unary(Opcode.POW, this.compile(power.input(), coordinates, seedOffset), power.power(), 0.0F, 0.0F, 0.0F);
			}
			if(noise instanceof PowerCurve curve) {
				int input = this.compile(curve.input(), coordinates, seedOffset);
				int centered = this.subtract(input, this.builder.constant(curve.mid()));
				int curved = this.builder.unary(Opcode.SIGNED_POW, centered, curve.power(), 0.0F, 0.0F, 0.0F);
				curved = this.builder.binary(Opcode.ADD, curved, this.builder.constant(curve.mid()));
				curved = this.builder.unary(Opcode.CLAMP, curved, curve.min(), curve.max(), 0.0F, 0.0F);
				return this.map(curved, curve.min(), curve.max(), 0.0F, 1.0F);
			}
			if(noise instanceof Gradient gradient) {
				return this.gradient(gradient, coordinates, seedOffset);
			}
			if(noise instanceof Terrace terrace) {
				return this.terrace(terrace, coordinates, seedOffset);
			}
			if(noise instanceof AdvancedTerrace terrace) {
				return this.advancedTerrace(terrace, coordinates, seedOffset);
			}
			if(noise instanceof Clamp clamp) {
				int input = this.compile(clamp.input(), coordinates, seedOffset);
				int min = this.compile(clamp.min(), coordinates, seedOffset);
				int max = this.compile(clamp.max(), coordinates, seedOffset);
				return this.builder.binary(Opcode.MIN, this.builder.binary(Opcode.MAX, input, min), max);
			}
			if(noise instanceof Map map) {
				int alpha = this.compile(map.alpha(), coordinates, seedOffset);
				alpha = this.map(alpha, map.alpha().minValue(), map.alpha().maxValue(), 0.0F, 1.0F);
				return this.builder.ternary(Opcode.LERP, alpha, this.compile(map.from(), coordinates, seedOffset), this.compile(map.to(), coordinates, seedOffset));
			}
			if(noise instanceof Invert invert) {
				int input = this.compile(invert.input(), coordinates, seedOffset);
				input = this.builder.unary(Opcode.CLAMP, input, invert.input().minValue(), invert.input().maxValue(), 0.0F, 0.0F);
				return this.subtract(this.builder.constant(invert.input().maxValue()), input);
			}
			if(noise instanceof Curve curve) {
				return this.curve(this.compile(curve.input(), coordinates, seedOffset), curve.curveFunction());
			}
			if(noise instanceof Blend blend) {
				float mid = blend.alpha().minValue() + (blend.alpha().maxValue() - blend.alpha().minValue()) * blend.mid();
				float lower = Math.max(blend.alpha().minValue(), mid - blend.range() * 0.5F);
				float upper = Math.min(blend.alpha().maxValue(), mid + blend.range() * 0.5F);
				int alpha = this.map(this.compile(blend.alpha(), coordinates, seedOffset), lower, upper, 0.0F, 1.0F);
				alpha = this.builder.unary(Opcode.CLAMP, alpha, 0.0F, 1.0F, 0.0F, 0.0F);
				alpha = this.curve(alpha, blend.interpolation());
				return this.builder.ternary(Opcode.LERP, alpha, this.compile(blend.lower(), coordinates, seedOffset), this.compile(blend.upper(), coordinates, seedOffset));
			}
			if(noise instanceof Alpha alpha) {
				return this.builder.ternary(Opcode.LERP, this.compile(alpha.alpha(), coordinates, seedOffset), this.builder.constant(1.0F), this.compile(alpha.input(), coordinates, seedOffset));
			}
			if(noise instanceof Boost boost) {
				return this.builder.unary(Opcode.BOOST, this.compile(boost.input(), coordinates, seedOffset), boost.iterations(), 0.0F, 0.0F, 0.0F);
			}
			if(noise instanceof Steps steps) {
				float count = constant(steps.steps());
				float slopeMin = constant(steps.slopeMin());
				float slopeMax = constant(steps.slopeMax());
				float curve = curveCode(steps.slopeCurve());
				return this.builder.unary(Opcode.STEPS, this.compile(steps.input(), coordinates, seedOffset), count, slopeMin, slopeMax, curve);
			}
			if(noise instanceof Threshold threshold) {
				int selector = this.builder.binary(Opcode.GREATER, this.compile(threshold.input(), coordinates, seedOffset), this.compile(threshold.threshold(), coordinates, seedOffset));
				return this.builder.ternary(Opcode.LERP, selector, this.compile(threshold.lower(), coordinates, seedOffset), this.compile(threshold.upper(), coordinates, seedOffset));
			}
			if(noise instanceof LegacyMoisture moisture) {
				int source = this.compile(moisture.source(), coordinates, seedOffset);
				if(moisture.power() < 2) {
					return source;
				}
				source = this.map(source, 0.0F, 1.0F, -1.0F, 1.0F);
				source = this.builder.unary(Opcode.SIGNED_POW, source, moisture.power(), 0.0F, 0.0F, 0.0F);
				return this.map(source, -1.0F, 1.0F, 0.0F, 1.0F);
			}
			if(noise instanceof LegacyTemperature temperature) {
				int value = this.builder.binary(Opcode.MULTIPLY, this.coordinateZ(coordinates), this.builder.constant(temperature.frequency()));
				value = this.builder.unary(Opcode.RTF_SIN, value);
				value = this.builder.unary(Opcode.CLAMP, value, -1.0F, 1.0F, 0.0F, 0.0F);
				value = this.builder.unary(Opcode.SIGNED_POW, value, temperature.power(), 0.0F, 0.0F, 0.0F);
				return this.map(value, -1.0F, 1.0F, 0.0F, 1.0F);
			}
			if(noise instanceof LinearSpline spline) {
				return this.linearSpline(spline, coordinates, seedOffset);
			}
			if(noise instanceof Line line) {
				return this.line(line, coordinates, seedOffset);
			}
			if(noise instanceof Warp warp) {
				int x = this.coordinateX(coordinates);
				int z = this.coordinateZ(coordinates);
				Coordinates offset = this.compileDomainOffset(warp.domain(), coordinates, seedOffset);
				Coordinates warped = new Coordinates(this.builder.binary(Opcode.ADD, x, offset.x), this.builder.binary(Opcode.ADD, z, offset.z));
				return this.compile(warp.input(), warped, seedOffset);
			}
			throw UnsupportedGraph.INSTANCE;
		}

		private int binary(Opcode opcode, Noise first, Noise second, Coordinates coordinates, long seedOffset) {
			return this.builder.binary(opcode, this.compile(first, coordinates, seedOffset), this.compile(second, coordinates, seedOffset));
		}

		private int gradient(Gradient gradient, Coordinates coordinates, long seedOffset) {
			int input = this.compile(gradient.input(), coordinates, seedOffset);
			int lower = this.compile(gradient.lower(), coordinates, seedOffset);
			int upper = this.compile(gradient.upper(), coordinates, seedOffset);
			int strength = this.compile(gradient.strength(), coordinates, seedOffset);
			int one = this.builder.constant(1.0F);
			int belowPower = this.subtract(one, strength);
			int alpha = this.subtract(one, this.divide(this.subtract(input, lower), this.subtract(upper, lower)));
			int middlePower = this.subtract(one, this.builder.binary(Opcode.MULTIPLY, strength, alpha));
			int middle = this.builder.binary(Opcode.POW_DYNAMIC, input, middlePower);
			int below = this.builder.binary(Opcode.POW_DYNAMIC, input, belowPower);
			int result = this.builder.ternary(Opcode.LERP, this.builder.binary(Opcode.GREATER, lower, input), middle, below);
			return this.builder.ternary(Opcode.LERP, this.builder.binary(Opcode.GREATER, input, upper), result, input);
		}

		private int terrace(Terrace terrace, Coordinates coordinates, long seedOffset) {
			int stepCount = terrace.stepCount();
			if(stepCount < 2) {
				throw UnsupportedGraph.INSTANCE;
			}
			int input = this.builder.unary(Opcode.CLAMP, this.compile(terrace.input(), coordinates, seedOffset), 0.0F, 0.999999F, 0.0F, 0.0F);
			int rampNoise = this.compile(terrace.ramp(), coordinates, seedOffset);
			int cliffNoise = this.compile(terrace.cliff(), coordinates, seedOffset);
			int rampHeight = this.compile(terrace.rampHeight(), coordinates, seedOffset);
			float spacing = (terrace.input().maxValue() - terrace.input().minValue()) / (stepCount - 1);
			float blend = spacing * terrace.blendRange();
			float bound = (spacing - blend) * 0.5F;
			int result = this.terraceSegment(input, rampNoise, cliffNoise, rampHeight, 0.0F, -bound, bound, spacing);
			for(int index = 1; index < stepCount; index++) {
				float value = index * spacing;
				int candidate = index == stepCount - 1
					? this.builder.constant(value)
					: this.terraceSegment(input, rampNoise, cliffNoise, rampHeight, value, value - bound, value + bound, value + spacing);
				int selector = this.builder.binary(Opcode.GREATER_EQUAL, input, this.builder.constant((float) index / stepCount));
				result = this.builder.ternary(Opcode.LERP, selector, result, candidate);
			}
			return result;
		}

		private int terraceSegment(int input, int rampNoise, int cliffNoise, int rampHeight, float value, float lower, float upper, float next) {
			int one = this.builder.constant(1.0F);
			int alpha = this.divide(this.subtract(input, this.builder.constant(lower)), this.builder.constant(upper - lower));
			alpha = this.builder.unary(Opcode.CLAMP, alpha, 0.0F, 1.0F, 0.0F, 0.0F);
			int ramp = this.subtract(one, this.builder.binary(Opcode.MULTIPLY, rampNoise, this.builder.constant(0.5F)));
			int rampAlpha = this.divide(this.subtract(alpha, ramp), this.subtract(one, ramp));
			rampAlpha = this.builder.unary(Opcode.CLAMP, rampAlpha, 0.0F, 1.0F, 0.0F, 0.0F);
			int result = this.builder.binary(Opcode.ADD, this.builder.constant(value), this.builder.binary(Opcode.MULTIPLY, this.builder.constant(next - value), this.builder.binary(Opcode.MULTIPLY, rampAlpha, rampHeight)));
			int cliff = this.subtract(one, this.builder.binary(Opcode.MULTIPLY, cliffNoise, this.builder.constant(0.5F)));
			int cliffAlpha = this.divide(this.subtract(alpha, cliff), this.subtract(one, cliff));
			cliffAlpha = this.builder.unary(Opcode.CLAMP, cliffAlpha, 0.0F, 1.0F, 0.0F, 0.0F);
			return this.builder.ternary(Opcode.LERP, cliffAlpha, result, this.builder.constant(next));
		}

		private int advancedTerrace(AdvancedTerrace terrace, Coordinates coordinates, long seedOffset) {
			if(terrace.steps() <= 0 || terrace.octaves() < 0) {
				throw UnsupportedGraph.INSTANCE;
			}
			int source = this.compile(terrace.source(), coordinates, seedOffset);
			int modulation = this.compile(terrace.modulation(), coordinates, seedOffset);
			int mask = this.compile(terrace.mask(), coordinates, seedOffset);
			int slope = this.compile(terrace.slope(), coordinates, seedOffset);
			int result = source;
			for(int octave = 1; octave <= terrace.octaves(); octave++) {
				int steps = this.builder.constant((float) terrace.steps() * octave);
				int stepped = this.divide(this.builder.unary(Opcode.ROUND, this.builder.binary(Opcode.MULTIPLY, result, steps)), steps);
				result = this.builder.binary(Opcode.ADD, stepped, this.builder.binary(Opcode.MULTIPLY, this.subtract(source, stepped), slope));
			}
			float divisor = terrace.source().maxValue() + terrace.modulation().maxValue();
			result = this.divide(this.builder.binary(Opcode.ADD, result, modulation), this.builder.constant(divisor));
			int alpha = this.divide(this.subtract(source, this.builder.constant(terrace.blendMin())), this.builder.constant(terrace.blendMax() - terrace.blendMin()));
			alpha = this.builder.ternary(Opcode.LERP, this.builder.binary(Opcode.GREATER, source, this.builder.constant(terrace.blendMax())), alpha, this.builder.constant(1.0F));
			alpha = this.builder.binary(Opcode.MULTIPLY, alpha, mask);
			int terraced = this.builder.ternary(Opcode.LERP, alpha, source, result);
			return this.builder.ternary(Opcode.LERP, this.builder.binary(Opcode.GREATER, source, this.builder.constant(terrace.blendMin())), source, terraced);
		}

		private int linearSpline(LinearSpline spline, Coordinates coordinates, long seedOffset) {
			if(spline.points().isEmpty()) {
				throw UnsupportedGraph.INSTANCE;
			}
			int input = this.compile(spline.input(), coordinates, seedOffset);
			int result = this.compile(spline.points().getFirst().getSecond(), coordinates, seedOffset);
			for(int index = 0; index < spline.points().size() - 1; index++) {
				var start = spline.points().get(index);
				var end = spline.points().get(index + 1);
				int alpha = this.divide(this.subtract(input, this.builder.constant(start.getFirst())), this.builder.constant(end.getFirst() - start.getFirst()));
				alpha = this.builder.unary(Opcode.CLAMP, alpha, 0.0F, 1.0F, 0.0F, 0.0F);
				int candidate = this.builder.ternary(Opcode.LERP, alpha, this.compile(start.getSecond(), coordinates, seedOffset), this.compile(end.getSecond(), coordinates, seedOffset));
				int selector = this.builder.binary(Opcode.GREATER, input, this.builder.constant(start.getFirst()));
				result = this.builder.ternary(Opcode.LERP, selector, result, candidate);
			}
			return result;
		}

		private int line(Line line, Coordinates coordinates, long seedOffset) {
			if(line.lengthSq() == 0.0F) {
				throw UnsupportedGraph.INSTANCE;
			}
			int x = this.coordinateX(coordinates);
			int z = this.coordinateZ(coordinates);
			int d1 = this.distanceSquared(x, z, line.x1(), line.z1());
			int d2 = this.distanceSquared(x, z, line.x2(), line.z2());
			int fadeIn = this.fadeFactor(d1, this.compile(line.fadeIn(), coordinates, seedOffset), line.lengthSq());
			int fadeOut = this.fadeFactor(d2, this.compile(line.fadeOut(), coordinates, seedOffset), line.lengthSq());
			int width = this.builder.binary(Opcode.MULTIPLY, fadeIn, fadeOut);
			width = this.builder.binary(Opcode.MULTIPLY, width, this.builder.binary(Opcode.GREATER, d1, this.builder.constant(0.0F)));
			width = this.builder.binary(Opcode.MULTIPLY, width, this.builder.binary(Opcode.GREATER, d2, this.builder.constant(0.0F)));

			int relX = this.subtract(x, this.builder.constant(line.x1()));
			int relZ = this.subtract(z, this.builder.constant(line.z1()));
			int dot = this.builder.binary(Opcode.ADD, this.builder.binary(Opcode.MULTIPLY, relX, this.builder.constant(line.dx())), this.builder.binary(Opcode.MULTIPLY, relZ, this.builder.constant(line.dz())));
			int alpha = this.builder.unary(Opcode.CLAMP, this.divide(dot, this.builder.constant(line.lengthSq())), 0.0F, 1.0F, 0.0F, 0.0F);
			int closestX = this.builder.binary(Opcode.ADD, this.builder.constant(line.x1()), this.builder.binary(Opcode.MULTIPLY, alpha, this.builder.constant(line.dx())));
			int closestZ = this.builder.binary(Opcode.ADD, this.builder.constant(line.z1()), this.builder.binary(Opcode.MULTIPLY, alpha, this.builder.constant(line.dz())));
			int distance = this.distanceSquared(x, z, closestX, closestZ);
			int radius = this.builder.binary(Opcode.MULTIPLY, this.compile(line.radiusSq(), coordinates, seedOffset), width);
			int value = this.subtract(this.builder.constant(1.0F), this.divide(distance, radius));
			if(line.feather() != 0.0F) {
				int feather = this.builder.binary(Opcode.ADD, this.builder.constant(line.featherBias()), this.builder.binary(Opcode.MULTIPLY, width, this.builder.constant(line.feather())));
				value = this.builder.binary(Opcode.MULTIPLY, value, feather);
			}
			int inside = this.builder.binary(Opcode.GREATER_EQUAL, radius, distance);
			return this.builder.ternary(Opcode.LERP, inside, this.builder.constant(0.0F), value);
		}

		private int fadeFactor(int distanceSquared, int fade, float lengthSquared) {
			int distance = this.builder.binary(Opcode.MULTIPLY, fade, this.builder.constant(lengthSquared));
			int candidate = this.builder.binary(Opcode.MIN, this.builder.constant(1.0F), this.divide(distanceSquared, distance));
			return this.builder.ternary(Opcode.LERP, this.builder.binary(Opcode.GREATER, fade, this.builder.constant(0.0F)), this.builder.constant(1.0F), candidate);
		}

		private int distanceSquared(int x, int z, float pointX, float pointZ) {
			return this.distanceSquared(x, z, this.builder.constant(pointX), this.builder.constant(pointZ));
		}

		private int distanceSquared(int x, int z, int pointX, int pointZ) {
			int dx = this.subtract(x, pointX);
			int dz = this.subtract(z, pointZ);
			return this.builder.binary(Opcode.ADD, this.builder.binary(Opcode.MULTIPLY, dx, dx), this.builder.binary(Opcode.MULTIPLY, dz, dz));
		}

		private int subtract(int first, int second) {
			return this.builder.binary(Opcode.ADD, first, this.builder.binary(Opcode.MULTIPLY, second, this.builder.constant(-1.0F)));
		}

		private int divide(int numerator, int denominator) {
			return this.builder.binary(Opcode.DIVIDE, numerator, denominator);
		}

		private Coordinates compileDomainOffset(Domain domain, Coordinates coordinates, long seedOffset) {
			if(domain instanceof DirectWarp) {
				int zero = this.builder.constant(0.0F);
				return new Coordinates(zero, zero);
			}
			if(domain instanceof DomainWarp warp) {
				int distance = this.compile(warp.distance(), coordinates, seedOffset);
				return new Coordinates(
					this.builder.binary(Opcode.MULTIPLY, this.compile(warp.mappedX(), coordinates, seedOffset), distance),
					this.builder.binary(Opcode.MULTIPLY, this.compile(warp.mappedZ(), coordinates, seedOffset), distance)
				);
			}
			if(domain instanceof AddWarp add) {
				Coordinates first = this.compileDomainOffset(add.input1(), coordinates, seedOffset);
				Coordinates second = this.compileDomainOffset(add.input2(), coordinates, seedOffset);
				return new Coordinates(this.builder.binary(Opcode.ADD, first.x, second.x), this.builder.binary(Opcode.ADD, first.z, second.z));
			}
			if(domain instanceof CompoundWarp compound) {
				Coordinates firstOffset = this.compileDomainOffset(compound.input1(), coordinates, seedOffset);
				Coordinates first = new Coordinates(
					this.builder.binary(Opcode.ADD, this.coordinateX(coordinates), firstOffset.x),
					this.builder.binary(Opcode.ADD, this.coordinateZ(coordinates), firstOffset.z)
				);
				return this.compileDomainOffset(compound.input2(), first, seedOffset);
			}
			if(domain instanceof DirectionWarp direction) {
				int angle = this.builder.binary(Opcode.MULTIPLY, this.compile(direction.direction(), coordinates, seedOffset), this.builder.constant((float) (Math.PI * 2.0D)));
				int strength = this.compile(direction.strength(), coordinates, seedOffset);
				return new Coordinates(
					this.builder.binary(Opcode.MULTIPLY, this.builder.unary(Opcode.RTF_SIN, angle), strength),
					this.builder.binary(Opcode.MULTIPLY, this.builder.unary(Opcode.RTF_COS, angle), strength)
				);
			}
			throw UnsupportedGraph.INSTANCE;
		}

		private int rtfPrimitive(Opcode opcode, int octaves, Coordinates coordinates, long seedOffset, float param0, float param1, float param2, float param3, float param4, float param5) {
			return this.builder.rtfPrimitive(opcode, octaves, this.coordinateX(coordinates), this.coordinateZ(coordinates), seedOffset, param0, param1, param2, param3, param4, param5);
		}

		private int map(int input, float inputMin, float inputMax, float outputMin, float outputMax) {
			return this.builder.unary(Opcode.MAP, input, inputMin, inputMax, outputMin, outputMax);
		}

		private int curve(int input, Object curve) {
			if(curve == Interpolation.LINEAR) {
				return input;
			}
			if(curve == Interpolation.CURVE3) {
				return this.builder.unary(Opcode.CURVE3, input);
			}
			if(curve == Interpolation.CURVE4) {
				return this.builder.unary(Opcode.CURVE5, input);
			}
			throw UnsupportedGraph.INSTANCE;
		}

		private int coordinateX(Coordinates coordinates) {
			if(coordinates.x >= 0) {
				return coordinates.x;
			}
			if(this.coordinateX < 0) {
				this.coordinateX = this.builder.coordinateX();
			}
			return this.coordinateX;
		}

		private int coordinateZ(Coordinates coordinates) {
			if(coordinates.z >= 0) {
				return coordinates.z;
			}
			if(this.coordinateZ < 0) {
				this.coordinateZ = this.builder.coordinateZ();
			}
			return this.coordinateZ;
		}
	}

	private static Noise unwrap(Noise noise) {
		while(true) {
			if(noise instanceof Noises.HolderHolder holder) {
				noise = holder.holder().value();
				continue;
			}
			if(noise instanceof Cache2d cache) {
				noise = cache.noise();
				continue;
			}
			if(noise instanceof Cache2d.Cached cache) {
				noise = cache.noise;
				continue;
			}
			return noise;
		}
	}

	private static float constant(Noise noise) {
		noise = unwrap(noise);
		if(noise.minValue() == noise.maxValue()) {
			return noise.minValue();
		}
		throw UnsupportedGraph.INSTANCE;
	}

	private static float curveCode(Object curve) {
		if(curve == Interpolation.LINEAR) {
			return 0.0F;
		}
		if(curve == Interpolation.CURVE3) {
			return 1.0F;
		}
		if(curve == Interpolation.CURVE4) {
			return 2.0F;
		}
		throw UnsupportedGraph.INSTANCE;
	}

	private static float cellFunctionCode(CellFunction function) {
		return switch(function) {
			case CELL_VALUE -> 0.0F;
			case NOISE_LOOKUP -> 1.0F;
			case DISTANCE -> 2.0F;
		};
	}

	private static float distanceFunctionCode(DistanceFunction function) {
		return switch(function) {
			case EUCLIDEAN -> 0.0F;
			case MANHATTAN -> 1.0F;
			case NATURAL -> 2.0F;
		};
	}

	private static float edgeFunctionCode(EdgeFunction function) {
		return switch(function) {
			case DISTANCE_2 -> 0.0F;
			case DISTANCE_2_ADD -> 1.0F;
			case DISTANCE_2_SUB -> 2.0F;
			case DISTANCE_2_MUL -> 3.0F;
			case DISTANCE_2_DIV -> 4.0F;
		};
	}

	private record Coordinates(int x, int z) {
		private static final Coordinates ROOT = new Coordinates(-1, -1);
	}

	private record CompileKey(Noise noise, Coordinates coordinates, long seedOffset) {
		@Override
		public boolean equals(Object object) {
			return object instanceof CompileKey other && this.noise == other.noise && this.coordinates.equals(other.coordinates) && this.seedOffset == other.seedOffset;
		}

		@Override
		public int hashCode() {
			int hash = System.identityHashCode(this.noise);
			hash = 31 * hash + this.coordinates.hashCode();
			return 31 * hash + Long.hashCode(this.seedOffset);
		}
	}

	private static final class UnsupportedGraph extends RuntimeException {
		private static final UnsupportedGraph INSTANCE = new UnsupportedGraph();

		private UnsupportedGraph() {
			super(null, null, false, false);
		}
	}
}
