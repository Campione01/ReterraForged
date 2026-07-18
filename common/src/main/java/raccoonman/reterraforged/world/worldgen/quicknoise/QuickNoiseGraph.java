package raccoonman.reterraforged.world.worldgen.quicknoise;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

public final class QuickNoiseGraph {
	private static final int MAGIC = 0x32564E51;
	private static final int VERSION = 2;
	private static final int HEADER_BYTES = 20;
	private static final int NODE_BYTES = 48;

	private final List<Node> nodes;
	private final int[] roots;

	private QuickNoiseGraph(List<Node> nodes, int[] roots) {
		this.nodes = List.copyOf(nodes);
		this.roots = roots.clone();
	}

	public byte[] encode() {
		int size = Math.addExact(HEADER_BYTES, Math.addExact(Math.multiplyExact(this.nodes.size(), NODE_BYTES), Math.multiplyExact(this.roots.length, Integer.BYTES)));
		ByteBuffer buffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN);
		buffer.putInt(MAGIC);
		buffer.putInt(VERSION);
		buffer.putInt(this.nodes.size());
		buffer.putInt(this.roots.length);
		buffer.putInt(NODE_BYTES);
		for(Node node : this.nodes) {
			buffer.putInt(node.opcode.code);
			buffer.putInt(node.inputA);
			buffer.putInt(node.inputB);
			buffer.putInt(node.inputC);
			buffer.putLong(node.seedOffset);
			buffer.putFloat(node.param0);
			buffer.putFloat(node.param1);
			buffer.putFloat(node.param2);
			buffer.putFloat(node.param3);
			buffer.putFloat(node.param4);
			buffer.putFloat(node.param5);
		}
		for(int root : this.roots) {
			buffer.putInt(root);
		}
		return buffer.array();
	}

	public int nodeCount() {
		return this.nodes.size();
	}

	public int outputCount() {
		return this.roots.length;
	}

	public static Builder builder() {
		return new Builder();
	}

	public enum Opcode {
		CONSTANT(0),
		PERLIN_FBM(1),
		VALUE_FBM(2),
		SIMPLEX_FBM(3),
		CELLULAR_FBM(4),
		PERLIN_BILLOW(5),
		PERLIN_RIDGED(6),
		SIMPLEX_RIDGED(7),
		ADD(16),
		MULTIPLY(17),
		MIN(18),
		MAX(19),
		ABS(20),
		CLAMP(21),
		MAP(22),
		INVERT(23),
		CURVE3(24),
		CURVE5(25),
		LERP(26),
		POW(27),
		GREATER(28),
		SIGNED_POW(29),
		BOOST(30),
		STEPS(31),
		COORD_X(32),
		COORD_Z(33),
		SIN(34),
		COS(35),
		DIVIDE(36),
		POW_DYNAMIC(37),
		ROUND(38),
		GREATER_EQUAL(39);

		private final int code;

		Opcode(int code) {
			this.code = code;
		}
	}

	public static final class Builder {
		private static final int MAX_NODES = 4096;
		private static final int MAX_ROOTS = 64;
		private final List<Node> nodes = new ArrayList<>();

		public int constant(float value) {
			return this.add(Opcode.CONSTANT, -1, -1, -1, 0L, value, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
		}

		public int coordinateX() {
			return this.add(Opcode.COORD_X, -1, -1, -1, 0L, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
		}

		public int coordinateZ() {
			return this.add(Opcode.COORD_Z, -1, -1, -1, 0L, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
		}

		public int primitive(Opcode opcode, int octaves, int coordinateX, int coordinateZ, long seedOffset, float frequency, float lacunarity, float persistence, float amplitude, float scaleX, float scaleZ) {
			if(opcode.ordinal() < Opcode.PERLIN_FBM.ordinal() || opcode.ordinal() > Opcode.SIMPLEX_RIDGED.ordinal()) {
				throw new IllegalArgumentException("Opcode " + opcode + " is not a QUICK_V2 primitive");
			}
			return this.add(opcode, octaves, coordinateX, coordinateZ, seedOffset, frequency, lacunarity, persistence, amplitude, scaleX, scaleZ);
		}

		public int unary(Opcode opcode, int input) {
			return this.unary(opcode, input, 0.0F, 0.0F, 0.0F, 0.0F);
		}

		public int unary(Opcode opcode, int input, float param0, float param1, float param2, float param3) {
			return this.add(opcode, input, -1, -1, 0L, param0, param1, param2, param3, 0.0F, 0.0F);
		}

		public int binary(Opcode opcode, int inputA, int inputB) {
			return this.add(opcode, inputA, inputB, -1, 0L, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
		}

		public int ternary(Opcode opcode, int inputA, int inputB, int inputC) {
			return this.add(opcode, inputA, inputB, inputC, 0L, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F, 0.0F);
		}

		public QuickNoiseGraph build(int... roots) {
			if(roots.length == 0 || roots.length > MAX_ROOTS) {
				throw new IllegalArgumentException("A QUICK_V2 graph requires between 1 and " + MAX_ROOTS + " roots");
			}
			for(int root : roots) {
				this.requireInput(root);
			}
			return new QuickNoiseGraph(this.nodes, roots);
		}

		private int add(Opcode opcode, int inputA, int inputB, int inputC, long seedOffset, float param0, float param1, float param2, float param3, float param4, float param5) {
			if(this.nodes.size() >= MAX_NODES) {
				throw new IllegalStateException("The QUICK_V2 graph exceeded " + MAX_NODES + " nodes");
			}
			requireFinite(param0);
			requireFinite(param1);
			requireFinite(param2);
			requireFinite(param3);
			requireFinite(param4);
			requireFinite(param5);
			int index = this.nodes.size();
			this.nodes.add(new Node(opcode, inputA, inputB, inputC, seedOffset, param0, param1, param2, param3, param4, param5));
			return index;
		}

		private void requireInput(int input) {
			if(input < 0 || input >= this.nodes.size()) {
				throw new IllegalArgumentException("Invalid QUICK_V2 input node " + input);
			}
		}

		private static void requireFinite(float value) {
			if(!Float.isFinite(value)) {
				throw new IllegalArgumentException("QUICK_V2 parameters must be finite");
			}
		}
	}

	private record Node(Opcode opcode, int inputA, int inputB, int inputC, long seedOffset, float param0, float param1, float param2, float param3, float param4, float param5) {
	}
}
