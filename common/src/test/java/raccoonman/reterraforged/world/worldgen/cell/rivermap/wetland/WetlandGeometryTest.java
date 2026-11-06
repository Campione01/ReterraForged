package raccoonman.reterraforged.world.worldgen.cell.rivermap.wetland;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil.Vec2f;
import raccoonman.reterraforged.world.worldgen.noise.module.Line;
import raccoonman.reterraforged.world.worldgen.noise.module.Noise;
import raccoonman.reterraforged.world.worldgen.noise.module.Noises;

class WetlandGeometryTest {
    private static final Levels LEVELS = new Levels(256, 63);
    private static final Vec2f A = new Vec2f(-28.0F, 11.0F);
    private static final Vec2f B = new Vec2f(172.0F, 11.0F);
    private static final float RADIUS = 100.0F;

    @BeforeAll
    static void bootstrapMinecraft() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    // The upstream geometry is the oracle for flat cells below the port's height fade.
    @Test
    void matchesDonorAcrossTheFlatValleyAndPools() {
        float bed = LEVELS.water(-1) - 0.5F / LEVELS.worldHeight;
        float[] heights = {Math.nextDown(bed), bed, LEVELS.water(0), LEVELS.ground(3), LEVELS.ground(18)};
        for (int seed : new int[] {42, -987654321}) {
            for (Vec2f end : new Vec2f[] {B, new Vec2f(137.0F, 89.0F)}) {
                Wetland actual = new Wetland(seed, A, end, RADIUS, LEVELS);
                FlatDonor donor = new FlatDonor(seed, A, end);
                int wet = 0;
                for (int rx = -132; rx <= 276; rx += 4) {
                    for (int rz = -93; rz <= 193; rz += 4) {
                        for (float height : heights) {
                            // Geometry samples rx/rz; noise samples a different x/z pair.
                            Cell result = compareAt(actual, donor, height, rx, rz, rx + 127.25F, rz - 77.5F);
                            if (result.terrain == TerrainType.WETLAND) wet++;
                        }
                    }
                }
                assertTrue(wet > 0, "No tagged wetland cells for seed=" + seed + " end=" + end);
            }
        }
    }

    @Test
    void lowersTheOuterValleyWithoutTaggingItAndLeavesTheRadiusBoundaryUntouched() {
        Wetland actual = new Wetland(42, A, B, RADIUS, LEVELS);
        FlatDonor donor = new FlatDonor(42, A, B);
        float initial = LEVELS.ground(18);
        float midX = 72.0F;
        Cell valley = compareAt(actual, donor, initial, midX, 91.0F, 901.0F, -230.0F);
        assertTrue(valley.height < initial && valley.height > LEVELS.ground(3), "Valley-only lowering was not reached");
        assertTrue(valley.riverMask < 1.0F);
        assertSame(TerrainType.FLATS, valley.terrain);
        assertFalse(valley.erosionMask);

        float edgeZ = A.y() + RADIUS;
        compareAt(actual, donor, initial, midX, Math.nextDown(edgeZ), 901.0F, -230.0F);
        for (float z : new float[] {edgeZ, Math.nextUp(edgeZ)}) {
            assertCell(flat(initial), compareAt(actual, donor, initial, midX, z, 901.0F, -230.0F), "radius boundary z=" + z);
        }
    }

    @Test
    void treatsCoincidentEndpointsAsAFinitePoint() {
        // The donor divides by zero for coincident endpoints; retain the host's radial guard instead.
        Wetland point = new Wetland(42, A, A, RADIUS, LEVELS);
        Wetland segment = new Wetland(42, A, B, RADIUS, LEVELS);
        float initial = LEVELS.ground(18);
        for (float offset : new float[] {0.0F, 40.0F, 80.0F, 100.0F, 101.0F}) {
            Cell expected = flat(initial);
            Cell actual = flat(initial);
            float rz = A.y() + offset;
            segment.apply(expected, A.x(), rz, 42.0F, -13.0F);
            point.apply(actual, A.x(), rz, 42.0F, -13.0F);
            assertTrue(Float.isFinite(actual.height) && Float.isFinite(actual.riverMask), "offset=" + offset);
            assertCell(expected, actual, "point offset=" + offset);
        }
    }

