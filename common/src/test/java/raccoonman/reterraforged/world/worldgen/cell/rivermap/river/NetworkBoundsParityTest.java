package raccoonman.reterraforged.world.worldgen.cell.rivermap.river;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import org.junit.jupiter.api.Test;

import raccoonman.reterraforged.world.worldgen.cell.Cell;
import raccoonman.reterraforged.world.worldgen.cell.heightmap.Levels;
import raccoonman.reterraforged.world.worldgen.cell.terrain.TerrainType;
import raccoonman.reterraforged.world.worldgen.noise.module.Line;
import raccoonman.reterraforged.world.worldgen.util.Boundsf;
import raccoonman.reterraforged.world.worldgen.util.PosUtil;

class NetworkBoundsParityTest {
    private static final Levels LEVELS = new Levels(384, 63);

    @Test
    void productionTreeKeepsAggregateBoundsOnTheRootOnly() {
        Network.Builder root = Network.builder(carver(-1000.0F, 1000.0F, 1000.0F, 1000.0F, 101, true));
        Network.Builder child = Network.builder(carver(-100.0F, 0.0F, 100.0F, 0.0F, 102, false));
        child.children.add(Network.builder(carver(-50.0F, -200.0F, 50.0F, -100.0F, 103, false)));
        root.children.add(child);

        Network network = root.build();

        assertNotSame(Boundsf.NONE, network.bounds());
        assertSame(Boundsf.NONE, network.children()[0].bounds());
        assertSame(Boundsf.NONE, network.children()[0].children()[0].bounds());
    }

    @Test
    void rejectedChildBoundsCanMissAValidRiverBankSample() {
        Network.Builder root = Network.builder(carver(-1000.0F, 1000.0F, 1000.0F, 1000.0F, 101, true));
        root.carver.warp = RiverWarp.NONE;
        Network.Builder child = Network.builder(carver(-100.0F, 0.0F, 100.0F, 0.0F, 102, false));
        child.carver.warp = RiverWarp.NONE;
        root.children.add(child);

        Network original = root.build();
        Network rejected = buildRejectedBoundedTree(root);
        float x = 0.0F;
        float z = 10.0F;
        assertFalse(rejected.children()[0].contains(x, z),
            "the rejected child bound contains only the river center line");

        Cell expected = new Cell();
        expected.height = 0.7F;
        expected.continentEdge = 1.0F;
        expected.riverMask = 1.0F;
        expected.terrain = TerrainType.HILLS;
        Cell actual = new Cell();
        actual.copyFrom(expected);

        original.carve(expected, x, z, 0.0F, 0.0F);
        carveRejectedBoundedTree(rejected, actual, x, z, 0.0F, 0.0F);

        assertNotEquals(Float.floatToRawIntBits(expected.height), Float.floatToRawIntBits(actual.height));
        assertNotEquals(Float.floatToRawIntBits(expected.riverMask), Float.floatToRawIntBits(actual.riverMask));
    }

    private static Network buildRejectedBoundedTree(Network.Builder builder) {
        Boundsf.Builder bounds = Boundsf.builder();
        recordBounds(builder, bounds);
        return new Network(
            builder.carver,
            builder.lakes.toArray(raccoonman.reterraforged.world.worldgen.cell.rivermap.lake.Lake[]::new),
            builder.wetlands.toArray(raccoonman.reterraforged.world.worldgen.cell.rivermap.wetland.Wetland[]::new),
            builder.children.stream().map(NetworkBoundsParityTest::buildRejectedBoundedTree).toArray(Network[]::new),
            bounds.build()
        );
    }

    private static void recordBounds(Network.Builder network, Boundsf.Builder bounds) {
        bounds.record(network.carver.river.minX, network.carver.river.minZ);
        bounds.record(network.carver.river.maxX, network.carver.river.maxZ);
        network.children.forEach(child -> recordBounds(child, bounds));
        network.lakes.forEach(lake -> lake.recordBounds(bounds));
        network.wetlands.forEach(wetland -> wetland.recordBounds(bounds));
    }

    private static void carveRejectedBoundedTree(Network network, Cell cell, float x, float z, float nx, float nz) {
        RiverCarver riverCarver = network.riverCarver();
        River river = riverCarver.river;
        RiverWarp warp = riverCarver.warp;
        float t = Line.distanceOnLine(x, z, river.x1, river.z1, river.x2, river.z2);
        float px = x;
        float pz = z;
        float pt = t;
        if(warp.test(t)) {
            long offset = warp.getOffset(x, z, pt, river);
            x += PosUtil.unpackLeftf(offset);
            z += PosUtil.unpackRightf(offset);
            t = Line.distanceOnLine(x, z, river.x1, river.z1, river.x2, river.z2);
        }
        riverCarver.carve(cell, px, pz, pt, x, z, t);
        for(var wetland : network.wetlands()) {
            wetland.apply(cell, x + nx, z + nz, x, z);
        }
        for(var lake : network.lakes()) {
            lake.apply(cell, x + nx, z + nz);
        }
        for(Network child : network.children()) {
            if(child.contains(x, z)) {
                carveRejectedBoundedTree(child, cell, x, z, nx, nz);
            }
        }
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
}
