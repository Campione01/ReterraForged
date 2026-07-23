package raccoonman.reterraforged.world.worldgen.densityfunction.tile.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Field;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.FilterSettings;

class ErosionBrushPackingTest {
    private static final int RADIUS = 4;

    @Test
    void packedBrushesPreserveOriginalIndicesAndWeightBits() throws ReflectiveOperationException {
        for (int size : new int[] { 9, 16, 33, 130 }) {
            Erosion erosion = new Erosion(1, size, settings(), Modifier.range(0.2F, 0.8F));
            long[][] actual = brushes(erosion);
            Brush[] expected = legacyBrushes(size, RADIUS);
            assertEquals(expected.length, actual.length, "brush count at size " + size);
            for (int brushIndex = 0; brushIndex < expected.length; brushIndex++) {
                Brush legacy = expected[brushIndex];
                long[] packed = actual[brushIndex];
                assertEquals(legacy.indices.length, packed.length, "brush length at size " + size + ", index " + brushIndex);
                for (int point = 0; point < packed.length; point++) {
                    assertEquals(legacy.indices[point], (int)(packed[point] >>> 32),
                        "node index at size " + size + ", brush " + brushIndex + ", point " + point);
                    assertEquals(Float.floatToRawIntBits(legacy.weights[point]), (int)packed[point],
                        "weight bits at size " + size + ", brush " + brushIndex + ", point " + point);
                }
            }
        }
    }

    @Test
    void flatSharedRelativeBrushesPreserveOriginalIndicesAndWeightBits() throws ReflectiveOperationException {
        for(int size : new int[] { 9, 16, 33, 130 }) {
            Erosion erosion = new Erosion(1, size, settings(), Modifier.range(0.2F, 0.8F), true);
            FlatPoints actual = flatSharedPoints(erosion);
            Brush[] expected = legacyBrushes(size, RADIUS);
            Set<Integer> templateStarts = new HashSet<>();
            assertEquals(expected.length, actual.starts.length, "brush count at size " + size);
            for(int brushIndex = 0; brushIndex < expected.length; brushIndex++) {
                Brush legacy = expected[brushIndex];
                int start = actual.starts[brushIndex];
                templateStarts.add(start);
                int length = Byte.toUnsignedInt(actual.lengths[brushIndex]);
                assertEquals(legacy.indices.length, length, "brush length at size " + size + ", index " + brushIndex);
                for(int point = 0; point < length; point++) {
                    int flatIndex = start + point;
                    assertEquals(legacy.indices[point], brushIndex + actual.offsets[flatIndex],
                        "node index at size " + size + ", brush " + brushIndex + ", point " + point);
                    assertEquals(Float.floatToRawIntBits(legacy.weights[point]), Float.floatToRawIntBits(actual.weights[flatIndex]),
                        "weight bits at size " + size + ", brush " + brushIndex + ", point " + point);
                }
            }
            if(size >= 16) {
                org.junit.jupiter.api.Assertions.assertTrue(templateStarts.size() < actual.starts.length / 2,
                    "flat layout should reuse template ranges at size " + size);
            }
        }
    }

    private static FilterSettings.Erosion settings() {
        return new FilterSettings.Erosion(1, 30, 1.0F, 1.0F, 0.3F, 0.3F);
    }

    private static long[][] brushes(Erosion erosion) throws ReflectiveOperationException {
        Field field = Erosion.class.getDeclaredField("erosionBrushes");
        field.setAccessible(true);
        return (long[][])field.get(erosion);
    }

    private static FlatPoints flatSharedPoints(Erosion erosion) throws ReflectiveOperationException {
        Field field = Erosion.class.getDeclaredField("flatSharedErosionBrushes");
        field.setAccessible(true);
        Object brushes = field.get(erosion);
        Field startsField = brushes.getClass().getDeclaredField("starts");
        Field lengthsField = brushes.getClass().getDeclaredField("lengths");
        Field offsetsField = brushes.getClass().getDeclaredField("offsets");
        Field weightsField = brushes.getClass().getDeclaredField("weights");
        startsField.setAccessible(true);
        lengthsField.setAccessible(true);
        offsetsField.setAccessible(true);
        weightsField.setAccessible(true);
        return new FlatPoints(
            (int[])startsField.get(brushes),
            (byte[])lengthsField.get(brushes),
            (int[])offsetsField.get(brushes),
            (float[])weightsField.get(brushes)
        );
    }

    private static Brush[] legacyBrushes(int size, int radius) {
        Brush[] brushes = new Brush[size * size];
        int[] xOffsets = new int[radius * radius * 4];
        int[] yOffsets = new int[radius * radius * 4];
        float[] weights = new float[radius * radius * 4];
        float weightSum = 0.0F;
        int addIndex = 0;
        for (int i = 0; i < brushes.length; i++) {
            int centreX = i % size;
            int centreY = i / size;
            if (centreY <= radius || centreY >= size - radius || centreX <= radius + 1 || centreX >= size - radius) {
                weightSum = 0.0F;
                addIndex = 0;
                for (int y = -radius; y <= radius; y++) {
                    for (int x = -radius; x <= radius; x++) {
                        float distance2 = x * x + y * y;
                        if (distance2 < radius * radius) {
                            int coordX = centreX + x;
                            int coordY = centreY + y;
                            if (coordX >= 0 && coordX < size && coordY >= 0 && coordY < size) {
                                float weight = 1.0F - (float)Math.sqrt(distance2) / radius;
                                weightSum += weight;
                                weights[addIndex] = weight;
                                xOffsets[addIndex] = x;
                                yOffsets[addIndex] = y;
                                addIndex++;
                            }
                        }
                    }
                }
            }
            int[] indices = new int[addIndex];
            float[] normalizedWeights = new float[addIndex];
            for (int j = 0; j < addIndex; j++) {
                indices[j] = (yOffsets[j] + centreY) * size + xOffsets[j] + centreX;
                normalizedWeights[j] = weights[j] / weightSum;
            }
            brushes[i] = new Brush(indices, normalizedWeights);
        }
        return brushes;
    }

    private record Brush(int[] indices, float[] weights) {
    }

    private record FlatPoints(int[] starts, byte[] lengths, int[] offsets, float[] weights) {
    }
}