    @Test
    void blendsValleyLoweringContinuouslyAcrossTheMountainEdge() {
        Wetland wetland = new Wetland(42, A, B, RADIUS, LEVELS);
        float high = LEVELS.ground(18);
        float z = A.y() + 80.0F;
        float band = (1.0F - 80.0F * 80.0F / (RADIUS * RADIUS)) / 0.65F;
        Cell flat = flat(high);
        wetland.apply(flat, 72.0F, z, 901.0F, -230.0F);
        float previousHeight = Float.NEGATIVE_INFINITY;
        for (int i = 0; i <= 8; i++) {
            float edge = 0.35F * i / 8;
            Cell aboveBank = mountain(high, edge);
            wetland.apply(aboveBank, 72.0F, z, 901.0F, -230.0F);
            float t = edge / 0.35F;
            float alpha = band * (1.0F - 0.8F * t * t * (3.0F - 2.0F * t));
            assertEquals(NoiseUtil.lerp(high, LEVELS.ground(3), alpha), aboveBank.height, 1.0e-7F,
                    "valley lowering at mountain edge " + edge);
            assertEquals(1.0F - alpha, aboveBank.riverMask, 1.0e-7F, "river mask at mountain edge " + edge);
            assertTrue(aboveBank.height > previousHeight, "Lowering should taper through the mountain edge");
            assertSame(TerrainType.MOUNTAINS_1, aboveBank.terrain);
            if (i == 0) {
                assertEquals(flat.height, aboveBank.height, "A mountain at the boundary must meet the flat side");
                assertEquals(flat.riverMask, aboveBank.riverMask);
            }
            previousHeight = aboveBank.height;
        }
        Cell interior = mountain(high, 1.0F);
        wetland.apply(interior, 72.0F, z, 901.0F, -230.0F);
        assertEquals(previousHeight, interior.height);
    }

    @Test
    void fadesPoolsAtMountainEdgesWithoutTaggingMountainCells() {
        Wetland wetland = new Wetland(42, A, B, RADIUS, LEVELS);
        float bank = LEVELS.ground(3);
        Cell flat = flat(bank);
        Cell edge = mountain(bank, 0.0F);
        Cell middle = mountain(bank, 0.2625F);
        Cell interior = mountain(bank, 0.35F);
        for (Cell cell : new Cell[] {flat, edge, middle, interior}) {
            wetland.apply(cell, 72.0F, A.y(), 901.0F, -230.0F);
        }
        assertSame(TerrainType.WETLAND, flat.terrain, "The flat pool must exercise the wetland tag");
        assertEquals(flat.height, edge.height, "The mountain boundary must have the same pool geometry");
        assertTrue(edge.height < middle.height && middle.height < bank, "A partial pool should reach the soft edge");
        assertEquals(65.140625F / LEVELS.worldHeight, middle.height, 1.0e-7F, "Soft-edge pool before mounds");
        assertEquals(bank, interior.height, "No pool in the mountain interior");
        for (Cell cell : new Cell[] {edge, middle, interior}) {
            assertSame(TerrainType.MOUNTAINS_1, cell.terrain);
        }
    }

    @Test
    void retainsTheMountainErosionFootprintAndHeightFade() {
        Wetland wetland = new Wetland(42, A, B, RADIUS, LEVELS);
        float high = LEVELS.ground(18);
        Cell center = mountain(high, 1.0F);
        wetland.apply(center, 72.0F, A.y(), 901.0F, -230.0F);
        assertTrue(center.erosionMask);
        Cell farFromAxis = mountain(high, 1.0F);
        wetland.apply(farFromAxis, 72.0F, A.y() + 85.0F, 901.0F, -230.0F);
        assertTrue(farFromAxis.height < high);
        assertFalse(farFromAxis.erosionMask, "The new falloff must not widen the old erosion mask");

        Cell aboveFade = mountain(LEVELS.scale(360), 0.0F);
        wetland.apply(aboveFade, 72.0F, A.y(), 901.0F, -230.0F);
        assertCell(mountain(LEVELS.scale(360), 0.0F), aboveFade, "at fade end");
    }

