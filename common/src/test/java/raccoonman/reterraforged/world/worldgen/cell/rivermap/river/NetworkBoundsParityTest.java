package raccoonman.reterraforged.world.worldgen.cell.rivermap.river;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Random;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.data.worldgen.preset.settings.RiverSettings;
import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.lake.Lake;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.lake.LakeConfig;
import raccoonman.reterraforged.world.worldgen.cell.rivermap.wetland.Wetland;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.NoiseUtil.Vec2f;

class NetworkBoundsParityTest {
    private static final Levels LEVELS = new Levels(384, 63);
    private static final LakeConfig LAKE_CONFIG = LakeConfig.of(
        new RiverSettings.Lake(1.0F, 0.025F, 0.05F, 8, 70, 130, 1, 4),
        LEVELS
    );

    @Test
    void boundedChildTraversalMatchesOriginalRiverTreeByRawBits() {
        Network.Builder root = Network.builder(carver(-3600.0F, -500.0F, 3600.0F, 500.0F, 11, true));
        addBranch(root, -2200.0F, -250.0F, -3500.0F, -2600.0F, 21, 31);
        addBranch(root, -900.0F, -100.0F, -1700.0F, 3000.0F, 41, 51);
        addBranch(root, 700.0F, 90.0F, 1800.0F, -3100.0F, 61, 71);
        addBranch(root, 2300.0F, 300.0F, 3700.0F, 2500.0F, 81, 91);

        Network original = root.build();
        Network bounded = root.buildBounded();
        Random random = new Random(0x5EEDB0A7L);
        int prunableTopLevelSamples = 0;
        for(int sample = 0; sample < 300_000; sample++) {
            float x = -5200.0F + random.nextFloat() * 10_400.0F;
            float z = -4200.0F + random.nextFloat() * 8_400.0F;
            float lakeOffsetX = -40.0F + random.nextFloat() * 80.0F;
            float lakeOffsetZ = -40.0F + random.nextFloat() * 80.0F;

            Cell expected = cell(random, sample);
            Cell actual = new Cell();
            actual.copyFrom(expected);
            if(original.contains(x, z)) {
                original.carve(expected, x, z, lakeOffsetX, lakeOffsetZ);
                bounded.carveBounded(actual, x, z, lakeOffsetX, lakeOffsetZ);
                boolean missesAnyChild = false;
                for(Network child : bounded.children()) {
                    missesAnyChild |= !child.contains(x, z);
                }
                if(missesAnyChild) {
                    prunableTopLevelSamples++;
                }
            }

            assertCellEquals(expected, actual, sample, x, z);
        }
        assertTrue(prunableTopLevelSamples > 100_000,
            "corpus must exercise points inside the root but outside child subtrees");
    }

    private static void addBranch(Network.Builder root, float x1, float z1, float x2, float z2, int seed, int grandchildSeed) {
        Network.Builder child = Network.builder(carver(x1, z1, x2, z2, seed, false));
        float midpointX = (x1 + x2) * 0.5F;
        float midpointZ = (z1 + z2) * 0.5F;
        child.lakes.add(new Lake(new Vec2f(midpointX, midpointZ), 105.0F, 1.0F, LAKE_CONFIG));
        child.wetlands.add(new Wetland(
            seed + 1000,
            new Vec2f(x1 * 0.75F + x2 * 0.25F, z1 * 0.75F + z2 * 0.25F),
            new Vec2f(x1 * 0.25F + x2 * 0.75F, z1 * 0.25F + z2 * 0.75F),
            135.0F,
            LEVELS
        ));
        float dx = x2 - x1;
        float dz = z2 - z1;
        Network.Builder grandchild = Network.builder(carver(
            x1 - dz * 0.45F,
            z1 + dx * 0.45F,
            x1,
            z1,
            grandchildSeed,
            false
        ));
        child.children.add(grandchild);
        root.children.add(child);
    }

    private static RiverCarver carver(float x1, float z1, float x2, float z2, int seed, boolean main) {
        RiverConfig config = RiverConfig.builder(LEVELS)
            .main(main)
            .order(main ? 0 : 1)
            .bedWidth(main ? 7 : 4)
            .bankWidth(main ? 24 : 15)
            .bedDepth(main ? 7 : 5)
            .bankHeight(1, 4)
            .fade(0.7F)
            .length(5000)
            .build();
        RiverCarver.Settings settings = new RiverCarver.Settings();
        settings.connecting = !main;
        settings.valleySize = main ? 320.0F : 210.0F;
        settings.fadeIn = 0.7F;
        RiverWarp warp = new RiverWarp(seed, 0.15F, 0.8F, 7.5E-4F, main ? 165.0F : 115.0F);
        return new RiverCarver(new River(x1, z1, x2, z2), warp, config, settings, LEVELS);
    }

    private static Cell cell(Random random, int sample) {
        Cell cell = new Cell();
        cell.height = 0.25F + random.nextFloat() * 0.7F;
        cell.continentEdge = random.nextFloat();
        cell.riverMask = 0.35F + random.nextFloat() * 0.65F;
        cell.terrain = sample % 5 == 0 ? TerrainType.MOUNTAINS_1 : TerrainType.HILLS;
        return cell;
    }

    private static void assertCellEquals(Cell expected, Cell actual, int sample, float x, float z) {
        String message = "sample=" + sample + ", x=" + x + ", z=" + z;
        assertEquals(Float.floatToRawIntBits(expected.height), Float.floatToRawIntBits(actual.height), "height " + message);
        assertEquals(Float.floatToRawIntBits(expected.riverMask), Float.floatToRawIntBits(actual.riverMask), "riverMask " + message);
        assertEquals(expected.erosionMask, actual.erosionMask, "erosionMask " + message);
        assertEquals(expected.terrain, actual.terrain, "terrain " + message);
    }
}
