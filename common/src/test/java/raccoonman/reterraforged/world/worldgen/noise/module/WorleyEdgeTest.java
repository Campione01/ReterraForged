package raccoonman.reterraforged.world.worldgen.noise.module;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.world.worldgen.noise.function.DistanceFunction;
import raccoonman.reterraforged.world.worldgen.noise.function.EdgeFunction;

class WorleyEdgeTest {
    private static final long PINNED_HASH = 0x16B036EDBE8CB9C6L;

    // Pins every finite result of the sampler bit for bit.
    @Test
    void finiteResultsAreBitIdentical() {
        long hash = 0xCBF29CE484222325L;
        int count = 0;
        int[] seeds = {0, 1, 42, -987654321, 0x7FFFFFFF};
        float[] jitters = {0.0F, 0.5F, 1.0F};
        for (EdgeFunction edge : EdgeFunction.values()) {
            for (DistanceFunction distance : DistanceFunction.values()) {
                for (int seed : seeds) {
                    for (float jitter : jitters) {
                        for (int i = 0; i < 40; i++) {
                            for (int j = 0; j < 40; j++) {
                                float x = -37.3F + i * 1.9137F;
                                float y = 29.7F - j * 1.7711F;
                                float result = WorleyEdge.sample(x, y, seed, jitter, edge, distance);
                                hash = (hash ^ Float.floatToRawIntBits(result)) * 0x100000001B3L;
                                count++;
                            }
                        }
                    }
                }
            }
        }
        assertEquals(360000, count);
        assertEquals(PINNED_HASH, hash);
    }

    // At 2^24 the float spacing is 2, so with jitter 0 the cells xi and xi+1 (in x and in y) of the 3x3 block share
    // the feature point (2^24, 2^24). Sampling exactly there gives two nearest distances of 0, i.e. 0/0.
    @Test
    void sharedFeaturePointAtTwoToThe24ReturnsTheMinimum() {
        float at = 16777216.0F;
        for (DistanceFunction distance : DistanceFunction.values()) {
            float result = WorleyEdge.sample(at, at, 0, 0.0F, EdgeFunction.DISTANCE_2_DIV, distance);
            assertEquals(EdgeFunction.DISTANCE_2_DIV.min(), result, distance.name());
        }
    }
}