    private static Cell compareAt(Wetland wetland, FlatDonor donor, float height, float rx, float rz, float x, float z) {
        Cell expected = flat(height);
        Cell actual = flat(height);
        donor.apply(expected, rx, rz, x, z);
        wetland.apply(actual, rx, rz, x, z);
        assertCell(expected, actual, "seed=" + donor.seed() + " warped=" + rx + "," + rz + " noise=" + x + "," + z + " inputHeight=" + height);
        return actual;
    }

    private static Cell flat(float height) {
        Cell cell = new Cell();
        cell.height = height;
        cell.terrain = TerrainType.FLATS;
        return cell;
    }

    private static Cell mountain(float height, float edge) {
        Cell cell = flat(height);
        cell.terrain = TerrainType.MOUNTAINS_1;
        cell.terrainRegionEdge = edge;
        return cell;
    }

    private static void assertCell(Cell expected, Cell actual, String where) {
        assertEquals(Float.floatToRawIntBits(expected.height), Float.floatToRawIntBits(actual.height), "height " + where);
        assertEquals(Float.floatToRawIntBits(expected.riverMask), Float.floatToRawIntBits(actual.riverMask), "river mask " + where);
        assertEquals(expected.erosionMask, actual.erosionMask, "erosion mask " + where);
        assertSame(expected.terrain, actual.terrain, "terrain " + where);
    }

    // Donor 1033bece7502, using the current Levels and the same host noise implementations.
    private record FlatDonor(int seed, Vec2f a, Vec2f b, Noise moundShape, Noise moundHeight, Noise terrainEdge) {
        FlatDonor(int seed, Vec2f a, Vec2f b) {
            this(seed, a, b,
                    Noises.map(Noises.clamp(Noises.perlin(seed + 1, 10, 1), 0.3F, 0.6F), 0.0F, 1.0F),
                    Noises.map(Noises.clamp(Noises.simplex(seed + 2, 20, 1), 0.0F, 0.3F), 0.0F, 1.0F),
                    Noises.map(Noises.clamp(Noises.perlin(seed + 3, 8, 1), 0.2F, 0.8F), 0.0F, 0.9F));
        }

        void apply(Cell cell, float rx, float rz, float x, float z) {
            float bed = LEVELS.water(-1) - 0.5F / LEVELS.worldHeight;
            if (cell.height < bed) return;
            float banks = LEVELS.ground(3), moundMin = LEVELS.water(1), moundMax = LEVELS.water(2);
            float t = Line.distanceOnLine(rx, rz, a.x(), a.y(), b.x(), b.y());
            float d2 = t <= 0.0F ? Line.distSq(rx, rz, a.x(), a.y()) : t >= 1.0F ? Line.distSq(rx, rz, b.x(), b.y())
                    : Line.distSq(rx, rz, a.x() + t * (b.x() - a.x()), a.y() + t * (b.y() - a.y()));
            if (d2 > RADIUS * RADIUS) return;
            float dist = 1.0F - d2 / (RADIUS * RADIUS);
            if (dist <= 0.0F) return;
            float valley = NoiseUtil.map(dist, 0.0F, 0.65F, 0.65F);
            if (cell.height > banks) cell.height = NoiseUtil.lerp(cell.height, banks, valley);
            float pools = NoiseUtil.map(dist, 0.65F, 0.7F, 0.050000012F);
            if (cell.height > bed && cell.height <= banks) cell.height = NoiseUtil.lerp(cell.height, bed, pools);
            if (pools >= 1.0F) cell.erosionMask = true;
            if (dist > 0.65F && pools > terrainEdge.compute(x, z, 0)) cell.terrain = TerrainType.WETLAND;
            if (cell.height >= bed && cell.height < moundMax) {
                float shape = moundShape.compute(x, z, 0) * pools;
                float mounds = moundMin + moundHeight.compute(x, z, 0) * (moundMax - moundMin);
                cell.height = NoiseUtil.lerp(cell.height, mounds, shape);
            }
            cell.riverMask = Math.min(cell.riverMask, 1.0F - valley);
        }
    }
}
