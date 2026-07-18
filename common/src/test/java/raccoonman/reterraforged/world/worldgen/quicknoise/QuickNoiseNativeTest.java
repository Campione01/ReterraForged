package raccoonman.reterraforged.world.worldgen.quicknoise;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class QuickNoiseNativeTest {
	@Test
	void loadsBestBackendAndGeneratesDeterministicTile() {
		assertTrue(QuickNoiseNative.initialize());
		assertTrue(QuickNoiseNative.backendName().matches("cave=(avx512|avx2|scalar),quick_v2=(avx512|avx2|sse42)"));

		float[] first = new float[QuickNoiseNative.TILE_SAMPLES];
		float[] repeated = new float[QuickNoiseNative.TILE_SAMPLES];
		assertTrue(QuickNoiseNative.fillTile(0x5EED1234L, -2, -1, 3, 0.32F, 0.085F, 0.055F, first));
		assertTrue(QuickNoiseNative.fillTile(0x5EED1234L, -2, -1, 3, 0.32F, 0.085F, 0.055F, repeated));
		assertArrayEquals(first, repeated);
	}

	@Test
	void executesWarpedProgramWithStableOverlapsAndLifecycle() {
		QuickNoiseGraph.Builder builder = QuickNoiseGraph.builder();
		int x = builder.coordinateX();
		int z = builder.coordinateZ();
		int phase = builder.unary(QuickNoiseGraph.Opcode.SIN, builder.binary(QuickNoiseGraph.Opcode.MULTIPLY, x, builder.constant(0.017F)));
		int warpedX = builder.binary(QuickNoiseGraph.Opcode.ADD, x, builder.binary(QuickNoiseGraph.Opcode.MULTIPLY, phase, builder.constant(23.0F)));
		int warpedZ = builder.binary(QuickNoiseGraph.Opcode.ADD, z, builder.binary(QuickNoiseGraph.Opcode.MULTIPLY, phase, builder.constant(-17.0F)));
		int noise = builder.primitive(QuickNoiseGraph.Opcode.PERLIN_FBM, 4, warpedX, warpedZ, 91L, 0.008F, 2.1F, 0.52F, 1.0F, 1.0F, 1.0F);
		int magnitude = builder.unary(QuickNoiseGraph.Opcode.ABS, noise);
		int power = builder.binary(QuickNoiseGraph.Opcode.POW_DYNAMIC, magnitude, builder.constant(1.7F));
		int stepped = builder.binary(QuickNoiseGraph.Opcode.DIVIDE, builder.unary(QuickNoiseGraph.Opcode.ROUND, builder.binary(QuickNoiseGraph.Opcode.MULTIPLY, power, builder.constant(12.0F))), builder.constant(12.0F));
		int selector = builder.binary(QuickNoiseGraph.Opcode.GREATER_EQUAL, power, builder.constant(0.35F));
		QuickNoiseGraph graph = builder.build(builder.ternary(QuickNoiseGraph.Opcode.LERP, selector, power, stepped));

		QuickNoiseNative.Program program = QuickNoiseNative.compileProgram(graph.encode());
		float[] full = new float[41 * 37];
		float[] repeated = new float[41 * 37];
		float[] overlap = new float[9 * 11];
		program.fill(771L, -23, -19, 41, 37, full);
		program.fill(771L, -23, -19, 41, 37, repeated);
		program.fill(771L, -7, -8, 9, 11, overlap);
		assertArrayEquals(full, repeated);
		for(int zIndex = 0; zIndex < 11; zIndex++) {
			for(int xIndex = 0; xIndex < 9; xIndex++) {
				int fullIndex = (zIndex + 11) * 41 + xIndex + 16;
				assertEquals(Float.floatToRawIntBits(full[fullIndex]), Float.floatToRawIntBits(overlap[zIndex * 9 + xIndex]));
			}
		}

		program.close();
		assertThrows(IllegalStateException.class, () -> program.fill(0L, 0, 0, 1, 1, new float[1]));
	}
}
